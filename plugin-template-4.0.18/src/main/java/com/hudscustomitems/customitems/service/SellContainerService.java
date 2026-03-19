package com.hudscustomitems.customitems.service;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Sells full container inventories using hooked shop providers and Vault deposits.
 */
public final class SellContainerService {
  private final JavaPlugin plugin;
  private final VaultEconomyBridge vaultEconomyBridge;
  private final DecimalFormat moneyFormat;
  private final List<SellPriceProvider> priceProviders;
  private final Set<String> loggedWarnings;

  /**
   * Creates a new sell container service.
   *
   * @param plugin owner plugin
   */
  public SellContainerService(JavaPlugin plugin) {
    this.plugin = plugin;
    this.vaultEconomyBridge = new VaultEconomyBridge(plugin);
    this.moneyFormat = new DecimalFormat("#,##0.00");
    this.priceProviders = createProviderChain();
    this.loggedWarnings = new HashSet<>();
  }

  /**
   * Sells everything in a container inventory if sell prices are available.
   *
   * @param player seller
   * @param clickedBlock clicked block
   * @return detailed outcome for user messaging
   */
  public SellOutcome sellContainer(Player player, Block clickedBlock) {
    BlockState state = clickedBlock.getState();
    if (!(state instanceof InventoryHolder holder)) {
      return SellOutcome.notHandled();
    }
    Inventory inventory = holder.getInventory();
    if (inventory == null) {
      return SellOutcome.handledFailure("That container has no inventory.");
    }
    if (!vaultEconomyBridge.available()) {
      return SellOutcome.handledFailure("Vault economy is not available.");
    }

    List<SoldStack> sold = new ArrayList<>();
    List<ItemStack> unsoldDrops = new ArrayList<>();
    int totalSoldItems = 0;
    double totalEarnings = 0.0;
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      ItemStack stack = inventory.getItem(slot);
      if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) {
        continue;
      }
      Optional<PriceQuote> quote = quoteSellValue(player, stack);
      if (quote.isEmpty()) {
        unsoldDrops.add(stack.clone());
        continue;
      }
      double unitPrice = quote.get().unitPrice();
      if (unitPrice <= 0.0) {
        unsoldDrops.add(stack.clone());
        continue;
      }
      int amount = stack.getAmount();
      double stackValue = unitPrice * amount;
      sold.add(new SoldStack(slot, amount, stackValue, quote.get().providerName()));
      totalSoldItems += amount;
      totalEarnings += stackValue;
    }

    if (sold.isEmpty()) {
      return SellOutcome.handledFailure("No sellable items were found in this container.");
    }
    if (!vaultEconomyBridge.deposit(player, totalEarnings)) {
      return SellOutcome.handledFailure("Sale failed because Vault could not deposit funds.");
    }
    for (SoldStack soldStack : sold) {
      inventory.setItem(soldStack.slot(), null);
    }
    if (state instanceof Container container) {
      container.update(true, false);
    }
    Map<String, Double> providerBreakdown = new LinkedHashMap<>();
    for (SoldStack soldStack : sold) {
      providerBreakdown.merge(soldStack.providerName(), soldStack.stackValue(), Double::sum);
    }
    String moneyText = vaultEconomyBridge.format(totalEarnings)
        .orElse("$" + moneyFormat.format(totalEarnings));
    StringBuilder summary = new StringBuilder();
    summary.append("Sold ")
        .append(totalSoldItems)
        .append(" items for ")
        .append(moneyText)
        .append('.');
    if (!providerBreakdown.isEmpty()) {
      summary.append(" Source: ");
      List<String> parts = new ArrayList<>();
      for (Map.Entry<String, Double> entry : providerBreakdown.entrySet()) {
        parts.add(entry.getKey() + "=" + moneyFormat.format(entry.getValue()));
      }
      summary.append(String.join(", ", parts));
    }
    return SellOutcome.handledSuccess(totalSoldItems, totalEarnings, providerBreakdown,
        unsoldDrops, summary.toString());
  }

  private Optional<PriceQuote> quoteSellValue(Player player, ItemStack stack) {
    ItemStack pricedStack = stack.clone();
    pricedStack.setAmount(1);
    List<SellPriceProvider> providers = configuredPriceProviders();
    if (providers.isEmpty()) {
      return Optional.empty();
    }
    boolean forcedMode = isForcedProviderMode();
    for (SellPriceProvider provider : providers) {
      if (!provider.available()) {
        if (forcedMode) {
          warnOnce("sell-provider-unavailable:" + provider.name(),
              "Forced sell provider '" + provider.name()
                  + "' is not available (plugin/API not detected).");
        }
        continue;
      }
      OptionalDouble price = provider.sellPrice(player, pricedStack);
      if (price.isPresent() && price.getAsDouble() > 0.0) {
        return Optional.of(new PriceQuote(provider.name(), price.getAsDouble()));
      }
    }
    if (forcedMode) {
      SellPriceProvider provider = providers.get(0);
      String diagnostics = providerDiagnostics(provider);
      warnOnce("sell-provider-no-prices:" + provider.name(),
          "Forced sell provider '" + provider.name()
              + "' returned no sell prices. " + diagnostics);
    }
    return Optional.empty();
  }

  private List<SellPriceProvider> createProviderChain() {
    List<SellPriceProvider> chain = new ArrayList<>();
    chain.add(new ReflectionProvider(
        plugin,
        "EconomyShopGUI",
        List.of(
            "EconomyShopGUI",
            "EconomyShopGUI Premium",
            "EconomyShopGUI-Premium",
            "EconomyShopGUIPremium"),
        List.of(
            "me.gypopo.economyshopgui.api.EconomyShopGUIHook",
            "me.gypopo.economyshopgui.api.EconomyShopGUIAPI"),
        List.of("getItemSellPrice", "getSellPrice", "getItemStackSellPrice")));
    chain.add(new ReflectionProvider(
        plugin,
        "ShopGUIPlus",
        List.of("ShopGUIPlus", "ShopGUI+"),
        List.of("net.brcdev.shopgui.ShopGuiPlusApi"),
        List.of("getItemStackPriceSell", "getSellPrice", "getPrice")));
    chain.add(new ReflectionProvider(
        plugin,
        "QuickShop",
        List.of("QuickShop"),
        List.of("com.ghostchu.quickshop.api.QuickShopAPI", "com.ghostchu.quickshop.api.QuickShop"),
        List.of("getSellPrice", "getPrice", "getWorth")));
    chain.add(new ReflectionProvider(
        plugin,
        "ChestShop",
        List.of("ChestShop"),
        List.of("com.Acrobot.ChestShop.API", "com.Acrobot.ChestShop.ChestShop"),
        List.of("getSellPrice", "getPrice", "getWorth")));
    chain.add(new ReflectionProvider(
        plugin,
        "Shopkeepers",
        List.of("Shopkeepers"),
        List.of("com.nisovin.shopkeepers.api.ShopkeepersAPI"),
        List.of("getSellPrice", "getPrice", "getWorth")));
    chain.add(new ReflectionProvider(
        plugin,
        "BossShopPro",
        List.of("BossShopPro"),
        List.of("org.black_ixx.bossshop.BossShop"),
        List.of("getSellPrice", "getPrice", "getWorth")));
    chain.add(new ReflectionProvider(
        plugin,
        "UltimateShop",
        List.of("UltimateShop"),
        List.of("com.songoda.ultimateshop.UltimateShop"),
        List.of("getSellPrice", "getPrice", "getWorth")));
    chain.add(new ReflectionProvider(
        plugin,
        "DynamicShop",
        List.of("DynamicShop"),
        List.of("me.sat7.dynamicshop.DynamicShop"),
        List.of("getSellPrice", "getPrice", "getWorth")));
    chain.add(new EssentialsWorthProvider(plugin, "EssentialsX", List.of("Essentials", "EssentialsX")));
    chain.add(new ReflectionProvider(
        plugin,
        "GUIShop",
        List.of("GUIShop"),
        List.of("org.popcraft.guishop.GuiShop", "com.epicnicity322.guishop.GuiShop"),
        List.of("getSellPrice", "getPrice", "getWorth")));
    chain.add(new ConfigWorthProvider(plugin, "Vault"));
    return chain;
  }

  private List<SellPriceProvider> configuredPriceProviders() {
    String forcedProvider = forcedProviderName();
    if (forcedProvider != null
        && !forcedProvider.isBlank()
        && !forcedProvider.equalsIgnoreCase("auto")) {
      List<SellPriceProvider> single = new ArrayList<>();
      for (SellPriceProvider provider : priceProviders) {
        if (matchesProviderName(provider.name(), forcedProvider.trim())) {
          single.add(provider);
          return single;
        }
      }
      warnOnce("sell-provider-unknown:" + forcedProvider.toLowerCase(Locale.ROOT),
          "Configured sell provider '" + forcedProvider + "' was not found. "
              + "Valid providers: " + availableProviderNames());
      return List.of();
    }
    List<String> configuredOrder = plugin.getConfig().getStringList("sell-container.provider-order");
    if (configuredOrder.isEmpty()) {
      return priceProviders;
    }
    Map<String, SellPriceProvider> byName = new LinkedHashMap<>();
    for (SellPriceProvider provider : priceProviders) {
      byName.put(provider.name().toLowerCase(Locale.ROOT), provider);
    }
    List<SellPriceProvider> ordered = new ArrayList<>();
    for (String providerName : configuredOrder) {
      SellPriceProvider matched = byName.remove(providerName.toLowerCase(Locale.ROOT).trim());
      if (matched != null) {
        ordered.add(matched);
      }
    }
    ordered.addAll(byName.values());
    return ordered;
  }

  private String forcedProviderName() {
    return plugin.getConfig().getString("sell-container.provider", "auto");
  }

  private boolean isForcedProviderMode() {
    String forcedProvider = forcedProviderName();
    return forcedProvider != null
        && !forcedProvider.isBlank()
        && !forcedProvider.equalsIgnoreCase("auto");
  }

  private boolean matchesProviderName(String providerName, String configuredName) {
    String left = normalizeToken(providerName);
    String right = normalizeToken(configuredName);
    return left.equals(right);
  }

  private String normalizeToken(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  private String availableProviderNames() {
    List<String> names = new ArrayList<>();
    for (SellPriceProvider provider : priceProviders) {
      names.add(provider.name());
    }
    return String.join(", ", names);
  }

  private void warnOnce(String key, String message) {
    if (loggedWarnings.add(key)) {
      plugin.getLogger().warning("[SellContainer] " + message);
    }
  }

  private String providerDiagnostics(SellPriceProvider provider) {
    if (provider instanceof ReflectionProvider reflectionProvider) {
      return reflectionProvider.diagnostics();
    }
    return "Check provider configuration and API availability.";
  }

  private record SoldStack(int slot, int amount, double stackValue, String providerName) {
  }

  private record PriceQuote(String providerName, double unitPrice) {
  }

  /**
   * Immutable sell action result.
   *
   * @param handled true when block was a container and sale action was attempted
   * @param success true when sale completed
   * @param soldItems item amount sold
   * @param totalValue total money value
   * @param providerBreakdown totals by provider
   * @param unsoldDrops unsold item stacks that should be dropped
   * @param message player-facing summary
   */
  public record SellOutcome(
      boolean handled,
      boolean success,
      int soldItems,
      double totalValue,
      Map<String, Double> providerBreakdown,
      List<ItemStack> unsoldDrops,
      String message) {
    static SellOutcome notHandled() {
      return new SellOutcome(false, false, 0, 0.0, Map.of(), List.of(), "");
    }

    static SellOutcome handledFailure(String message) {
      return new SellOutcome(true, false, 0, 0.0, Map.of(), List.of(), message);
    }

    static SellOutcome handledSuccess(
        int soldItems,
        double totalValue,
        Map<String, Double> providerBreakdown,
        List<ItemStack> unsoldDrops,
        String message) {
      List<ItemStack> immutableDrops = unsoldDrops.stream()
          .map(ItemStack::clone)
          .collect(Collectors.toList());
      return new SellOutcome(true, true, soldItems, totalValue, Map.copyOf(providerBreakdown),
          List.copyOf(immutableDrops), message);
    }
  }

  private interface SellPriceProvider {
    boolean available();

    String name();

    OptionalDouble sellPrice(Player player, ItemStack stack);
  }

  private static final class ReflectionProvider implements SellPriceProvider {
    private final JavaPlugin plugin;
    private final String providerName;
    private final List<String> pluginNames;
    private final List<String> classCandidates;
    private final List<String> methodCandidates;

    ReflectionProvider(
        JavaPlugin plugin,
        String providerName,
        List<String> pluginNames,
        List<String> classCandidates,
        List<String> methodCandidates) {
      this.plugin = plugin;
      this.providerName = providerName;
      this.pluginNames = pluginNames;
      this.classCandidates = classCandidates;
      this.methodCandidates = methodCandidates;
    }

    @Override
    public boolean available() {
      if (findPlugin().isPresent()) {
        return true;
      }
      for (String className : classCandidates) {
        if (classForName(className).isPresent()) {
          return true;
        }
      }
      return false;
    }

    @Override
    public String name() {
      return providerName;
    }

    @Override
    public OptionalDouble sellPrice(Player player, ItemStack stack) {
      Optional<Plugin> pluginInstance = findPlugin();
      for (String className : classCandidates) {
        Optional<Class<?>> found = classForName(className);
        if (found.isEmpty()) {
          continue;
        }
        OptionalDouble staticResult = invokePriceMethod(found.get(), null, player, stack);
        if (staticResult.isPresent()) {
          return staticResult;
        }
      }
      if (pluginInstance.isEmpty()) {
        return OptionalDouble.empty();
      }
      OptionalDouble pluginResult = invokePriceMethod(
          pluginInstance.get().getClass(),
          pluginInstance.get(),
          player,
          stack);
      return pluginResult.isPresent() ? pluginResult : OptionalDouble.empty();
    }

    private OptionalDouble invokePriceMethod(
        Class<?> targetClass,
        Object target,
        Player player,
        ItemStack stack) {
      for (Method method : targetClass.getMethods()) {
        String methodName = method.getName().toLowerCase(Locale.ROOT);
        boolean candidateName = methodCandidates
            .stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .anyMatch(methodName::equals);
        if (!candidateName) {
          candidateName = methodName.contains("sell")
              && (methodName.endsWith("price")
                  || methodName.endsWith("value")
                  || methodName.endsWith("worth"));
        }
        if (!candidateName) {
          continue;
        }
        Optional<Object> result = invokeWithSupportedArguments(method, target, player, stack);
        if (result.isEmpty()) {
          continue;
        }
        OptionalDouble extracted = extractPrice(result.get());
        if (extracted.isPresent() && extracted.getAsDouble() > 0.0) {
          return extracted;
        }
      }
      return OptionalDouble.empty();
    }

    private Optional<Object> invokeWithSupportedArguments(
        Method method,
        Object target,
        Player player,
        ItemStack stack) {
      List<Object[]> options = buildArgumentOptions(method.getParameterTypes(), player, stack);
      if (options.isEmpty()) {
        return Optional.empty();
      }
      try {
        for (Object[] arguments : options) {
          try {
            Object result = method.invoke(target, arguments);
            if (result != null) {
              return Optional.of(result);
            }
          } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            // Try next candidate argument combination.
          }
        }
      } catch (SecurityException ignored) {
        return Optional.empty();
      }
      return Optional.empty();
    }

    private List<Object[]> buildArgumentOptions(
        Class<?>[] parameterTypes,
        Player player,
        ItemStack stack) {
      List<List<Object>> optionsPerParameter = new ArrayList<>();
      for (Class<?> parameter : parameterTypes) {
        List<Object> options = argumentOptions(parameter, player, stack);
        if (options.isEmpty()) {
          return List.of();
        }
        optionsPerParameter.add(options);
      }
      List<Object[]> combinations = new ArrayList<>();
      buildArgumentCombinations(optionsPerParameter, 0, new Object[parameterTypes.length],
          combinations);
      return combinations;
    }

    private void buildArgumentCombinations(
        List<List<Object>> optionsPerParameter,
        int index,
        Object[] current,
        List<Object[]> output) {
      if (index >= optionsPerParameter.size()) {
        output.add(current.clone());
        return;
      }
      for (Object option : optionsPerParameter.get(index)) {
        current[index] = option;
        buildArgumentCombinations(optionsPerParameter, index + 1, current, output);
      }
    }

    private List<Object> argumentOptions(Class<?> parameter, Player player, ItemStack stack) {
      if (parameter.isAssignableFrom(player.getClass())
          || OfflinePlayer.class.isAssignableFrom(parameter)) {
        return List.of(player);
      }
      if (parameter.isAssignableFrom(stack.getClass())) {
        return List.of(stack);
      }
      if (Material.class.isAssignableFrom(parameter)) {
        return List.of(stack.getType());
      }
      if (parameter == int.class || parameter == Integer.class) {
        return List.of(stack.getAmount(), 1);
      }
      if (parameter == double.class || parameter == Double.class) {
        return List.of((double) stack.getAmount(), 1.0d);
      }
      if (parameter == float.class || parameter == Float.class) {
        return List.of((float) stack.getAmount(), 1.0f);
      }
      if (parameter == long.class || parameter == Long.class) {
        return List.of((long) stack.getAmount(), 1L);
      }
      if (parameter == boolean.class || parameter == Boolean.class) {
        return List.of(Boolean.TRUE, Boolean.FALSE);
      }
      if (parameter.isEnum()) {
        List<Object> enumOptions = enumArguments(parameter);
        return enumOptions.isEmpty() ? List.of() : enumOptions;
      }
      if (parameter.isPrimitive()) {
        return List.of();
      }
      return List.of();
    }

    private List<Object> enumArguments(Class<?> enumType) {
      Object[] values = enumType.getEnumConstants();
      if (values == null || values.length == 0) {
        return List.of();
      }
      List<Object> prioritized = new ArrayList<>();
      for (Object value : values) {
        String name = value.toString().toLowerCase(Locale.ROOT);
        if (name.contains("sell")) {
          prioritized.add(value);
        }
      }
      for (Object value : values) {
        if (!prioritized.contains(value)) {
          prioritized.add(value);
        }
      }
      return prioritized;
    }

    private Optional<Plugin> findPlugin() {
      for (String pluginName : pluginNames) {
        Plugin found = Bukkit.getPluginManager().getPlugin(pluginName);
        if (found != null && found.isEnabled()) {
          return Optional.of(found);
        }
        String wanted = normalizePluginName(pluginName);
        for (Plugin candidate : Bukkit.getPluginManager().getPlugins()) {
          if (!candidate.isEnabled()) {
            continue;
          }
          String candidateName = normalizePluginName(candidate.getName());
          if (candidateName.equals(wanted)
              || candidateName.contains(wanted)
              || wanted.contains(candidateName)) {
            return Optional.of(candidate);
          }
        }
      }
      return Optional.empty();
    }

    private String normalizePluginName(String value) {
      return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private Optional<Class<?>> classForName(String className) {
      try {
        return Optional.of(Class.forName(className));
      } catch (ClassNotFoundException ex) {
        return Optional.empty();
      }
    }

    String diagnostics() {
      String pluginSummary = findPlugin()
          .map(found -> "plugin=" + found.getName())
          .orElse("plugin=not-detected");
      List<String> foundClasses = classCandidates.stream()
          .filter(className -> classForName(className).isPresent())
          .collect(Collectors.toList());
      if (foundClasses.isEmpty()) {
        return pluginSummary + ", api-classes=none";
      }
      List<String> methodSummaries = new ArrayList<>();
      for (String className : foundClasses) {
        Optional<Class<?>> resolved = classForName(className);
        if (resolved.isEmpty()) {
          continue;
        }
        List<String> methods = new ArrayList<>();
        for (Method method : resolved.get().getMethods()) {
          String methodName = method.getName().toLowerCase(Locale.ROOT);
          boolean candidate = methodCandidates.stream()
              .map(name -> name.toLowerCase(Locale.ROOT))
              .anyMatch(methodName::equals)
              || (methodName.contains("sell")
                  && (methodName.endsWith("price")
                      || methodName.endsWith("value")
                      || methodName.endsWith("worth")));
          if (candidate) {
            String signature = java.util.Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(Collectors.joining(","));
            methods.add(method.getName() + "(" + signature + ")");
          }
        }
        if (!methods.isEmpty()) {
          methodSummaries.add(className + ":" + String.join(",", methods));
        }
      }
      if (methodSummaries.isEmpty()) {
        return pluginSummary + ", api-classes=" + String.join(",", foundClasses)
            + ", candidate-methods=none";
      }
      return pluginSummary + ", candidates=" + String.join(" | ", methodSummaries);
    }
  }

  private static final class EssentialsWorthProvider implements SellPriceProvider {
    private final JavaPlugin plugin;
    private final String providerName;
    private final List<String> pluginNames;
    private final Map<String, Double> worthValues;
    private long lastLoadMs;
    private long lastModified;

    EssentialsWorthProvider(JavaPlugin plugin, String providerName, List<String> pluginNames) {
      this.plugin = plugin;
      this.providerName = providerName;
      this.pluginNames = pluginNames;
      this.worthValues = new HashMap<>();
      this.lastLoadMs = 0L;
      this.lastModified = 0L;
    }

    @Override
    public boolean available() {
      return findEssentialsDataFolder().isPresent();
    }

    @Override
    public String name() {
      return providerName;
    }

    @Override
    public OptionalDouble sellPrice(Player player, ItemStack stack) {
      Optional<File> worthFile = findEssentialsWorthFile();
      if (worthFile.isEmpty()) {
        return OptionalDouble.empty();
      }
      reloadIfNeeded(worthFile.get());
      Double value = worthValues.get(normalizeMaterialKey(stack.getType().name()));
      return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    private Optional<File> findEssentialsWorthFile() {
      Optional<File> folder = findEssentialsDataFolder();
      if (folder.isEmpty()) {
        return Optional.empty();
      }
      File worthFile = new File(folder.get(), "worth.yml");
      return worthFile.exists() ? Optional.of(worthFile) : Optional.empty();
    }

    private Optional<File> findEssentialsDataFolder() {
      for (String pluginName : pluginNames) {
        Plugin found = Bukkit.getPluginManager().getPlugin(pluginName);
        if (found != null && found.isEnabled()) {
          return Optional.of(found.getDataFolder());
        }
      }
      return Optional.empty();
    }

    private void reloadIfNeeded(File worthFile) {
      long now = System.currentTimeMillis();
      if (now - lastLoadMs < 5000L && worthFile.lastModified() == lastModified) {
        return;
      }
      worthValues.clear();
      YamlConfiguration configuration = YamlConfiguration.loadConfiguration(worthFile);
      ConfigurationSection section = configuration.getConfigurationSection("worth");
      ConfigurationSection source = section == null ? configuration : section;
      for (String key : source.getKeys(true)) {
        Object value = source.get(key);
        if (value instanceof Number number) {
          worthValues.put(normalizeMaterialKey(key), number.doubleValue());
        }
      }
      lastModified = worthFile.lastModified();
      lastLoadMs = now;
      plugin.getLogger().fine("Loaded " + worthValues.size() + " Essentials worth values.");
    }
  }

  private static final class ConfigWorthProvider implements SellPriceProvider {
    private final JavaPlugin plugin;
    private final String providerName;

    ConfigWorthProvider(JavaPlugin plugin, String providerName) {
      this.plugin = plugin;
      this.providerName = providerName;
    }

    @Override
    public boolean available() {
      return true;
    }

    @Override
    public String name() {
      return providerName;
    }

    @Override
    public OptionalDouble sellPrice(Player player, ItemStack stack) {
      String key = "sell-container.fallback-worth." + stack.getType().name();
      if (!plugin.getConfig().contains(key)) {
        return OptionalDouble.empty();
      }
      return OptionalDouble.of(plugin.getConfig().getDouble(key));
    }
  }

  private static final class VaultEconomyBridge {
    private final Object economyProvider;
    private final Method depositMethod;
    private final Method formatMethod;

    VaultEconomyBridge(JavaPlugin plugin) {
      Object provider = null;
      Method deposit = null;
      Method format = null;
      try {
        Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
        RegisteredServiceProvider<?> registration =
            Bukkit.getServicesManager().getRegistration(economyClass);
        if (registration != null) {
          provider = registration.getProvider();
          if (provider != null) {
            deposit = findMethod(provider.getClass(), "depositPlayer", OfflinePlayer.class,
                double.class);
            if (deposit == null) {
              deposit = findMethod(provider.getClass(), "depositPlayer", String.class, double.class);
            }
            format = findMethod(provider.getClass(), "format", double.class);
          }
        }
      } catch (ClassNotFoundException ex) {
        plugin.getLogger().fine("Vault API not found at runtime.");
      }
      this.economyProvider = provider;
      this.depositMethod = deposit;
      this.formatMethod = format;
    }

    boolean available() {
      return economyProvider != null && depositMethod != null;
    }

    boolean deposit(Player player, double amount) {
      if (!available()) {
        return false;
      }
      try {
        Object response;
        if (depositMethod.getParameterTypes()[0] == OfflinePlayer.class) {
          response = depositMethod.invoke(economyProvider, player, amount);
        } else {
          response = depositMethod.invoke(economyProvider, player.getName(), amount);
        }
        return transactionSucceeded(response);
      } catch (ReflectiveOperationException ex) {
        return false;
      }
    }

    Optional<String> format(double amount) {
      if (economyProvider == null || formatMethod == null) {
        return Optional.empty();
      }
      try {
        Object formatted = formatMethod.invoke(economyProvider, amount);
        return formatted == null ? Optional.empty() : Optional.of(formatted.toString());
      } catch (ReflectiveOperationException ex) {
        return Optional.empty();
      }
    }

    private boolean transactionSucceeded(Object response) {
      if (response == null) {
        return false;
      }
      if (response instanceof Boolean bool) {
        return bool;
      }
      OptionalDouble direct = extractPrice(response);
      if (direct.isPresent()) {
        return direct.getAsDouble() >= 0.0;
      }
      try {
        Method method = response.getClass().getMethod("transactionSuccess");
        Object value = method.invoke(response);
        if (value instanceof Boolean bool) {
          return bool;
        }
      } catch (ReflectiveOperationException ignored) {
        // Ignore.
      }
      try {
        Field field = response.getClass().getField("transactionSuccess");
        Object value = field.get(response);
        if (value instanceof Boolean bool) {
          return bool;
        }
      } catch (ReflectiveOperationException ignored) {
        // Ignore.
      }
      return false;
    }

    private Method findMethod(Class<?> type, String name, Class<?>... params) {
      try {
        return type.getMethod(name, params);
      } catch (NoSuchMethodException ex) {
        return null;
      }
    }
  }

  private static OptionalDouble extractPrice(Object source) {
    if (source == null) {
      return OptionalDouble.empty();
    }
    if (source instanceof Number number) {
      return OptionalDouble.of(number.doubleValue());
    }
    if (source instanceof Optional<?> optional) {
      return optional.isPresent() ? extractPrice(optional.get()) : OptionalDouble.empty();
    }
    if (source instanceof OptionalDouble optionalDouble) {
      return optionalDouble;
    }
    if (source instanceof Map<?, ?> map) {
      for (Object value : map.values()) {
        OptionalDouble extracted = extractPrice(value);
        if (extracted.isPresent()) {
          return extracted;
        }
      }
      return OptionalDouble.empty();
    }
    try {
      Method method = source.getClass().getMethod("getPrice");
      return extractPrice(method.invoke(source));
    } catch (ReflectiveOperationException ignored) {
      // Ignore.
    }
    try {
      Method method = source.getClass().getMethod("getSellPrice");
      return extractPrice(method.invoke(source));
    } catch (ReflectiveOperationException ignored) {
      // Ignore.
    }
    try {
      Method method = source.getClass().getMethod("getValue");
      return extractPrice(method.invoke(source));
    } catch (ReflectiveOperationException ignored) {
      // Ignore.
    }
    try {
      Field field = source.getClass().getField("price");
      return extractPrice(field.get(source));
    } catch (ReflectiveOperationException ignored) {
      // Ignore.
    }
    try {
      Field field = source.getClass().getField("sellPrice");
      return extractPrice(field.get(source));
    } catch (ReflectiveOperationException ignored) {
      // Ignore.
    }
    try {
      Field field = source.getClass().getField("value");
      return extractPrice(field.get(source));
    } catch (ReflectiveOperationException ignored) {
      // Ignore.
    }
    return OptionalDouble.empty();
  }

  private static String normalizeMaterialKey(String key) {
    String normalized = key.toLowerCase(Locale.ROOT).trim();
    if (normalized.startsWith("minecraft:")) {
      normalized = normalized.substring("minecraft:".length());
    }
    if (normalized.contains(":")) {
      normalized = normalized.substring(0, normalized.indexOf(':'));
    }
    return normalized.toUpperCase(Locale.ROOT);
  }
}
