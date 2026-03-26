package com.hudscustomitems.customitems.service;

import com.hudscustomitems.customitems.model.CustomItemDefinition;
import dev.faststats.bukkit.BukkitMetrics;
import dev.faststats.core.ErrorTracker;
import dev.faststats.core.SimpleMetrics;
import dev.faststats.core.data.Metric;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Collects sanitized operational telemetry and forwards it to FastStats.
 */
public final class TelemetryService {
  private static final int defaultPeriodicIntervalSeconds = 300;
  private static final int defaultErrorReportDedupWindowSeconds = 120;
  private static final int defaultRecentEventBufferSize = 50;
  private static final int minPeriodicIntervalSeconds = 30;
  private static final int minErrorReportDedupWindowSeconds = 0;
  private static final int maxErrorReportDedupWindowSeconds = 3600;
  private static final int minRecentEventBufferSize = 10;
  private static final int maxRecentEventBufferSize = 500;
  private static final int recentEventMetricLimit = 10;
  private static final int exceptionContextLogEventLimit = 6;
  private static final long exceptionContextLogCooldownMs = 30_000L;
  private static final long immediateSubmitCooldownMs = 10_000L;
  private static final long fiveMinutesMs = 5L * 60L * 1000L;
  private static final long thirtyMinutesMs = 30L * 60L * 1000L;

  private static final String[] mechanicAttributes = {
      "right_click_ability",
      "shift_ability",
      "dash_ability",
      "area_ability",
      "explosion_ability",
      "time_slow_ability",
      "summon_entity",
      "block_ability",
      "projectile_launch",
      "chain_lightning"
  };

  private final JavaPlugin plugin;
  private final CustomItemService itemService;
  private final String pluginPackagePrefix;

  private final boolean enabledInConfig;
  private final boolean errorReportingEnabled;
  private final int periodicIntervalSeconds;
  private final long errorReportDedupWindowMs;
  private final int recentEventBufferSize;
  private final String token;

  private final AtomicBoolean active = new AtomicBoolean(false);
  private final AtomicBoolean telemetryFailureWarned = new AtomicBoolean(false);
  private final AtomicBoolean manualSubmitSupported = new AtomicBoolean(true);
  private final AtomicLong lastExceptionContextLogMs = new AtomicLong(0L);
  private final AtomicLong lastImmediateSubmitAttemptMs = new AtomicLong(0L);

  private final AtomicInteger customItemsLoaded = new AtomicInteger(0);
  private final AtomicInteger customItemsFailedLoad = new AtomicInteger(0);
  private final AtomicInteger mechanicsRegistered = new AtomicInteger(0);
  private final AtomicInteger pluginCommandsRegistered = new AtomicInteger(0);
  private final AtomicInteger listenerHandlersRegistered = new AtomicInteger(0);
  private final AtomicInteger onlinePlayers = new AtomicInteger(0);
  private final AtomicInteger loadedWorlds = new AtomicInteger(0);

  private final AtomicBoolean protocolLibEnabled = new AtomicBoolean(false);
  private final AtomicBoolean placeholderApiEnabled = new AtomicBoolean(false);

  private final LongAdder giveOperationsSuccess = new LongAdder();
  private final LongAdder giveOperationsFailed = new LongAdder();
  private final LongAdder commandExecutionFailed = new LongAdder();
  private final LongAdder listenerExceptions = new LongAdder();
  private final LongAdder configReloadSuccess = new LongAdder();
  private final LongAdder configReloadFailed = new LongAdder();
  private final LongAdder itemParseFailed = new LongAdder();
  private final LongAdder recipeParseFailed = new LongAdder();
  private final LongAdder recipesRegistered = new LongAdder();
  private final LongAdder recipesFailedRegister = new LongAdder();
  private final LongAdder mechanicExecutionFailed = new LongAdder();

  private final Map<Subsystem, LongAdder> subsystemFailures = new EnumMap<>(Subsystem.class);
  private final AtomicLong lastErrorTimestampMs = new AtomicLong(0L);
  private final AtomicReference<String> lastErrorClass = new AtomicReference<>("none");
  private final AtomicReference<String> lastErrorTopFrame = new AtomicReference<>("none");
  private final Object errorTimelineLock = new Object();
  private final ArrayDeque<Long> errorTimeline = new ArrayDeque<>();
  private final Object errorReportLock = new Object();
  private final Map<String, ErrorReportState> errorReportStates = new HashMap<>();
  private final LongAdder faststatsErrorsSent = new LongAdder();
  private final LongAdder faststatsErrorsSuppressed = new LongAdder();

  private final Object recentEventsLock = new Object();
  private final ArrayDeque<DiagnosticEvent> recentEvents = new ArrayDeque<>();

  private BukkitMetrics metrics;
  private SimpleMetrics submitCapableMetrics;
  private BukkitTask snapshotTask;
  private BukkitTask submitTask;

  /**
   * Creates and initializes one telemetry service instance.
   *
   * @param plugin plugin instance
   * @param itemService item service
   * @return initialized telemetry service
   */
  public static TelemetryService create(JavaPlugin plugin, CustomItemService itemService) {
    TelemetryService service = new TelemetryService(plugin, itemService);
    service.initialize();
    return service;
  }

  private TelemetryService(JavaPlugin plugin, CustomItemService itemService) {
    this.plugin = plugin;
    this.itemService = itemService;
    this.pluginPackagePrefix = plugin.getClass().getPackageName();
    for (Subsystem subsystem : Subsystem.values()) {
      subsystemFailures.put(subsystem, new LongAdder());
    }
    enabledInConfig = plugin.getConfig().getBoolean("metrics.faststats.enabled", true);
    errorReportingEnabled = plugin.getConfig().getBoolean("metrics.faststats.error-reporting",
        true);
    periodicIntervalSeconds = clamp(
        plugin.getConfig().getInt("metrics.faststats.periodic-interval-seconds",
            defaultPeriodicIntervalSeconds),
        minPeriodicIntervalSeconds,
        Integer.MAX_VALUE);
    int dedupWindowSeconds = clamp(
        plugin.getConfig().getInt("metrics.faststats.error-report-dedup-window-seconds",
            defaultErrorReportDedupWindowSeconds),
        minErrorReportDedupWindowSeconds,
        maxErrorReportDedupWindowSeconds);
    errorReportDedupWindowMs = dedupWindowSeconds <= 0 ? 0L : (long) dedupWindowSeconds * 1000L;
    recentEventBufferSize = clamp(
        plugin.getConfig().getInt("metrics.faststats.recent-event-buffer-size",
            defaultRecentEventBufferSize),
        minRecentEventBufferSize,
        maxRecentEventBufferSize);
    token = resolveToken();
  }

  /**
   * Marks whether ProtocolLib integration is active.
   *
   * @param enabled true when active
   */
  public void setProtocolLibEnabled(boolean enabled) {
    protocolLibEnabled.set(enabled);
    String code = enabled ? "protocol_lib_on" : "protocol_lib_off";
    recordEvent(Subsystem.STARTUP, "hook", enabled, code);
  }

  /**
   * Tracks amount of registered listener handlers.
   *
   * @param count handler count
   */
  public void setListenerHandlerCount(int count) {
    listenerHandlersRegistered.set(Math.max(0, count));
  }

  /**
   * Refreshes state snapshots that are sent as metrics.
   */
  public void refreshSnapshot() {
    if (!active.get()) {
      return;
    }
    captureSnapshot();
  }

  /**
   * Tracks one failed command execution.
   *
   * @param code short internal failure code
   */
  public void recordCommandFailure(String code) {
    if (!active.get()) {
      return;
    }
    commandExecutionFailed.increment();
    incrementSubsystemFailure(Subsystem.COMMAND);
    recordEvent(Subsystem.COMMAND, "command", false, code);
  }

  /**
   * Tracks one successful give operation.
   */
  public void recordGiveSuccess() {
    if (!active.get()) {
      return;
    }
    giveOperationsSuccess.increment();
    recordEvent(Subsystem.COMMAND, "give", true, "give_ok");
  }

  /**
   * Tracks one failed give operation.
   *
   * @param code short internal failure code
   */
  public void recordGiveFailure(String code) {
    if (!active.get()) {
      return;
    }
    giveOperationsFailed.increment();
    recordCommandFailure(code);
  }

  /**
   * Tracks one successful config reload.
   */
  public void recordConfigReloadSuccess() {
    if (!active.get()) {
      return;
    }
    configReloadSuccess.increment();
    recordEvent(Subsystem.RELOAD, "reload", true, "reload_ok");
    captureSnapshot();
  }

  /**
   * Tracks one failed config reload.
   *
   * @param code short internal failure code
   * @param throwable associated exception
   */
  public void recordConfigReloadFailure(String code, Throwable throwable) {
    if (!active.get()) {
      return;
    }
    configReloadFailed.increment();
    recordException(Subsystem.RELOAD, code, throwable);
  }

  /**
   * Tracks one startup-stage exception.
   *
   * @param code short internal failure code
   * @param throwable exception
   */
  public void recordStartupFailure(String code, Throwable throwable) {
    recordException(Subsystem.STARTUP, code, throwable);
  }

  /**
   * Tracks one shutdown-stage exception.
   *
   * @param code short internal failure code
   * @param throwable exception
   */
  public void recordShutdownFailure(String code, Throwable throwable) {
    recordException(Subsystem.SHUTDOWN, code, throwable);
  }

  /**
   * Tracks one invalid item parse/deserialize case.
   *
   * @param code short internal failure code
   */
  public void recordItemParseFailure(String code) {
    if (!active.get()) {
      return;
    }
    itemParseFailed.increment();
    incrementSubsystemFailure(Subsystem.ITEM_LOAD);
    recordEvent(Subsystem.ITEM_LOAD, "item_parse", false, code);
  }

  /**
   * Tracks one recipe parse/register failure.
   *
   * @param code short internal failure code
   */
  public void recordRecipeFailure(String code) {
    if (!active.get()) {
      return;
    }
    recipeParseFailed.increment();
    incrementSubsystemFailure(Subsystem.RECIPE_REGISTER);
    recordEvent(Subsystem.RECIPE_REGISTER, "recipe", false, code);
  }

  /**
   * Tracks one mechanic/cooldown execution failure.
   *
   * @param code short internal failure code
   */
  public void recordMechanicFailure(String code) {
    if (!active.get()) {
      return;
    }
    mechanicExecutionFailed.increment();
    incrementSubsystemFailure(Subsystem.MECHANIC);
    recordEvent(Subsystem.MECHANIC, "mechanic", false, code);
  }

  /**
   * Tracks one listener exception and forwards it to FastStats error tracking when enabled.
   *
   * @param handlerName listener handler method name
   * @param throwable exception
   */
  public void recordListenerException(String handlerName, Throwable throwable) {
    if (!active.get()) {
      return;
    }
    listenerExceptions.increment();
    recordException(Subsystem.LISTENER, "listener_" + sanitizeCode(handlerName), throwable);
  }

  /**
   * Records an exception for one logical plugin subsystem.
   *
   * @param subsystem logical subsystem
   * @param code short internal failure code
   * @param throwable exception
   */
  public void recordException(Subsystem subsystem, String code, Throwable throwable) {
    if (!active.get()) {
      return;
    }
    incrementSubsystemFailure(subsystem);
    updateLastErrorDetails(throwable);
    markErrorNow();
    recordEvent(subsystem, "exception", false, code);
    trackError(throwable);
    logRecentExceptionContext(subsystem, code);
    scheduleImmediateSubmit();
  }

  /**
   * Shuts down telemetry and performs a best-effort final flush.
   */
  public void shutdown() {
    if (!active.getAndSet(false)) {
      return;
    }
    recordEvent(Subsystem.SHUTDOWN, "shutdown", true, "shutdown_begin");
    if (snapshotTask != null) {
      snapshotTask.cancel();
      snapshotTask = null;
    }
    if (submitTask != null) {
      submitTask.cancel();
      submitTask = null;
    }
    try {
      if (metrics != null) {
        // Hard JVM or host crashes can skip onDisable; periodic submit reduces lost diagnostics.
        metrics.shutdown();
      }
    } catch (Throwable throwable) {
      warnTelemetryFailureOnce();
    } finally {
      metrics = null;
      submitCapableMetrics = null;
    }
  }

  private void initialize() {
    if (!enabledInConfig) {
      return;
    }
    if (token.isBlank()) {
      return;
    }
    try {
      BukkitMetrics.Factory factory = BukkitMetrics.factory()
          .token(token)
          .onFlush(this::onFlush);
      addMetrics(factory);
      if (errorReportingEnabled) {
        ErrorTracker tracker = ErrorTracker.contextAware();
        tracker.setContextErrorHandler((loader, throwable) -> onAutoTrackedError(throwable));
        factory.errorTracker(tracker);
      }
      metrics = factory.create(plugin);
      submitCapableMetrics = metrics instanceof SimpleMetrics simpleMetrics
          ? simpleMetrics
          : null;
      if (submitCapableMetrics == null) {
        manualSubmitSupported.set(false);
      }
      metrics.ready();
      active.set(true);
      captureSnapshot();
      startPeriodicTasks();
      recordEvent(Subsystem.STARTUP, "telemetry", true, "faststats_enabled");
    } catch (Throwable throwable) {
      active.set(false);
      metrics = null;
      submitCapableMetrics = null;
      warnTelemetryFailureOnce();
    }
  }

  private void addMetrics(BukkitMetrics.Factory factory) {
    factory.addMetric(Metric.number("custom_items_loaded", customItemsLoaded::get));
    factory.addMetric(Metric.number("custom_items_failed_load", customItemsFailedLoad::get));
    factory.addMetric(Metric.number("recipes_registered", recipesRegistered::sum));
    factory.addMetric(Metric.number("recipes_failed_register", recipesFailedRegister::sum));
    factory.addMetric(Metric.number("mechanics_registered", mechanicsRegistered::get));
    factory.addMetric(Metric.number("plugin_commands_registered", pluginCommandsRegistered::get));
    factory.addMetric(Metric.number("listener_handlers_registered",
        listenerHandlersRegistered::get));
    factory.addMetric(Metric.bool("hook_protocol_lib_enabled", protocolLibEnabled::get));
    factory.addMetric(Metric.bool("hook_placeholder_api_enabled", placeholderApiEnabled::get));

    factory.addMetric(Metric.number("give_operations_success", giveOperationsSuccess::sum));
    factory.addMetric(Metric.number("give_operations_failed", giveOperationsFailed::sum));
    factory.addMetric(Metric.number("command_execution_failed", commandExecutionFailed::sum));
    factory.addMetric(Metric.number("listener_exceptions", listenerExceptions::sum));
    factory.addMetric(Metric.number("config_reload_success", configReloadSuccess::sum));
    factory.addMetric(Metric.number("config_reload_failed", configReloadFailed::sum));
    factory.addMetric(Metric.number("item_parse_failed", itemParseFailed::sum));
    factory.addMetric(Metric.number("recipe_parse_failed", recipeParseFailed::sum));
    factory.addMetric(Metric.number("mechanic_execution_failed", mechanicExecutionFailed::sum));
    factory.addMetric(Metric.number("online_players", onlinePlayers::get));
    factory.addMetric(Metric.number("loaded_worlds", loadedWorlds::get));

    factory.addMetric(Metric.number("last_error_timestamp_ms", lastErrorTimestampMs::get));
    factory.addMetric(Metric.string("last_error_class", lastErrorClass::get));
    factory.addMetric(Metric.string("last_error_top_frame", lastErrorTopFrame::get));
    factory.addMetric(Metric.number("errors_last_five_minutes", this::errorsLastFiveMinutes));
    factory.addMetric(Metric.number("errors_last_thirty_minutes",
        this::errorsLastThirtyMinutes));
    factory.addMetric(Metric.number("faststats_errors_sent", faststatsErrorsSent::sum));
    factory.addMetric(Metric.number("faststats_errors_suppressed",
        faststatsErrorsSuppressed::sum));

    factory.addMetric(Metric.number("failures_startup",
        () -> subsystemFailureCount(Subsystem.STARTUP)));
    factory.addMetric(Metric.number("failures_config_load",
        () -> subsystemFailureCount(Subsystem.CONFIG_LOAD)));
    factory.addMetric(Metric.number("failures_item_load",
        () -> subsystemFailureCount(Subsystem.ITEM_LOAD)));
    factory.addMetric(Metric.number("failures_recipe_register",
        () -> subsystemFailureCount(Subsystem.RECIPE_REGISTER)));
    factory.addMetric(Metric.number("failures_command",
        () -> subsystemFailureCount(Subsystem.COMMAND)));
    factory.addMetric(Metric.number("failures_listener",
        () -> subsystemFailureCount(Subsystem.LISTENER)));
    factory.addMetric(Metric.number("failures_mechanic",
        () -> subsystemFailureCount(Subsystem.MECHANIC)));
    factory.addMetric(Metric.number("failures_reload",
        () -> subsystemFailureCount(Subsystem.RELOAD)));
    factory.addMetric(Metric.number("failures_shutdown",
        () -> subsystemFailureCount(Subsystem.SHUTDOWN)));

    factory.addMetric(Metric.number("recent_event_total", this::recentEventCount));
    factory.addMetric(Metric.number("recent_event_failure_total",
        this::recentFailureEventCount));
    factory.addMetric(Metric.stringArray("recent_event_snapshot",
        this::recentEventSnapshot));
  }

  private void startPeriodicTasks() {
    long ticks = (long) periodicIntervalSeconds * 20L;
    snapshotTask = Bukkit.getScheduler().runTaskTimer(plugin, this::captureSnapshot, ticks, ticks);
    submitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
        this::submitNowBestEffort, ticks, ticks);
  }

  private void captureSnapshot() {
    if (!active.get()) {
      return;
    }
    try {
      customItemsLoaded.set(itemService.loadedDefinitionCount());
      customItemsFailedLoad.set(itemService.failedDefinitionLoadCount());
      mechanicsRegistered.set(countMechanicItems(itemService.definitions()));
      pluginCommandsRegistered.set(commandCount());
      onlinePlayers.set(Bukkit.getOnlinePlayers().size());
      loadedWorlds.set(Bukkit.getWorlds().size());
      placeholderApiEnabled.set(Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"));
      recordEvent(Subsystem.STARTUP, "snapshot", true, "snapshot_ok");
    } catch (Throwable throwable) {
      recordException(Subsystem.STARTUP, "snapshot_failed", throwable);
    }
  }

  private void onFlush() {
    recordEvent(Subsystem.STARTUP, "flush", true, "flush_accepted");
  }

  private void onAutoTrackedError(Throwable throwable) {
    if (!active.get()) {
      return;
    }
    Subsystem subsystem = classifySubsystem(throwable);
    if (subsystem == Subsystem.LISTENER) {
      listenerExceptions.increment();
    }
    incrementSubsystemFailure(subsystem);
    updateLastErrorDetails(throwable);
    markErrorNow();
    recordEvent(subsystem, "uncaught_exception", false, topPluginFrameCode(throwable));
    logRecentExceptionContext(subsystem, "auto_tracked");
    scheduleImmediateSubmit();
  }

  private void trackError(Throwable throwable) {
    if (!errorReportingEnabled || metrics == null) {
      return;
    }
    if (!shouldTrackErrorNow(throwable)) {
      faststatsErrorsSuppressed.increment();
      return;
    }
    try {
      metrics.getErrorTracker().ifPresent(tracker -> {
        tracker.trackError(throwable);
        faststatsErrorsSent.increment();
      });
    } catch (Throwable ignored) {
      warnTelemetryFailureOnce();
    }
  }

  private boolean shouldTrackErrorNow(Throwable throwable) {
    if (errorReportDedupWindowMs <= 0L) {
      return true;
    }
    String signature = errorSignature(throwable);
    long now = System.currentTimeMillis();
    synchronized (errorReportLock) {
      pruneErrorReportStates(now);
      ErrorReportState state = errorReportStates.get(signature);
      if (state == null) {
        errorReportStates.put(signature, new ErrorReportState(now));
        return true;
      }
      state.lastSeenMs = now;
      if (now - state.lastSentMs >= errorReportDedupWindowMs) {
        state.lastSentMs = now;
        return true;
      }
      return false;
    }
  }

  private void pruneErrorReportStates(long nowMs) {
    long keepMs = Math.max(errorReportDedupWindowMs * 2L, fiveMinutesMs);
    long threshold = nowMs - keepMs;
    Iterator<Map.Entry<String, ErrorReportState>> iterator = errorReportStates.entrySet()
        .iterator();
    while (iterator.hasNext()) {
      ErrorReportState state = iterator.next().getValue();
      if (state.lastSeenMs < threshold) {
        iterator.remove();
      }
    }
  }

  private String errorSignature(Throwable throwable) {
    return sanitizeCode(throwable.getClass().getName()) + ":" + topPluginFrameCode(throwable);
  }

  private void submitNowBestEffort() {
    if (!active.get() || metrics == null || !manualSubmitSupported.get()) {
      return;
    }
    try {
      SimpleMetrics submitter = submitCapableMetrics;
      if (submitter == null) {
        manualSubmitSupported.set(false);
        return;
      }
      submitter.submit();
    } catch (Throwable throwable) {
      warnTelemetryFailureOnce();
    }
  }

  private void scheduleImmediateSubmit() {
    if (!active.get() || metrics == null || !manualSubmitSupported.get()) {
      return;
    }
    long now = System.currentTimeMillis();
    long previous = lastImmediateSubmitAttemptMs.get();
    if (now - previous < immediateSubmitCooldownMs) {
      return;
    }
    if (!lastImmediateSubmitAttemptMs.compareAndSet(previous, now)) {
      return;
    }
    Bukkit.getScheduler().runTaskAsynchronously(plugin, this::submitNowBestEffort);
  }

  private int countMechanicItems(Collection<CustomItemDefinition> definitions) {
    int count = 0;
    for (CustomItemDefinition definition : definitions) {
      if (containsMechanic(definition.attributes())) {
        count++;
      }
    }
    return count;
  }

  private boolean containsMechanic(Map<String, String> attributes) {
    for (String key : mechanicAttributes) {
      if (attributes.containsKey(key)) {
        return true;
      }
    }
    return false;
  }

  private int commandCount() {
    Map<String, Map<String, Object>> commands = plugin.getDescription().getCommands();
    return commands == null ? 0 : commands.size();
  }

  private void incrementSubsystemFailure(Subsystem subsystem) {
    LongAdder adder = subsystemFailures.get(subsystem);
    if (adder != null) {
      adder.increment();
    }
  }

  private long subsystemFailureCount(Subsystem subsystem) {
    LongAdder adder = subsystemFailures.get(subsystem);
    return adder == null ? 0L : adder.sum();
  }

  private void markErrorNow() {
    long now = System.currentTimeMillis();
    lastErrorTimestampMs.set(now);
    synchronized (errorTimelineLock) {
      errorTimeline.addLast(now);
      pruneErrorTimeline(now);
    }
  }

  private void updateLastErrorDetails(Throwable throwable) {
    lastErrorClass.set(sanitizeCode(throwable.getClass().getName()));
    lastErrorTopFrame.set(topPluginFrameCode(throwable));
  }

  private long errorsLastFiveMinutes() {
    return errorCountInWindow(fiveMinutesMs);
  }

  private long errorsLastThirtyMinutes() {
    return errorCountInWindow(thirtyMinutesMs);
  }

  private long errorCountInWindow(long windowMs) {
    long threshold = System.currentTimeMillis() - windowMs;
    synchronized (errorTimelineLock) {
      pruneErrorTimeline(System.currentTimeMillis());
      long count = 0L;
      for (Long timestamp : errorTimeline) {
        if (timestamp >= threshold) {
          count++;
        }
      }
      return count;
    }
  }

  private void pruneErrorTimeline(long nowMs) {
    long threshold = nowMs - thirtyMinutesMs;
    while (!errorTimeline.isEmpty()) {
      Long oldest = errorTimeline.peekFirst();
      if (oldest == null || oldest >= threshold) {
        return;
      }
      errorTimeline.pollFirst();
    }
  }

  private int recentEventCount() {
    synchronized (recentEventsLock) {
      return recentEvents.size();
    }
  }

  private int recentFailureEventCount() {
    synchronized (recentEventsLock) {
      int count = 0;
      for (DiagnosticEvent event : recentEvents) {
        if (!event.success()) {
          count++;
        }
      }
      return count;
    }
  }

  private String[] recentEventSnapshot() {
    synchronized (recentEventsLock) {
      int size = Math.min(recentEventMetricLimit, recentEvents.size());
      String[] snapshot = new String[size];
      Iterator<DiagnosticEvent> iterator = recentEvents.descendingIterator();
      int index = 0;
      while (iterator.hasNext() && index < size) {
        DiagnosticEvent event = iterator.next();
        snapshot[index] = event.timestampEpochSecond()
            + ":" + event.subsystemTag()
            + ":" + event.eventType()
            + ":" + (event.success() ? "ok" : "fail")
            + ":" + event.code();
        index++;
      }
      return snapshot;
    }
  }

  private void recordEvent(
      Subsystem subsystem,
      String eventType,
      boolean success,
      String code) {
    // Keep only internal tags/codes in the ring buffer; never store player or item user content.
    String safeType = sanitizeCode(eventType);
    String safeCode = sanitizeCode(code);
    DiagnosticEvent event = new DiagnosticEvent(
        Instant.now().getEpochSecond(),
        subsystem.tag(),
        safeType,
        success,
        safeCode);
    synchronized (recentEventsLock) {
      recentEvents.addLast(event);
      while (recentEvents.size() > recentEventBufferSize) {
        recentEvents.pollFirst();
      }
    }
  }

  private void logRecentExceptionContext(Subsystem subsystem, String code) {
    long now = System.currentTimeMillis();
    long last = lastExceptionContextLogMs.get();
    if (now - last < exceptionContextLogCooldownMs) {
      return;
    }
    if (!lastExceptionContextLogMs.compareAndSet(last, now)) {
      return;
    }
    String[] snapshot = recentEventSnapshot();
    if (snapshot.length == 0) {
      return;
    }
    StringBuilder builder = new StringBuilder("[Telemetry] ")
        .append(subsystem.tag())
        .append(" exception (")
        .append(sanitizeCode(code))
        .append("). Recent events: ");
    int max = Math.min(exceptionContextLogEventLimit, snapshot.length);
    for (int i = 0; i < max; i++) {
      if (i > 0) {
        builder.append(" -> ");
      }
      builder.append(snapshot[i]);
    }
    plugin.getLogger().warning(builder.toString());
  }

  private Subsystem classifySubsystem(Throwable throwable) {
    for (StackTraceElement element : throwable.getStackTrace()) {
      String className = element.getClassName();
      if (!className.startsWith(pluginPackagePrefix)) {
        continue;
      }
      if (className.contains(".listener.")) {
        return Subsystem.LISTENER;
      }
      if (className.contains(".command.")) {
        return Subsystem.COMMAND;
      }
      if (className.contains(".storage.") || className.contains("CustomItemService")) {
        return Subsystem.ITEM_LOAD;
      }
      if (className.contains("ConfigUpdater")) {
        return Subsystem.CONFIG_LOAD;
      }
      if (className.contains(".protocol.")) {
        return Subsystem.MECHANIC;
      }
    }
    return Subsystem.MECHANIC;
  }

  private String topPluginFrameCode(Throwable throwable) {
    for (StackTraceElement element : throwable.getStackTrace()) {
      String className = element.getClassName();
      if (!className.startsWith(pluginPackagePrefix)) {
        continue;
      }
      int packageIndex = className.lastIndexOf('.') + 1;
      String classPart = packageIndex <= 0 ? className : className.substring(packageIndex);
      return sanitizeCode(classPart + "_" + element.getMethodName());
    }
    return "unknown";
  }

  private String sanitizeCode(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    String normalized = value.toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9_]+", "_")
        .replaceAll("_+", "_");
    if (normalized.startsWith("_")) {
      normalized = normalized.substring(1);
    }
    if (normalized.endsWith("_")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    if (normalized.isBlank()) {
      return "unknown";
    }
    return normalized.length() <= 48 ? normalized : normalized.substring(0, 48);
  }

  private void warnTelemetryFailureOnce() {
    if (telemetryFailureWarned.compareAndSet(false, true)) {
      plugin.getLogger().warning("FastStats telemetry unavailable; continuing without telemetry.");
    }
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private String resolveToken() {
    String[] candidates = {
        System.getProperty("huds.faststats.token", "2bf1300a507d6b0fe051637f13b75a86"),
        System.getProperty("faststats.token", "2bf1300a507d6b0fe051637f13b75a86"),
        System.getenv("HUDS_FASTSTATS_TOKEN"),
        System.getenv("FASTSTATS_TOKEN")
    };
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate.trim();
      }
    }
    return "";
  }

  private record DiagnosticEvent(
      long timestampEpochSecond,
      String subsystemTag,
      String eventType,
      boolean success,
      String code) {
  }

  private static final class ErrorReportState {
    private long lastSentMs;
    private long lastSeenMs;

    private ErrorReportState(long nowMs) {
      lastSentMs = nowMs;
      lastSeenMs = nowMs;
    }
  }

  /**
   * Logical plugin subsystem tags used for failure aggregation.
   */
  public enum Subsystem {
    STARTUP("startup"),
    CONFIG_LOAD("config_load"),
    ITEM_LOAD("item_load"),
    RECIPE_REGISTER("recipe_register"),
    COMMAND("command"),
    LISTENER("listener"),
    MECHANIC("mechanic"),
    RELOAD("reload"),
    SHUTDOWN("shutdown");

    private final String tag;

    Subsystem(String tag) {
      this.tag = tag;
    }

    /**
     * Gets stable tag name used in telemetry payloads.
     *
     * @return subsystem tag
     */
    public String tag() {
      return tag;
    }
  }
}
