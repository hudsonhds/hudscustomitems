package com.hudscustomitems.customitems.protocol;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.hudscustomitems.customitems.service.CustomItemService;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Optional ProtocolLib integration for packet-level custom item effects.
 */
public final class ProtocolLibBridge {
  private final JavaPlugin plugin;
  private final CustomItemService itemService;
  private ProtocolManager protocolManager;
  private PacketAdapter swingPacketListener;

  /**
   * Creates a new bridge instance.
   *
   * @param plugin owner plugin
   * @param itemService item service
   */
  public ProtocolLibBridge(JavaPlugin plugin, CustomItemService itemService) {
    this.plugin = plugin;
    this.itemService = itemService;
  }

  /**
   * Starts ProtocolLib listeners if ProtocolLib is installed.
   *
   * @return true when integration is active
   */
  public boolean start() {
    Plugin protocolPlugin = Bukkit.getPluginManager().getPlugin("ProtocolLib");
    if (protocolPlugin == null || !protocolPlugin.isEnabled()) {
      return false;
    }
    protocolManager = ProtocolLibrary.getProtocolManager();
    registerSwingPacketParticles();
    return true;
  }

  /**
   * Stops integration and unregisters packet listeners.
   */
  public void stop() {
    if (protocolManager != null && swingPacketListener != null) {
      protocolManager.removePacketListener(swingPacketListener);
      swingPacketListener = null;
    }
  }

  private void registerSwingPacketParticles() {
    swingPacketListener = new PacketAdapter(
        plugin,
        ListenerPriority.NORMAL,
        PacketType.Play.Client.ARM_ANIMATION) {
      @Override
      public void onPacketReceiving(PacketEvent event) {
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        Optional<String> id = itemService.customItemId(held);
        if (id.isEmpty()) {
          return;
        }
        Map<String, String> attributes = itemService.resolveAttributes(held);
        String particleName = attributes.get("swing_particles");
        if (particleName == null || particleName.isBlank()) {
          return;
        }
        try {
          Particle particle = Particle.valueOf(particleName.toUpperCase(Locale.ROOT));
          Bukkit.getScheduler().runTask(plugin, () -> player.getWorld().spawnParticle(
              particle,
              player.getEyeLocation().add(player.getLocation().getDirection().multiply(1.1)),
              10,
              0.18,
              0.18,
              0.18,
              0.03));
        } catch (IllegalArgumentException ex) {
          // Invalid particle id set by configuration.
        }
      }
    };
    protocolManager.addPacketListener(swingPacketListener);
  }
}
