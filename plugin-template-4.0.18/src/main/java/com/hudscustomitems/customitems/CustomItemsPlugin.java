package com.hudscustomitems.customitems;

import com.hudscustomitems.customitems.command.CustomItemsCommand;
import com.hudscustomitems.customitems.listener.CustomItemListener;
import com.hudscustomitems.customitems.protocol.ProtocolLibBridge;
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
  private CustomItemService itemService;
  private SellContainerService sellContainerService;
  private CustomItemListener customItemListener;
  private ProtocolLibBridge protocolLibBridge;

  @Override
  public void onEnable() {
    PaperLib.suggestPaper(this);
    saveDefaultConfig();
    saveResource("items.yml", false);

    itemService = new CustomItemService(this);
    sellContainerService = new SellContainerService(this);
    customItemListener = new CustomItemListener(this, itemService, sellContainerService);
    protocolLibBridge = new ProtocolLibBridge(this, itemService);

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
}
