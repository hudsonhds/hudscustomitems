package com.hudscustomitems.customitems;

import com.hudscustomitems.customitems.command.CustomItemsCommand;
import com.hudscustomitems.customitems.listener.CustomItemListener;
import com.hudscustomitems.customitems.protocol.ProtocolLibBridge;
import com.hudscustomitems.customitems.service.ConfigUpdater;
import com.hudscustomitems.customitems.service.CustomItemService;
import com.hudscustomitems.customitems.service.SellContainerService;
import io.papermc.lib.PaperLib;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
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

  @Override
  public void onEnable() {
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

    registerCommand();
    registerListeners();
    getServer().getScheduler().runTaskTimer(this, customItemListener::runSecondTick, 20L, 20L);
    boolean protocolActive = protocolLibBridge.start();
    getLogger().info("ProtocolLib integration: " + (protocolActive ? "enabled" : "not installed"));
  }

  @Override
  public void onDisable() {
    if (protocolLibBridge != null) {
      protocolLibBridge.stop();
    }
  }

  private void registerCommand() {
    PluginCommand command = getCommand("customitems");
    if (command == null) {
      getLogger().severe("Could not register /customitems command from plugin.yml");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    CustomItemsCommand handler = new CustomItemsCommand(this, itemService);
    command.setExecutor(handler);
    command.setTabCompleter(handler);
  }

  private void registerListeners() {
    PluginManager manager = getServer().getPluginManager();
    manager.registerEvents(customItemListener, this);
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
