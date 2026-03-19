package com.hudscustomitems.customitems.protocol;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.hudscustomitems.customitems.listener.CustomItemListener;
import com.hudscustomitems.customitems.service.CustomItemService;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
  private final CustomItemListener customItemListener;
  private ProtocolManager protocolManager;
  private PacketAdapter swingPacketListener;
  private PacketAdapter emptyHandDashListener;

  /**
   * Creates a new bridge instance.
   *
   * @param plugin owner plugin
   * @param itemService item service
   */
  public ProtocolLibBridge(
      JavaPlugin plugin,
      CustomItemService itemService,
      CustomItemListener customItemListener) {
    this.plugin = plugin;
    this.itemService = itemService;
    this.customItemListener = customItemListener;
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
    registerEmptyHandDashFallback();
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
    if (protocolManager != null && emptyHandDashListener != null) {
      protocolManager.removePacketListener(emptyHandDashListener);
      emptyHandDashListener = null;
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
        Optional<ConfiguredParticle> configured = parseConfiguredParticle(particleName);
        if (configured.isEmpty()) {
          return;
        }
        ConfiguredParticle particle = configured.get();
        Bukkit.getScheduler().runTask(plugin, () -> {
          if (particle.data() == null) {
            player.getWorld().spawnParticle(
                particle.particle(),
                player.getEyeLocation().add(player.getLocation().getDirection().multiply(1.1)),
                10,
                0.18,
                0.18,
                0.18,
                0.03);
            return;
          }
          player.getWorld().spawnParticle(
              particle.particle(),
              player.getEyeLocation().add(player.getLocation().getDirection().multiply(1.1)),
              10,
              0.18,
              0.18,
              0.18,
              0.03,
              particle.data());
        });
      }
    };
    protocolManager.addPacketListener(swingPacketListener);
  }

  private void registerEmptyHandDashFallback() {
    emptyHandDashListener = new PacketAdapter(
        plugin,
        ListenerPriority.NORMAL,
        PacketType.Play.Client.USE_ITEM) {
      @Override
      public void onPacketReceiving(PacketEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> customItemListener.triggerSneakDashFallback(player));
      }
    };
    protocolManager.addPacketListener(emptyHandDashListener);
  }

  private Optional<ConfiguredParticle> parseConfiguredParticle(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    try {
      return Optional.of(new ConfiguredParticle(Particle.valueOf(normalized), null));
    } catch (IllegalArgumentException ex) {
      String materialName = normalized.startsWith("BLOCK:")
          ? normalized.substring("BLOCK:".length())
          : normalized;
      Material material = safeMaterial(materialName);
      if (material != null && material.isBlock()) {
        return Optional.of(new ConfiguredParticle(Particle.BLOCK, material.createBlockData()));
      }
      return Optional.empty();
    }
  }

  private Material safeMaterial(String value) {
    try {
      return Material.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private record ConfiguredParticle(
      Particle particle,
      Object data) {
  }
}
