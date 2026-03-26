package com.hudscustomitems.customitems;

import com.hudscustomitems.customitems.command.CustomItemsCommand;
import com.hudscustomitems.customitems.listener.CustomItemListener;
import com.hudscustomitems.customitems.protocol.ProtocolLibBridge;
import com.hudscustomitems.customitems.service.ConfigUpdater;
import com.hudscustomitems.customitems.service.CustomItemService;
import com.hudscustomitems.customitems.service.SafeListenerRegistrar;
import com.hudscustomitems.customitems.service.SellContainerService;
import com.hudscustomitems.customitems.service.TelemetryService;
import io.papermc.lib.PaperLib;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin bootstrap for HUDS custom items.
 */
public final class CustomItemsPlugin extends JavaPlugin {
  private ConfigUpdater configUpdater;
  private CustomItemService itemService;
  private SellContainerService sellContainerService;
  private CustomItemListener customItemListener;
  private ProtocolLibBridge protocolLibBridge;
  private TelemetryService telemetryService;

  @Override
  public void onEnable() {
    try {
      PaperLib.suggestPaper(this);
      saveDefaultConfig();
      configUpdater = new ConfigUpdater(this);
      ConfigUpdater.UpdateSummary summary = configUpdater.updateAndReload();
      saveResource("items.yml", false);
      logConfigSummary("Startup config update", summary);

      itemService = new CustomItemService(this);
      sellContainerService = new SellContainerService(this);
      customItemListener = new CustomItemListener(this, itemService, sellContainerService);
      protocolLibBridge = new ProtocolLibBridge(this, itemService, customItemListener);
      telemetryService = TelemetryService.create(this, itemService);

      registerCommand();
      registerListeners();
      getServer().getScheduler().runTaskTimer(this, () -> {
        try {
          customItemListener.runSecondTick();
        } catch (Throwable throwable) {
          telemetryService.recordListenerException("run_second_tick", throwable);
        }
      }, 20L, 20L);
      boolean protocolActive = protocolLibBridge.start();
      telemetryService.setProtocolLibEnabled(protocolActive);
      getLogger().info("ProtocolLib integration: " + (protocolActive ? "enabled" : "not installed"));
    } catch (Throwable throwable) {
      if (telemetryService != null) {
        telemetryService.recordStartupFailure("startup_failed", throwable);
      }
      getLogger().log(Level.SEVERE, "Plugin startup failed.", throwable);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public void onDisable() {
    if (protocolLibBridge != null) {
      try {
        protocolLibBridge.stop();
      } catch (Throwable throwable) {
        if (telemetryService != null) {
          telemetryService.recordShutdownFailure("protocol_stop_failed", throwable);
        }
        getLogger().log(Level.SEVERE, "ProtocolLib bridge shutdown failed.", throwable);
      }
    }
    if (telemetryService != null) {
      try {
        telemetryService.shutdown();
      } catch (Throwable throwable) {
        getLogger().log(Level.SEVERE, "Telemetry shutdown failed.", throwable);
      }
    }
  }

  private void registerCommand() {
    PluginCommand command = getCommand("customitems");
    if (command == null) {
      getLogger().severe("Could not register /customitems command from plugin.yml");
      if (telemetryService != null) {
        telemetryService.recordCommandFailure("command_registration_missing");
      }
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    CustomItemsCommand handler = new CustomItemsCommand(this, itemService, telemetryService);
    command.setExecutor(handler);
    command.setTabCompleter(handler);
  }

  private void registerListeners() {
    int handlers = SafeListenerRegistrar.register(
        getServer().getPluginManager(),
        customItemListener,
        this,
        telemetryService);
    telemetryService.setListenerHandlerCount(handlers);
  }

  /**
   * Reloads config with default merge and reports summary.
   *
   * @return update summary
   */
  public ConfigUpdater.UpdateSummary reloadConfigWithUpdate() {
    ConfigUpdater.UpdateSummary summary = configUpdater.updateAndReload();
    logConfigSummary("Reload config update", summary);
    return summary;
  }

  private void logConfigSummary(String prefix, ConfigUpdater.UpdateSummary summary) {
    if (!summary.changed()) {
      getLogger().info(prefix + ": no missing config keys.");
      return;
    }
    StringBuilder message = new StringBuilder(prefix)
        .append(": added ")
        .append(summary.addedKeys())
        .append(" missing key(s).");
    if (summary.backupPath() != null) {
      message.append(" Backup: ").append(summary.backupPath());
    } else if (summary.largeChange()) {
      message.append(" Backup requested but could not be created.");
    }
    getLogger().info(message.toString());
  }
}
