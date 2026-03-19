package com.hudscustomitems.customitems.listener;

import com.hudscustomitems.customitems.service.CustomItemService;
import com.hudscustomitems.customitems.service.SellContainerService;
import com.hudscustomitems.customitems.service.SellContainerService.SellOutcome;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Animals;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Handles gameplay behavior tied to custom item attributes.
 */
public final class CustomItemListener implements Listener {
  private static final int POTION_DURATION_TICKS = 15 * 20;
  private static final long POTION_REFRESH_INTERVAL_MS = 5_000L;
  private final JavaPlugin plugin;
  private final CustomItemService itemService;
  private final SellContainerService sellContainerService;
  private final LegacyComponentSerializer serializer;
  private final Set<String> breakGuard;
  private final Set<UUID> combatEffectGuard;
  private final Map<UUID, Integer> comboHits;
  private final Map<UUID, Long> movementThrottle;
  private final Map<UUID, Long> dashTriggerTimes;
  private final Map<UUID, Long> potionRefreshTimes;
  private final Map<UUID, Set<PotionEffectType>> managedPotionEffects;

  /**
   * Creates listener with references needed for custom item runtime logic.
   *
   * @param plugin owner plugin
   * @param itemService item service
   * @param sellContainerService sell container service
   */
  public CustomItemListener(
      JavaPlugin plugin,
      CustomItemService itemService,
      SellContainerService sellContainerService) {
    this.plugin = plugin;
    this.itemService = itemService;
    this.sellContainerService = sellContainerService;
    this.serializer = LegacyComponentSerializer.legacyAmpersand();
    this.breakGuard = new HashSet<>();
    this.combatEffectGuard = new HashSet<>();
    this.comboHits = new HashMap<>();
    this.movementThrottle = new HashMap<>();
    this.dashTriggerTimes = new HashMap<>();
    this.potionRefreshTimes = new HashMap<>();
    this.managedPotionEffects = new HashMap<>();
  }

  /**
   * Runs once-per-second effects such as buffs, particles and durability/energy regen.
   */
  public void runSecondTick() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      itemService.synchronizeInventory(player);
      applyPotionBuffs(player, resolvePotionAttributes(player));
      updateDoubleJumpState(player, resolveMobilityAttributes(player));
      ItemStack held = player.getInventory().getItemInMainHand();
      if (itemService.customItemId(held).isEmpty()) {
        continue;
      }
      Map<String, String> attributes = itemService.resolveAttributes(held);
      applyTrailParticles(player, attributes);
      applyBlockHighlighting(player, attributes);
      applyMobDetection(player, attributes);
      itemService.tickRegen(player);
      animateLoreIfNeeded(held, attributes);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockBreak(BlockBreakEvent event) {
    Player player = event.getPlayer();
    ItemStack held = player.getInventory().getItemInMainHand();
    if (itemService.customItemId(held).isEmpty()) {
      return;
    }
    Map<String, String> attributes = itemService.resolveAttributes(held);
    if (!itemService.canUse(player, held, null, true)) {
      event.setCancelled(true);
      return;
    }
    if (isUnbreakableBlock(event.getBlock().getType())) {
      event.setCancelled(true);
      player.sendMessage(color("&cThat block cannot be broken by custom item abilities."));
      return;
    }
    if (!passesBlockLists(event.getBlock(), attributes)) {
      event.setCancelled(true);
      player.sendMessage(color("&cThis block is not allowed for this item."));
      return;
    }
    if (!itemService.canTriggerAction(player, held, "block_break", true)) {
      event.setCancelled(true);
      return;
    }
    if (itemService.boolValue(attributes, "sell_container")) {
      SellOutcome outcome = sellContainerService.sellContainer(player, event.getBlock());
      if (outcome.handled()) {
        if (outcome.success()) {
          player.sendMessage(color("&a" + outcome.message()));
          clearContainerContents(event.getBlock());
          dropUnsoldStacks(event.getBlock(), outcome.unsoldDrops());
          event.setDropItems(false);
        } else if (!outcome.message().isBlank()) {
          player.sendMessage(color("&e" + outcome.message()));
        }
      }
    }
    boolean replantMain = itemService.boolValue(attributes, "auto_replant")
        && isHarvestableCrop(event.getBlock());
    Material mainCropType = event.getBlock().getType();
    String locationKey = locationKey(event.getBlock().getLocation());
    if (breakGuard.contains(locationKey)) {
      return;
    }
    if (itemService.boolValue(attributes, "no_block_drops")) {
      event.setDropItems(false);
    }
    spawnConfiguredParticle(
        player,
        attributes.get("mining_particles"),
        event.getBlock().getLocation().add(0.5, 0.6, 0.5),
        18,
        0.35,
        0.35,
        0.35,
        0.02);
    Set<Block> extra = collectExtraBlocks(event.getBlock(), player, attributes);
    if (!extra.isEmpty()) {
      for (Block block : extra) {
        if (block.equals(event.getBlock())) {
          continue;
        }
        if (!passesBlockLists(block, attributes)) {
          continue;
        }
        boolean replantExtra = itemService.boolValue(attributes, "auto_replant")
            && isHarvestableCrop(block);
        Material cropType = block.getType();
        String key = locationKey(block.getLocation());
        breakGuard.add(key);
        breakExtraBlock(block, player, held, attributes);
        breakGuard.remove(key);
        if (replantExtra) {
          scheduleCropReplant(block, cropType);
        }
      }
    }
    if (replantMain) {
      scheduleCropReplant(event.getBlock(), mainCropType);
    }
    itemService.consumeUse(player, held);
  }

  private void breakExtraBlock(
      Block block,
      Player player,
      ItemStack held,
      Map<String, String> attributes) {
    List<ItemStack> drops = new ArrayList<>(block.getDrops(held, player));
    block.setType(Material.AIR, false);
    if (itemService.boolValue(attributes, "no_block_drops")) {
      return;
    }
    boolean autoSmelt = itemService.boolValue(attributes, "auto_smelt");
    double multiplier = itemService.doubleValue(attributes, "block_drop_multiplier").orElse(1.0);
    multiplier *= itemService.doubleValue(attributes, "fortune_mode").orElse(1.0);
    Location location = block.getLocation().add(0.5, 0.5, 0.5);
    for (ItemStack rawDrop : drops) {
      ItemStack baseDrop = autoSmelt ? smeltResult(rawDrop).orElse(rawDrop.clone()) : rawDrop.clone();
      block.getWorld().dropItemNaturally(location, baseDrop);
      if (multiplier > 1.0) {
        int extraAmount = Math.max(0, (int) Math.floor(baseDrop.getAmount() * (multiplier - 1.0)));
        if (extraAmount > 0) {
          ItemStack extraDrop = baseDrop.clone();
          extraDrop.setAmount(extraAmount);
          block.getWorld().dropItemNaturally(location, extraDrop);
        }
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockDropItems(BlockDropItemEvent event) {
    Player player = event.getPlayer();
    ItemStack held = player.getInventory().getItemInMainHand();
    if (itemService.customItemId(held).isEmpty()) {
      return;
    }
    Map<String, String> attributes = itemService.resolveAttributes(held);
    if (itemService.boolValue(attributes, "no_block_drops")) {
      event.getItems().forEach(Entity::remove);
      event.getItems().clear();
      return;
    }
    boolean autoSmelt = itemService.boolValue(attributes, "auto_smelt");
    double multiplier = itemService.doubleValue(attributes, "block_drop_multiplier").orElse(1.0);
    multiplier *= itemService.doubleValue(attributes, "fortune_mode").orElse(1.0);
    List<ItemStack> extras = new ArrayList<>();
    for (Item itemEntity : event.getItems()) {
      ItemStack stack = itemEntity.getItemStack();
      if (autoSmelt) {
        stack = smeltResult(stack).orElse(stack);
      }
      itemEntity.setItemStack(stack);
      if (multiplier > 1.0) {
        int extraAmount = Math.max(0, (int) Math.floor(stack.getAmount() * (multiplier - 1.0)));
        if (extraAmount > 0) {
          ItemStack copied = stack.clone();
          copied.setAmount(extraAmount);
          extras.add(copied);
        }
      }
    }
    Location location = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
    for (ItemStack extraDrop : extras) {
      event.getBlock().getWorld().dropItemNaturally(location, extraDrop);
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent event) {
    Action action = event.getAction();
    if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
      return;
    }
    Player player = event.getPlayer();
    ItemStack held = player.getInventory().getItemInMainHand();
    if (held.getType() == Material.AIR
        && player.isSneaking()
        && (event.getHand() == EquipmentSlot.HAND || event.getHand() == EquipmentSlot.OFF_HAND)
        && triggerSneakDash(player)) {
      event.setCancelled(true);
      return;
    }
    if (event.getHand() != EquipmentSlot.HAND) {
      return;
    }
    if (itemService.customItemId(held).isEmpty()) {
      return;
    }
    Map<String, String> attributes = itemService.resolveAttributes(held);
    if (!itemService.canUse(player, held, null, true)) {
      event.setCancelled(true);
      return;
    }
    if (!itemService.canTriggerAction(player, held, "right_click", false)) {
      return;
    }
    if (event.getClickedBlock() != null) {
      if (itemService.boolValue(attributes, "sell_container_on_click")) {
        SellOutcome outcome = sellContainerService.sellContainer(player, event.getClickedBlock());
        if (outcome.handled()) {
          if (outcome.success()) {
            player.sendMessage(color("&a" + outcome.message()));
            replaceContainerWithUnsold(event.getClickedBlock(), outcome.unsoldDrops());
            itemService.consumeUse(player, held);
          } else if (!outcome.message().isBlank()) {
            player.sendMessage(color("&e" + outcome.message()));
          }
          event.setCancelled(true);
          return;
        }
      }
      applyTillRadius(player, event.getClickedBlock(), attributes);
      applyPlacementRadius(player, event.getClickedBlock(), event.getBlockFace(), held, attributes);
      applyBlockReplace(player, event.getClickedBlock(), attributes);
    }
    useRightClickAbilities(player, held, attributes);
    playUseSound(player, attributes);
    itemService.consumeUse(player, held);
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onSneakDashInteract(PlayerInteractEvent event) {
    Action action = event.getAction();
    if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
      return;
    }
    if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) {
      return;
    }
    Player player = event.getPlayer();
    ItemStack held = player.getInventory().getItemInMainHand();
    if (held.getType() != Material.AIR || !player.isSneaking()) {
      return;
    }
    if (triggerSneakDash(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onSneak(PlayerToggleSneakEvent event) {
    if (!event.isSneaking()) {
      return;
    }
    Player player = event.getPlayer();
    ItemStack held = player.getInventory().getItemInMainHand();
    if (itemService.customItemId(held).isEmpty()) {
      return;
    }
    Map<String, String> attributes = itemService.resolveAttributes(held);
    if (!itemService.canUse(player, held, null, false)) {
      return;
    }
    String shiftAbility = attributes.get("shift_ability");
    if (shiftAbility == null || shiftAbility.isBlank()) {
      return;
    }
    if (!itemService.canTriggerAction(player, held, "shift_ability", false)) {
      return;
    }
    triggerNamedAbility(player, held, shiftAbility, attributes);
    itemService.consumeUse(player, held);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onDamage(EntityDamageByEntityEvent event) {
    Player attacker = resolveAttacker(event.getDamager());
    if (attacker == null) {
      return;
    }
    if (combatEffectGuard.contains(attacker.getUniqueId())) {
      return;
    }
    ItemStack held = attacker.getInventory().getItemInMainHand();
    if (itemService.customItemId(held).isEmpty()) {
      return;
    }
    if (!itemService.canUse(attacker, held, event.getEntity(), true)) {
      event.setCancelled(true);
      return;
    }
    if (!itemService.canTriggerAction(attacker, held, "attack", true)) {
      event.setCancelled(true);
      return;
    }
    Map<String, String> attributes = itemService.resolveAttributes(held);
    applyCombatAttributes(attacker, event, attributes);
    playSwingParticles(attacker, attributes);
    playHitSound(attacker, attributes);
    itemService.consumeUse(attacker, held);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onMove(PlayerMoveEvent event) {
    Player player = event.getPlayer();
    Map<String, String> mobilityAttributes = resolveMobilityAttributes(player);
    applyGlide(player, mobilityAttributes);
    updateDoubleJumpState(player, mobilityAttributes);
    ItemStack held = player.getInventory().getItemInMainHand();
    if (itemService.customItemId(held).isEmpty()) {
      return;
    }
    Map<String, String> attributes = itemService.resolveAttributes(held);
    if (!isMovementTickDue(player)) {
      return;
    }
    applyMagnetModes(player, attributes);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onToggleFlight(PlayerToggleFlightEvent event) {
    Player player = event.getPlayer();
    if (player.getAllowFlight() && !player.isFlying()) {
      Optional<ItemWithAttributes> jumpSource = findDoubleJumpSource(player);
      if (jumpSource.isEmpty()) {
        return;
      }
      ItemWithAttributes source = jumpSource.get();
      if (!itemService.canUse(player, source.stack(), null, false)) {
        return;
      }
      int jumpPercent = itemService.intValue(source.attributes(), "double_jump").orElse(0);
      if (jumpPercent <= 0) {
        return;
      }
      event.setCancelled(true);
      player.setFlying(false);
      player.setAllowFlight(false);
      double jumpMultiplier = jumpPercent / 100.0;
      Vector jump = player.getLocation().getDirection().multiply(0.7 * jumpMultiplier)
          .setY(0.75 * jumpMultiplier);
      player.setVelocity(player.getVelocity().add(jump));
      player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0, 0.1, 0), 14, 0.3,
          0.2, 0.3, 0.01);
      itemService.consumeUse(player, source.stack());
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onDrop(PlayerDropItemEvent event) {
    ItemStack dropped = event.getItemDrop().getItemStack();
    if (itemService.customItemId(dropped).isEmpty()) {
      return;
    }
    Map<String, String> attributes = itemService.resolveAttributes(dropped);
    if (itemService.boolValue(attributes, "soulbound")) {
      event.setCancelled(true);
      event.getPlayer().sendMessage(color("&cSoulbound items cannot be dropped."));
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onItemConsume(PlayerItemConsumeEvent event) {
    ItemStack consumed = event.getItem();
    if (itemService.customItemId(consumed).isEmpty()) {
      return;
    }
    Map<String, String> attributes = itemService.resolveAttributes(consumed);
    if (!isPreventedByDefault(attributes, "prevent_eating")) {
      return;
    }
    event.setCancelled(true);
    event.getPlayer().sendMessage(color("&cThis custom item cannot be eaten."));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityFeed(PlayerInteractEntityEvent event) {
    ItemStack used = event.getPlayer().getInventory().getItem(event.getHand());
    if (itemService.customItemId(used).isEmpty()) {
      return;
    }
    if (!(event.getRightClicked() instanceof Animals)
        && !(event.getRightClicked() instanceof AbstractHorse)) {
      return;
    }
    Map<String, String> attributes = itemService.resolveAttributes(used);
    if (!isPreventedByDefault(attributes, "prevent_feeding")) {
      return;
    }
    event.setCancelled(true);
    event.getPlayer().sendMessage(color("&cThis custom item cannot be fed to animals."));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockPlace(BlockPlaceEvent event) {
    ItemStack placed = event.getItemInHand();
    if (itemService.customItemId(placed).isEmpty()) {
      return;
    }
    Map<String, String> attributes = itemService.resolveAttributes(placed);
    if (!isPreventedByDefault(attributes, "prevent_placement")) {
      return;
    }
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onPrepareEnchant(PrepareItemEnchantEvent event) {
    if (!isPluginManagedItem(event.getItem())) {
      return;
    }
    event.setCancelled(true);
    if (event.getOffers() != null) {
      for (int index = 0; index < event.getOffers().length; index++) {
        event.getOffers()[index] = null;
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEnchantItem(EnchantItemEvent event) {
    if (!isPluginManagedItem(event.getItem())) {
      return;
    }
    event.setCancelled(true);
    event.getEnchantsToAdd().clear();
    event.getEnchanter().sendMessage(color("&cCustom items are unmodifiable."));
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onPrepareAnvil(PrepareAnvilEvent event) {
    if (inventoryHasPluginItem(event.getInventory(), 0, 1)) {
      event.setResult(null);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onPrepareGrindstone(PrepareGrindstoneEvent event) {
    if (inventoryHasPluginItem(event.getInventory(), 0, 1)) {
      event.setResult(null);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onPrepareSmithing(PrepareSmithingEvent event) {
    if (inventoryHasPluginItem(event.getInventory(), 0, 1, 2)) {
      event.setResult(null);
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onInventoryClick(InventoryClickEvent event) {
    if (event.getWhoClicked() instanceof Player player) {
      Bukkit.getScheduler().runTask(plugin, () -> itemService.synchronizeInventory(player));
    }
    if (isProtectedModificationClick(event)) {
      event.setCancelled(true);
      if (event.getWhoClicked() instanceof Player player) {
        player.sendMessage(color("&cCustom items are unmodifiable."));
      }
      return;
    }
    ItemStack current = event.getCurrentItem();
    if (current == null || itemService.customItemId(current).isEmpty()) {
      return;
    }
    Map<String, String> attributes = itemService.resolveAttributes(current);
    if (!itemService.boolValue(attributes, "soulbound")) {
      return;
    }
    if (event.getClickedInventory() != null
        && event.getWhoClicked() instanceof Player player
        && event.getClickedInventory() != player.getInventory()) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void onDeath(PlayerDeathEvent event) {
    List<ItemStack> toKeep = new ArrayList<>();
    for (ItemStack stack : event.getDrops()) {
      if (itemService.customItemId(stack).isPresent()) {
        Map<String, String> attributes = itemService.resolveAttributes(stack);
        if (itemService.boolValue(attributes, "soulbound")) {
          toKeep.add(stack);
        }
      }
    }
    if (!toKeep.isEmpty()) {
      event.getDrops().removeAll(toKeep);
      event.getItemsToKeep().addAll(toKeep);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent event) {
    Bukkit.getScheduler().runTask(plugin, () -> itemService.synchronizeInventory(event.getPlayer()));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    dashTriggerTimes.remove(playerId);
    potionRefreshTimes.remove(playerId);
    managedPotionEffects.remove(playerId);
  }

  private void useRightClickAbilities(Player player, ItemStack held, Map<String, String> attributes) {
    itemService.doubleValue(attributes, "teleport_on_right_click")
        .ifPresent(distance -> teleportForward(player, distance));
    itemService.doubleValue(attributes, "dash_ability").ifPresent(strength -> applyDash(player, strength));
    if (itemService.boolValue(attributes, "projectile_launch")
        || attributes.containsKey("projectile_launch")) {
      launchConfiguredProjectile(player, attributes.get("projectile_launch"));
    }
    itemService.doubleValue(attributes, "area_ability").ifPresent(radius -> applyAreaAbility(player,
        radius));
    itemService.doubleValue(attributes, "gravity_pull").ifPresent(radius -> pullNearbyEntities(
        player, radius, 0.55));
    itemService.doubleValue(attributes, "explosion_ability").ifPresent(power -> player.getWorld()
        .createExplosion(player.getLocation(), power.floatValue(), false, false, player));
    itemService.intValue(attributes, "time_slow_ability").ifPresent(seconds -> applySlowNearby(
        player, seconds));
    String summon = attributes.get("summon_entity");
    if (summon != null && !summon.isBlank()) {
      spawnEntityNearPlayer(player, summon);
    }
    String namedAbility = attributes.get("right_click_ability");
    if (namedAbility != null && !namedAbility.isBlank()) {
      triggerNamedAbility(player, held, namedAbility, attributes);
    }
  }

  private void applyCombatAttributes(
      Player attacker,
      EntityDamageByEntityEvent event,
      Map<String, String> attributes) {
    double damage = event.getDamage();
    double attackDamageBonus = itemService.doubleValue(attributes, "attack_damage").orElse(0.0);
    if (!(event.getDamager() instanceof Player)) {
      damage += attackDamageBonus;
    }
    if (event.getEntity() instanceof Player) {
      damage += itemService.doubleValue(attributes, "bonus_damage_vs_players").orElse(0.0);
    } else {
      damage += itemService.doubleValue(attributes, "bonus_damage_vs_mobs").orElse(0.0);
    }
    double armorPenetration = itemService.doubleValue(attributes, "armor_penetration").orElse(0.0);
    if (armorPenetration > 0.0) {
      damage += damage * armorPenetration;
    }
    if (rollCritical(attributes) || isVanillaCritical(attacker)) {
      double critMulti = itemService.doubleValue(attributes, "critical_damage_multiplier")
          .orElse(1.5);
      damage *= critMulti;
      attacker.getWorld().spawnParticle(Particle.CRIT, event.getEntity().getLocation().add(0, 1, 0),
          16, 0.2, 0.3, 0.2, 0.0);
    }
    event.setDamage(damage);
    double finalDamage = damage;
    if (event.getEntity() instanceof LivingEntity target) {
      itemService.doubleValue(attributes, "knockback_strength")
          .ifPresent(strength -> {
            Vector direction = target.getLocation().toVector().subtract(attacker.getLocation()
                    .toVector())
                .normalize();
            target.setVelocity(target.getVelocity().add(direction.multiply(strength)));
          });
      itemService.doubleValue(attributes, "life_steal").ifPresent(amount -> {
        org.bukkit.attribute.AttributeInstance maxHealth = attacker.getAttribute(
            org.bukkit.attribute.Attribute.MAX_HEALTH);
        double cap = maxHealth == null ? 20.0 : maxHealth.getValue();
        attacker.setHealth(Math.min(cap, attacker.getHealth() + finalDamage * amount));
      });
      itemService.intValue(attributes, "burn_target").ifPresent(seconds -> target.setFireTicks(
          seconds * 20));
      itemService.intValue(attributes, "freeze_target").ifPresent(seconds -> {
        int freezeTicks = Math.max(target.getFreezeTicks(), seconds * 140);
        target.setFreezeTicks(freezeTicks);
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, seconds * 20, 1, true,
            true, true));
      });
      itemService.intValue(attributes, "poison_target")
          .ifPresent(seconds -> target.addPotionEffect(new PotionEffect(PotionEffectType.POISON,
              seconds * 20, 0, true, true, true)));
      if (itemService.boolValue(attributes, "shield_breaker") && target instanceof Player player) {
        player.setCooldown(Material.SHIELD, 80);
      }
      itemService.doubleValue(attributes, "sweeping_radius")
          .ifPresent(radius -> sweepDamage(attacker, target, finalDamage * 0.5, radius));
      itemService.doubleValue(attributes, "multi_target_strike")
          .ifPresent(radius -> sweepDamage(attacker, target, finalDamage * 0.75, radius));
      itemService.intValue(attributes, "chain_lightning")
          .ifPresent(hops -> chainLightning(attacker, target, hops, finalDamage * 0.4));
      itemService.doubleValue(attributes, "explosive_hit")
          .ifPresent(power -> target.getWorld().createExplosion(target.getLocation(),
              power.floatValue(), false, false, attacker));
      itemService.doubleValue(attributes, "pull_effect")
          .ifPresent(radius -> pullNearbyEntities(attacker, radius, 0.4));
      itemService.doubleValue(attributes, "push_effect")
          .ifPresent(radius -> pushNearbyEntities(attacker, radius, 0.8));
    }
    int hits = comboHits.getOrDefault(attacker.getUniqueId(), 0) + 1;
    comboHits.put(attacker.getUniqueId(), hits);
    int comboNeeded = itemService.intValue(attributes, "combo_system").orElse(0);
    if (comboNeeded > 0 && hits >= comboNeeded) {
      comboHits.put(attacker.getUniqueId(), 0);
      double area = itemService.doubleValue(attributes, "area_ability").orElse(3.0);
      applyAreaAbility(attacker, area);
    }
  }

  private void applyPotionBuffs(Player player, Map<String, String> attributes) {
    UUID playerId = player.getUniqueId();
    long now = System.currentTimeMillis();
    boolean shouldRefresh = now - potionRefreshTimes.getOrDefault(playerId, 0L)
        >= POTION_REFRESH_INTERVAL_MS;
    if (shouldRefresh) {
      potionRefreshTimes.put(playerId, now);
    }
    Set<PotionEffectType> desiredEffects = new HashSet<>();
    applyOptionalEffect(player, attributes, "speed_bonus", PotionEffectType.SPEED, desiredEffects,
        shouldRefresh);
    applyOptionalEffect(player, attributes, "jump_boost", PotionEffectType.JUMP_BOOST, desiredEffects,
        shouldRefresh);
    applyOptionalEffect(player, attributes, "haste", PotionEffectType.HASTE, desiredEffects,
        shouldRefresh);
    applyOptionalEffect(player, attributes, "strength_boost", PotionEffectType.STRENGTH, desiredEffects,
        shouldRefresh);
    applyOptionalEffect(player, attributes, "regeneration", PotionEffectType.REGENERATION, desiredEffects,
        shouldRefresh);
    if (itemService.boolValue(attributes, "night_vision")) {
      applyManagedPotionEffect(player, PotionEffectType.NIGHT_VISION, 0, desiredEffects, shouldRefresh);
    }
    if (itemService.boolValue(attributes, "water_breathing")) {
      applyManagedPotionEffect(player, PotionEffectType.WATER_BREATHING, 0, desiredEffects,
          shouldRefresh);
    }
    if (itemService.boolValue(attributes, "fire_resistance")) {
      applyManagedPotionEffect(player, PotionEffectType.FIRE_RESISTANCE, 0, desiredEffects, shouldRefresh);
    }
    itemService.doubleValue(attributes, "health_bonus").ifPresent(healthBonus -> {
      int amplifier = Math.max(0, (int) Math.floor(healthBonus / 4.0));
      applyManagedPotionEffect(player, PotionEffectType.HEALTH_BOOST, amplifier, desiredEffects,
          shouldRefresh);
    });
    itemService.doubleValue(attributes, "absorption_hearts").ifPresent(extra -> {
      int amplifier = Math.max(0, (int) Math.floor(extra / 4.0));
      applyManagedPotionEffect(player, PotionEffectType.ABSORPTION, amplifier, desiredEffects,
          shouldRefresh);
    });
    removeStaleManagedPotionEffects(player, desiredEffects);
  }

  private boolean triggerSneakDash(Player player) {
    long now = System.currentTimeMillis();
    long lastTrigger = dashTriggerTimes.getOrDefault(player.getUniqueId(), 0L);
    if (now - lastTrigger < 200L) {
      return false;
    }
    Optional<ItemWithAttributes> dashSource = findDashAbilitySource(player);
    if (dashSource.isEmpty()) {
      return false;
    }
    ItemWithAttributes source = dashSource.get();
    if (!itemService.canUse(player, source.stack(), null, false)) {
      return false;
    }
    if (!itemService.canTriggerAction(player, source.stack(), "right_click", false)) {
      return false;
    }
    dashTriggerTimes.put(player.getUniqueId(), now);
    itemService.doubleValue(source.attributes(), "dash_ability").ifPresent(strength -> {
      applyDash(player, strength);
      playUseSound(player, source.attributes());
      itemService.consumeUse(player, source.stack());
    });
    return true;
  }

  /**
   * Protocol fallback entrypoint for sneak-right-click air with empty hand.
   *
   * @param player player to process
   */
  public void triggerSneakDashFallback(Player player) {
    ItemStack mainHand = player.getInventory().getItemInMainHand();
    if (mainHand.getType() != Material.AIR || !player.isSneaking()) {
      return;
    }
    triggerSneakDash(player);
  }

  private Optional<ItemWithAttributes> findDoubleJumpSource(Player player) {
    ItemWithAttributes best = null;
    int bestPercent = 0;
    for (ItemStack stack : resolveHeldAndEquippedCustomItems(player)) {
      Map<String, String> attributes = itemService.resolveAttributes(stack);
      int percent = itemService.intValue(attributes, "double_jump").orElse(0);
      if (percent > bestPercent) {
        bestPercent = percent;
        best = new ItemWithAttributes(stack, attributes);
      }
    }
    return Optional.ofNullable(best);
  }

  private Optional<ItemWithAttributes> findDashAbilitySource(Player player) {
    ItemWithAttributes best = null;
    double bestStrength = 0.0;
    for (ItemStack stack : resolveHeldAndEquippedCustomItems(player)) {
      Map<String, String> attributes = itemService.resolveAttributes(stack);
      Optional<Double> strength = itemService.doubleValue(attributes, "dash_ability");
      if (strength.isEmpty() || strength.get() <= bestStrength) {
        continue;
      }
      bestStrength = strength.get();
      best = new ItemWithAttributes(stack, attributes);
    }
    return Optional.ofNullable(best);
  }

  private Map<String, String> resolvePotionAttributes(Player player) {
    Map<String, String> merged = new HashMap<>();
    for (ItemStack stack : resolveHeldAndEquippedCustomItems(player)) {
      mergePotionAttributes(merged, itemService.resolveAttributes(stack));
    }
    return merged;
  }

  private Map<String, String> resolveMobilityAttributes(Player player) {
    Map<String, String> merged = new HashMap<>();
    for (ItemStack stack : resolveHeldAndEquippedCustomItems(player)) {
      Map<String, String> attributes = itemService.resolveAttributes(stack);
      mergeMaxIntegerAttribute(merged, attributes, "double_jump");
      mergeBooleanAttribute(merged, attributes, "glide_mode");
    }
    return merged;
  }

  private List<ItemStack> resolveHeldAndEquippedCustomItems(Player player) {
    List<ItemStack> sources = new ArrayList<>();
    addIfCustomItem(sources, player.getInventory().getItemInMainHand());
    addIfCustomItem(sources, player.getInventory().getItemInOffHand());
    for (ItemStack armorPiece : player.getInventory().getArmorContents()) {
      addIfCustomItem(sources, armorPiece);
    }
    return sources;
  }

  private void addIfCustomItem(List<ItemStack> sources, ItemStack stack) {
    if (stack != null && stack.getType() != Material.AIR && itemService.customItemId(stack).isPresent()) {
      sources.add(stack);
    }
  }

  private record ItemWithAttributes(ItemStack stack, Map<String, String> attributes) {
  }

  private void mergePotionAttributes(Map<String, String> merged, Map<String, String> candidate) {
    mergeMaxIntegerAttribute(merged, candidate, "speed_bonus");
    mergeMaxIntegerAttribute(merged, candidate, "jump_boost");
    mergeMaxIntegerAttribute(merged, candidate, "haste");
    mergeMaxIntegerAttribute(merged, candidate, "strength_boost");
    mergeMaxIntegerAttribute(merged, candidate, "regeneration");
    mergeMaxDoubleAttribute(merged, candidate, "health_bonus");
    mergeMaxDoubleAttribute(merged, candidate, "absorption_hearts");
    mergeBooleanAttribute(merged, candidate, "night_vision");
    mergeBooleanAttribute(merged, candidate, "water_breathing");
    mergeBooleanAttribute(merged, candidate, "fire_resistance");
  }

  private void mergeMaxIntegerAttribute(
      Map<String, String> merged,
      Map<String, String> candidate,
      String key) {
    itemService.intValue(candidate, key).ifPresent(value -> {
      int current = itemService.intValue(merged, key).orElse(0);
      if (value > current) {
        merged.put(key, String.valueOf(value));
      }
    });
  }

  private void mergeMaxDoubleAttribute(
      Map<String, String> merged,
      Map<String, String> candidate,
      String key) {
    itemService.doubleValue(candidate, key).ifPresent(value -> {
      double current = itemService.doubleValue(merged, key).orElse(0.0);
      if (value > current) {
        merged.put(key, String.valueOf(value));
      }
    });
  }

  private void mergeBooleanAttribute(
      Map<String, String> merged,
      Map<String, String> candidate,
      String key) {
    if (itemService.boolValue(candidate, key)) {
      merged.put(key, "true");
    }
  }

  private void removeStaleManagedPotionEffects(Player player, Set<PotionEffectType> desiredEffects) {
    UUID playerId = player.getUniqueId();
    Set<PotionEffectType> previousEffects = managedPotionEffects.get(playerId);
    if (previousEffects != null) {
      for (PotionEffectType effectType : previousEffects) {
        if (!desiredEffects.contains(effectType)) {
          player.removePotionEffect(effectType);
        }
      }
    }
    if (desiredEffects.isEmpty()) {
      managedPotionEffects.remove(playerId);
      return;
    }
    managedPotionEffects.put(playerId, new HashSet<>(desiredEffects));
  }

  private void applyManagedPotionEffect(
      Player player,
      PotionEffectType effectType,
      int amplifier,
      Set<PotionEffectType> desiredEffects,
      boolean shouldRefresh) {
    desiredEffects.add(effectType);
    PotionEffect active = player.getPotionEffect(effectType);
    boolean missing = active == null;
    boolean changedAmplifier = active != null && active.getAmplifier() != amplifier;
    if (!shouldRefresh && !missing && !changedAmplifier) {
      return;
    }
    player.addPotionEffect(new PotionEffect(effectType, POTION_DURATION_TICKS, amplifier, true, false,
        false));
  }

  private void applyTrailParticles(Player player, Map<String, String> attributes) {
    String particleName = attributes.get("particle_trail");
    if (particleName == null || particleName.isBlank()) {
      return;
    }
    spawnConfiguredParticle(player, particleName, player.getLocation().add(0, 0.15, 0), 10, 0.25,
        0.1, 0.25, 0.01);
  }

  private void applyBlockHighlighting(Player player, Map<String, String> attributes) {
    if (!itemService.boolValue(attributes, "block_highlighting")) {
      return;
    }
    Location base = player.getLocation();
    int radius = 8;
    int highlighted = 0;
    for (int x = -radius; x <= radius && highlighted < 48; x++) {
      for (int y = -4; y <= 4 && highlighted < 48; y++) {
        for (int z = -radius; z <= radius && highlighted < 48; z++) {
          Block block = base.getBlock().getRelative(x, y, z);
          if (isValuable(block.getType())) {
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            player.spawnParticle(Particle.END_ROD, center, 8, 0.25, 0.25, 0.25, 0.0);
            player.spawnParticle(Particle.ELECTRIC_SPARK, center.clone().add(0, 0.3, 0), 12, 0.35,
                0.25, 0.35, 0.02);
            highlighted++;
          }
        }
      }
    }
  }

  private void applyMobDetection(Player player, Map<String, String> attributes) {
    if (!itemService.boolValue(attributes, "mob_detection")) {
      return;
    }
    double radius = plugin.getConfig().getDouble("mob-detection-radius", 22.0);
    for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
      if (entity instanceof LivingEntity living && !(living instanceof Player)) {
        living.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0, true, false,
            false));
      }
    }
  }

  private void applyMagnetModes(Player player, Map<String, String> attributes) {
    boolean magnetMode = itemService.boolValue(attributes, "magnet_mode");
    boolean autoPickup = itemService.boolValue(attributes, "auto_pickup");
    boolean xpMagnet = itemService.boolValue(attributes, "xp_magnet");
    double radius = itemService.doubleValue(attributes, "item_vacuum_radius")
        .orElse(plugin.getConfig().getDouble("default-item-vacuum-radius", 6.0));
    double pullStrength = itemService.doubleValue(attributes, "item_vacuum_pull_radius")
        .orElse(plugin.getConfig().getDouble("default-item-vacuum-pull-radius", 0.28));
    if (!magnetMode && !autoPickup && !xpMagnet) {
      return;
    }
    for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
      if (entity instanceof Item item) {
        if (autoPickup) {
          HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item.getItemStack());
          if (overflow.isEmpty()) {
            item.remove();
          }
        } else if (magnetMode) {
          Vector velocity = player.getLocation().toVector().subtract(item.getLocation().toVector())
              .normalize().multiply(pullStrength);
          item.setVelocity(velocity);
        }
      }
      if (xpMagnet && entity instanceof ExperienceOrb orb) {
        Vector velocity = player.getLocation().toVector().subtract(orb.getLocation().toVector())
            .normalize().multiply(pullStrength + 0.04);
        orb.setVelocity(velocity);
      }
    }
  }

  private void applyGlide(Player player, Map<String, String> attributes) {
    if (!itemService.boolValue(attributes, "glide_mode")) {
      return;
    }
    if (!player.isOnGround() && player.getVelocity().getY() < -0.08) {
      Vector velocity = player.getVelocity().clone();
      velocity.setY(Math.max(-0.08, velocity.getY() * 0.55));
      player.setVelocity(velocity);
      player.setFallDistance(0.0f);
    }
  }

  private void updateDoubleJumpState(Player player, Map<String, String> attributes) {
    if (player.getGameMode().name().equals("CREATIVE")
        || player.getGameMode().name().equals("SPECTATOR")) {
      return;
    }
    boolean hasDoubleJump = itemService.intValue(attributes, "double_jump").orElse(0) > 0;
    if (hasDoubleJump && player.isOnGround()) {
      player.setAllowFlight(true);
    } else if (!hasDoubleJump && player.getAllowFlight()) {
      player.setAllowFlight(false);
    }
  }

  private BlockFace horizontalFacing(BlockFace facing) {
    return switch (facing) {
      case NORTH, SOUTH, EAST, WEST -> facing;
      default -> BlockFace.NORTH;
    };
  }

  private void applyTillRadius(Player player, Block clicked, Map<String, String> attributes) {
    int radius = itemService.intValue(attributes, "till_radius").orElse(0);
    if (radius <= 0) {
      return;
    }
    for (int x = -radius; x <= radius; x++) {
      for (int z = -radius; z <= radius; z++) {
        Block current = clicked.getRelative(x, 0, z);
        if (current.getType() == Material.DIRT || current.getType() == Material.GRASS_BLOCK
            || current.getType() == Material.DIRT_PATH) {
          Block above = current.getRelative(BlockFace.UP);
          if (above.getType() == Material.AIR) {
            current.setType(Material.FARMLAND);
            player.getWorld().spawnParticle(Particle.BLOCK, current.getLocation().add(0.5, 0.5, 0.5),
                4, 0.2, 0.2, 0.2, Material.FARMLAND.createBlockData());
          }
        }
      }
    }
  }

  private void applyPlacementRadius(
      Player player,
      Block clicked,
      BlockFace clickedFace,
      ItemStack held,
      Map<String, String> attributes) {
    String placement = attributes.get("block_placement_radius");
    if (placement == null || placement.isBlank()) {
      return;
    }
    Material placeMaterial = held.getType();
    if (!placeMaterial.isBlock()) {
      return;
    }
    String[] split = placement.toLowerCase(Locale.ROOT).split(":");
    String mode = split[0];
    int value = split.length >= 2 ? safeParseInt(split[1], 3) : 3;
    BlockFace face = clickedFace == null ? BlockFace.UP : clickedFace;
    Block origin = clicked.getRelative(face);
    if (mode.equals("line")) {
      BlockFace direction = face == BlockFace.UP || face == BlockFace.DOWN
          ? horizontalFacing(player.getFacing())
          : face;
      for (int i = 0; i < value; i++) {
        Block target = origin.getRelative(direction, i);
        if (target.getType() == Material.AIR) {
          target.setType(placeMaterial);
        }
      }
    } else if (mode.equals("wall")) {
      BlockFace facing = horizontalFacing(player.getFacing());
      int width = Math.max(1, value);
      for (int y = 0; y < value; y++) {
        for (int offset = -width; offset <= width; offset++) {
          Block target = facing == BlockFace.NORTH || facing == BlockFace.SOUTH
              ? origin.getRelative(offset, y, 0)
              : origin.getRelative(0, y, offset);
          if (target.getType() == Material.AIR) {
            target.setType(placeMaterial);
          }
        }
      }
    } else {
      int radius = Math.max(1, value / 2);
      for (int x = -radius; x <= radius; x++) {
        for (int z = -radius; z <= radius; z++) {
          Block target = origin.getRelative(x, 0, z);
          if (target.getType() == Material.AIR) {
            target.setType(placeMaterial);
          }
        }
      }
    }
  }

  private void applyBlockReplace(Player player, Block clicked, Map<String, String> attributes) {
    String replace = attributes.get("block_replace_mode");
    if (replace == null || replace.isBlank()) {
      return;
    }
    String[] split = replace.split(">");
    if (split.length != 2) {
      return;
    }
    Material from = safeMaterial(split[0]);
    Material to = safeMaterial(split[1]);
    if (from == null || to == null) {
      return;
    }
    int radius = Math.max(1, itemService.intValue(attributes, "break_radius")
        .orElse(plugin.getConfig().getInt("default-break-radius", 1)));
    Location center = clicked.getLocation();
    for (int x = -radius; x <= radius; x++) {
      for (int y = -radius; y <= radius; y++) {
        for (int z = -radius; z <= radius; z++) {
          Block target = center.getBlock().getRelative(x, y, z);
          if (target.getType() == from) {
            target.setType(to);
          }
        }
      }
    }
    player.getWorld().playSound(center, Sound.BLOCK_STONE_PLACE, 0.8f, 1.2f);
  }

  private Set<Block> collectExtraBlocks(Block origin, Player player, Map<String, String> attributes) {
    if (itemService.boolValue(attributes, "vein_mining") && isOreBlock(origin.getType())) {
      int max = plugin.getConfig().getInt("max-vein-size", 96);
      return collectConnected(origin, max);
    }
    if (itemService.boolValue(attributes, "tree_feller") && origin.getType().name().endsWith("_LOG")) {
      return collectTree(origin, 196);
    }
    int cropRadius = itemService.intValue(attributes, "crop_harvester").orElse(0);
    if (cropRadius > 0) {
      return collectCrops(origin, cropRadius);
    }
    String volume = attributes.get("break_volume");
    if (volume != null && !volume.isBlank()) {
      int[] dims = parseVolume(volume);
      return collectVolume(origin, player, attributes, dims[0], dims[1], dims[2]);
    }
    int radius = itemService.intValue(attributes, "break_radius").orElse(0);
    if (radius > 0) {
      int size = radius * 2 + 1;
      return collectVolume(origin, player, attributes, size, 1, size);
    }
    return Set.of();
  }

  private boolean isOreBlock(Material material) {
    if (material == Material.ANCIENT_DEBRIS) {
      return true;
    }
    return material.name().endsWith("_ORE");
  }

  private Set<Block> collectVolume(
      Block origin,
      Player player,
      Map<String, String> attributes,
      int width,
      int height,
      int depth) {
    Set<Block> blocks = new HashSet<>();
    int halfWidth = Math.max(0, width / 2);
    int halfDepth = Math.max(0, depth / 2);
    int halfHeight = Math.max(0, height / 2);
    boolean directional = itemService.boolValue(attributes, "directional_mining");
    Vector direction = player.getLocation().getDirection();
    int directionSign = direction.getY() > 0.45 ? 1 : direction.getY() < -0.45 ? -1 : 0;
    for (int x = -halfWidth; x <= halfWidth; x++) {
      for (int y = -halfHeight; y <= halfHeight; y++) {
        for (int z = -halfDepth; z <= halfDepth; z++) {
          int targetY = directional ? y + directionSign : y;
          Block relative = origin.getRelative(x, targetY, z);
          if (relative.getType() != Material.AIR && !isUnbreakableBlock(relative.getType())) {
            blocks.add(relative);
          }
        }
      }
    }
    return blocks;
  }

  private Set<Block> collectConnected(Block origin, int max) {
    Set<Block> found = new HashSet<>();
    ArrayDeque<Block> queue = new ArrayDeque<>();
    String family = oreFamily(origin.getType());
    queue.add(origin);
    while (!queue.isEmpty() && found.size() < max) {
      Block current = queue.poll();
      if (!found.add(current)) {
        continue;
      }
      for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
          for (int z = -1; z <= 1; z++) {
            Block relative = current.getRelative(x, y, z);
            if (isMatchingOreFamily(relative.getType(), family)
                && !isUnbreakableBlock(relative.getType())
                && !found.contains(relative)) {
              queue.add(relative);
            }
          }
        }
      }
    }
    return found;
  }

  private boolean isMatchingOreFamily(Material material, String family) {
    return isOreBlock(material) && oreFamily(material).equals(family);
  }

  private String oreFamily(Material material) {
    String name = material.name();
    if (name.startsWith("DEEPSLATE_")) {
      return name.substring("DEEPSLATE_".length());
    }
    return name;
  }

  private Set<Block> collectTree(Block origin, int max) {
    Set<Block> found = new HashSet<>();
    ArrayDeque<Block> queue = new ArrayDeque<>();
    queue.add(origin);
    while (!queue.isEmpty() && found.size() < max) {
      Block current = queue.poll();
      if (!found.add(current)) {
        continue;
      }
      for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
          for (int z = -1; z <= 1; z++) {
            Block relative = current.getRelative(x, y, z);
            String type = relative.getType().name();
            if ((type.endsWith("_LOG") || type.endsWith("_LEAVES"))
                && !isUnbreakableBlock(relative.getType())
                && !found.contains(relative)) {
              queue.add(relative);
            }
          }
        }
      }
    }
    return found;
  }

  private Set<Block> collectCrops(Block origin, int radius) {
    Set<Block> crops = new HashSet<>();
    for (int x = -radius; x <= radius; x++) {
      for (int z = -radius; z <= radius; z++) {
        Block block = origin.getRelative(x, 0, z);
        if (!isUnbreakableBlock(block.getType()) && isHarvestableCrop(block)) {
          crops.add(block);
        }
      }
    }
    return crops;
  }

  private boolean isHarvestableCrop(Block block) {
    if (!(block.getBlockData() instanceof Ageable ageable)) {
      return false;
    }
    return ageable.getAge() >= ageable.getMaximumAge();
  }

  private void scheduleCropReplant(Block broken, Material cropType) {
    if (!(cropType.createBlockData() instanceof Ageable)) {
      return;
    }
    Bukkit.getScheduler().runTask(plugin, () -> {
      Block target = broken.getLocation().getBlock();
      if (target.getType() != Material.AIR) {
        return;
      }
      target.setType(cropType);
      if (target.getBlockData() instanceof Ageable replanted) {
        replanted.setAge(0);
        target.setBlockData(replanted);
      }
    });
  }

  private int[] parseVolume(String input) {
    String[] split = input.toLowerCase(Locale.ROOT).replace(" ", "").split("x");
    if (split.length != 3) {
      return new int[] {3, 3, 3};
    }
    return new int[] {
        Math.max(1, safeParseInt(split[0], 3)),
        Math.max(1, safeParseInt(split[1], 3)),
        Math.max(1, safeParseInt(split[2], 3))
    };
  }

  private void teleportForward(Player player, double distance) {
    RayTraceResult ray = player.getWorld().rayTraceBlocks(player.getEyeLocation(),
        player.getLocation().getDirection(), distance);
    Location target = ray != null && ray.getHitPosition() != null
        ? ray.getHitPosition().toLocation(player.getWorld())
        : player.getEyeLocation().add(player.getLocation().getDirection().multiply(distance));
    target.setPitch(player.getLocation().getPitch());
    target.setYaw(player.getLocation().getYaw());
    player.teleport(target);
    player.getWorld().spawnParticle(Particle.PORTAL, target, 30, 0.4, 0.6, 0.4, 0.01);
  }

  private void applyAreaAbility(Player player, double radius) {
    for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
      if (!(entity instanceof LivingEntity living) || entity.equals(player)) {
        continue;
      }
      combatEffectGuard.add(player.getUniqueId());
      living.damage(4.0, player);
      combatEffectGuard.remove(player.getUniqueId());
      living.getWorld().spawnParticle(Particle.SWEEP_ATTACK, living.getLocation().add(0, 1, 0), 1,
          0.0, 0.0, 0.0, 0.0);
    }
  }

  private void applyDash(Player player, double strength) {
    Vector dash = player.getLocation().getDirection().normalize().multiply(strength);
    dash.setY(Math.max(dash.getY(), 0.25));
    player.setVelocity(dash);
    player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0, 0.2, 0), 18, 0.2,
        0.1, 0.2, 0.01);
  }

  private void applySlowNearby(Player player, int seconds) {
    for (Entity entity : player.getNearbyEntities(6.0, 4.0, 6.0)) {
      if (entity instanceof LivingEntity living && !entity.equals(player)) {
        living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, seconds * 20, 1, true,
            true, true));
      }
    }
  }

  private void spawnEntityNearPlayer(Player player, String entityName) {
    try {
      EntityType type = EntityType.valueOf(entityName.toUpperCase(Locale.ROOT));
      player.getWorld().spawnEntity(player.getLocation().add(0, 0.5, 0), type);
    } catch (IllegalArgumentException ex) {
      player.sendMessage(color("&cInvalid summon entity: " + entityName));
    }
  }

  private void triggerNamedAbility(
      Player player,
      ItemStack held,
      String abilityName,
      Map<String, String> attributes) {
    String normalized = abilityName.toLowerCase(Locale.ROOT);
    if (normalized.equals("heal")) {
      player.setHealth(Math.min(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)
          .getValue(), player.getHealth() + 6.0));
      player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0, 1, 0), 6, 0.4,
          0.3, 0.4, 0.01);
    } else if (normalized.equals("blink")) {
      double distance = itemService.doubleValue(attributes, "teleport_on_right_click").orElse(6.0);
      teleportForward(player, distance);
    } else if (normalized.equals("burst")) {
      double radius = itemService.doubleValue(attributes, "area_ability").orElse(4.0);
      applyAreaAbility(player, radius);
    } else if (normalized.equals("projectile")) {
      launchConfiguredProjectile(player, attributes.get("projectile_launch"));
    } else if (normalized.equals("block")) {
      int seconds = itemService.intValue(attributes, "block_ability").orElse(4);
      player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, seconds * 20, 2, true,
          true, true));
    }
  }

  private void launchConfiguredProjectile(Player player, String value) {
    String config = value == null || value.isBlank() ? "arrow:2.0" : value;
    String[] split = config.split(":");
    String projectileName = split[0].toLowerCase(Locale.ROOT);
    double speed = split.length > 1 ? safeParseDouble(split[1], 2.0) : 2.0;
    Projectile projectile;
    if (projectileName.equals("snowball")) {
      projectile = player.launchProjectile(org.bukkit.entity.Snowball.class);
    } else if (projectileName.equals("egg")) {
      projectile = player.launchProjectile(org.bukkit.entity.Egg.class);
    } else if (projectileName.equals("trident")) {
      projectile = player.launchProjectile(org.bukkit.entity.Trident.class);
    } else if (projectileName.equals("fireball")) {
      projectile = player.launchProjectile(org.bukkit.entity.Fireball.class);
    } else {
      projectile = player.launchProjectile(AbstractArrow.class);
    }
    projectile.setVelocity(player.getLocation().getDirection().normalize().multiply(speed));
  }

  private void pullNearbyEntities(Player player, double radius, double strength) {
    for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
      if (entity instanceof LivingEntity living && !living.equals(player)) {
        Vector pull = player.getLocation().toVector().subtract(entity.getLocation().toVector())
            .normalize().multiply(strength);
        entity.setVelocity(entity.getVelocity().add(pull));
      }
    }
  }

  private void pushNearbyEntities(Player player, double radius, double strength) {
    for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
      if (entity instanceof LivingEntity living && !living.equals(player)) {
        Vector push = entity.getLocation().toVector().subtract(player.getLocation().toVector())
            .normalize().multiply(strength);
        entity.setVelocity(entity.getVelocity().add(push));
      }
    }
  }

  private void sweepDamage(Player attacker, LivingEntity center, double damage, double radius) {
    for (Entity entity : center.getNearbyEntities(radius, radius, radius)) {
      if (entity.equals(attacker) || entity.equals(center) || !(entity instanceof LivingEntity living)) {
        continue;
      }
      combatEffectGuard.add(attacker.getUniqueId());
      living.damage(damage, attacker);
      combatEffectGuard.remove(attacker.getUniqueId());
    }
  }

  private void chainLightning(Player attacker, LivingEntity first, int hops, double hopDamage) {
    if (hops <= 0) {
      return;
    }
    List<LivingEntity> chain = new ArrayList<>();
    chain.add(first);
    LivingEntity current = first;
    for (int i = 0; i < hops; i++) {
      Location currentLocation = current.getLocation();
      Optional<LivingEntity> next = current.getNearbyEntities(6.0, 6.0, 6.0)
          .stream()
          .filter(entity -> entity instanceof LivingEntity)
          .map(entity -> (LivingEntity) entity)
          .filter(entity -> !entity.equals(attacker))
          .filter(entity -> !chain.contains(entity))
          .min(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(
              currentLocation)));
      if (next.isEmpty()) {
        break;
      }
      LivingEntity victim = next.get();
      chain.add(victim);
      combatEffectGuard.add(attacker.getUniqueId());
      victim.damage(hopDamage, attacker);
      combatEffectGuard.remove(attacker.getUniqueId());
      victim.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, victim.getLocation().add(0, 1, 0),
          10, 0.2, 0.3, 0.2, 0.03);
      current = victim;
    }
  }

  private Optional<ItemStack> smeltResult(ItemStack input) {
    for (Recipe recipe : Bukkit.getRecipesFor(input)) {
      if (recipe instanceof CookingRecipe<?> cookingRecipe) {
        ItemStack result = cookingRecipe.getResult().clone();
        result.setAmount(Math.max(1, result.getAmount() * input.getAmount()));
        return Optional.of(result);
      }
    }
    return Optional.empty();
  }

  private void applyOptionalEffect(
      Player player,
      Map<String, String> attributes,
      String attribute,
      PotionEffectType effectType,
      Set<PotionEffectType> desiredEffects,
      boolean shouldRefresh) {
    itemService.intValue(attributes, attribute)
        .ifPresent(level -> applyManagedPotionEffect(player, effectType, Math.max(0, level - 1),
            desiredEffects, shouldRefresh));
  }

  private void playUseSound(Player player, Map<String, String> attributes) {
    String soundName = attributes.get("sound_on_use");
    if (soundName == null || soundName.isBlank()) {
      return;
    }
    optionalSound(soundName).ifPresent(sound -> player.getWorld().playSound(player.getLocation(),
        sound, 0.8f, 1.0f));
  }

  private void playHitSound(Player player, Map<String, String> attributes) {
    String soundName = attributes.get("sound_on_hit");
    if (soundName == null || soundName.isBlank()) {
      return;
    }
    optionalSound(soundName).ifPresent(sound -> player.getWorld().playSound(player.getLocation(),
        sound, 1.0f, 1.0f));
  }

  private void playSwingParticles(Player player, Map<String, String> attributes) {
    String particleName = attributes.get("swing_particles");
    if (particleName == null || particleName.isBlank()) {
      return;
    }
    spawnConfiguredParticle(
        player,
        particleName,
        player.getEyeLocation().add(player.getLocation().getDirection().multiply(1.1)),
        10,
        0.18,
        0.18,
        0.18,
        0.03);
  }

  private void spawnConfiguredParticle(
      Player player,
      String configuredValue,
      Location location,
      int count,
      double offsetX,
      double offsetY,
      double offsetZ,
      double extra) {
    Optional<ConfiguredParticle> configured = parseConfiguredParticle(configuredValue);
    if (configured.isEmpty()) {
      return;
    }
    ConfiguredParticle particle = configured.get();
    if (particle.data() == null) {
      player.getWorld().spawnParticle(
          particle.particle(),
          location,
          count,
          offsetX,
          offsetY,
          offsetZ,
          extra);
      return;
    }
    player.getWorld().spawnParticle(
        particle.particle(),
        location,
        count,
        offsetX,
        offsetY,
        offsetZ,
        extra,
        particle.data());
  }

  private void animateLoreIfNeeded(ItemStack held, Map<String, String> attributes) {
    String animation = attributes.get("animated_lore");
    if (animation == null || animation.isBlank()) {
      return;
    }
    String[] frames = animation.split("\\|");
    if (frames.length == 0) {
      return;
    }
    int index = (int) ((System.currentTimeMillis() / 1000L) % frames.length);
    ItemMeta meta = held.getItemMeta();
    if (meta == null || meta.lore() == null || meta.lore().isEmpty()) {
      return;
    }
    List<net.kyori.adventure.text.Component> lore = new ArrayList<>(meta.lore());
    lore.set(0, serializer.deserialize(frames[index]));
    meta.lore(lore);
    held.setItemMeta(meta);
  }

  private boolean rollCritical(Map<String, String> attributes) {
    double chance = itemService.doubleValue(attributes, "critical_chance").orElse(0.0);
    if (chance <= 0.0) {
      return false;
    }
    return Math.random() <= chance;
  }

  private boolean isVanillaCritical(Player player) {
    return player.getFallDistance() > 0.0f
        && !player.isOnGround()
        && !player.isSprinting()
        && !player.isInsideVehicle()
        && !player.hasPotionEffect(PotionEffectType.BLINDNESS);
  }

  private boolean passesBlockLists(Block block, Map<String, String> attributes) {
    if (isUnbreakableBlock(block.getType())) {
      return false;
    }
    String whitelist = attributes.get("block_whitelist");
    if (whitelist != null && !whitelist.isBlank()) {
      boolean matched = parseMaterialList(whitelist).contains(block.getType());
      if (!matched) {
        return false;
      }
    }
    String blacklist = attributes.get("block_blacklist");
    return blacklist == null || !parseMaterialList(blacklist).contains(block.getType());
  }

  private boolean isUnbreakableBlock(Material material) {
    if (!material.isBlock()) {
      return false;
    }
    try {
      if (material.getHardness() < 0.0f) {
        return true;
      }
    } catch (NoSuchMethodError ex) {
      // Hardness may not exist in older APIs.
    }
    return switch (material) {
      case BEDROCK,
          BARRIER,
          END_PORTAL_FRAME,
          END_PORTAL,
          NETHER_PORTAL,
          END_GATEWAY,
          COMMAND_BLOCK,
          CHAIN_COMMAND_BLOCK,
          REPEATING_COMMAND_BLOCK,
          STRUCTURE_BLOCK,
          JIGSAW,
          LIGHT,
          RESPAWN_ANCHOR ->
          true;
      default -> false;
    };
  }

  private void dropUnsoldStacks(Block block, List<ItemStack> unsoldDrops) {
    if (unsoldDrops.isEmpty()) {
      return;
    }
    Location dropLocation = block.getLocation().add(0.5, 0.5, 0.5);
    for (ItemStack stack : unsoldDrops) {
      if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) {
        continue;
      }
      block.getWorld().dropItemNaturally(dropLocation, stack.clone());
    }
  }

  private void replaceContainerWithUnsold(Block block, List<ItemStack> unsoldDrops) {
    if (!(block.getState() instanceof InventoryHolder holder)) {
      return;
    }
    Inventory inventory = holder.getInventory();
    if (inventory == null) {
      return;
    }
    inventory.clear();
    Location dropLocation = block.getLocation().add(0.5, 0.5, 0.5);
    for (ItemStack stack : unsoldDrops) {
      if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) {
        continue;
      }
      HashMap<Integer, ItemStack> overflow = inventory.addItem(stack.clone());
      for (ItemStack overflowStack : overflow.values()) {
        block.getWorld().dropItemNaturally(dropLocation, overflowStack);
      }
    }
    if (block.getState() instanceof Container container) {
      container.update(true, false);
    }
  }

  private void clearContainerContents(Block block) {
    if (!(block.getState() instanceof InventoryHolder holder)) {
      return;
    }
    Inventory inventory = holder.getInventory();
    if (inventory == null) {
      return;
    }
    inventory.clear();
    if (block.getState() instanceof Container container) {
      container.update(true, false);
    }
  }

  private Set<Material> parseMaterialList(String value) {
    Set<Material> materials = new HashSet<>();
    for (String split : value.split(",")) {
      Material material = safeMaterial(split);
      if (material != null) {
        materials.add(material);
      }
    }
    return materials;
  }

  private boolean isPluginManagedItem(ItemStack stack) {
    return stack != null
        && stack.getType() != Material.AIR
        && itemService.customItemId(stack).isPresent();
  }

  private boolean inventoryHasPluginItem(Inventory inventory, int... slots) {
    for (int slot : slots) {
      if (isPluginManagedItem(inventory.getItem(slot))) {
        return true;
      }
    }
    return false;
  }

  private boolean isProtectedModificationClick(InventoryClickEvent event) {
    Inventory topInventory = event.getView().getTopInventory();
    InventoryType type = topInventory.getType();
    if (type != InventoryType.ANVIL
        && type != InventoryType.GRINDSTONE
        && type != InventoryType.ENCHANTING
        && type != InventoryType.SMITHING) {
      return false;
    }
    if (event.isShiftClick() && isPluginManagedItem(event.getCurrentItem())) {
      return true;
    }
    Inventory clicked = event.getClickedInventory();
    if (clicked != null && clicked.equals(topInventory)) {
      if (isPluginManagedItem(event.getCursor())) {
        return true;
      }
      int hotbar = event.getHotbarButton();
      if (hotbar >= 0 && hotbar < event.getWhoClicked().getInventory().getSize()) {
        ItemStack hotbarItem = event.getWhoClicked().getInventory().getItem(hotbar);
        if (isPluginManagedItem(hotbarItem)) {
          return true;
        }
      }
    }
    return false;
  }

  private Player resolveAttacker(Entity damager) {
    if (damager instanceof Player player) {
      return player;
    }
    if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
      return player;
    }
    return null;
  }

  private boolean isValuable(Material material) {
    return material.name().endsWith("_ORE")
        || material == Material.ANCIENT_DEBRIS
        || material == Material.CHEST
        || material == Material.SPAWNER;
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

  private Optional<Sound> optionalSound(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Sound.valueOf(value.toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException ex) {
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

  private int safeParseInt(String value, int fallback) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private double safeParseDouble(String value, double fallback) {
    try {
      return Double.parseDouble(value.trim());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private boolean isMovementTickDue(Player player) {
    long now = System.currentTimeMillis();
    Long last = movementThrottle.get(player.getUniqueId());
    if (last == null || now - last >= 150L) {
      movementThrottle.put(player.getUniqueId(), now);
      return true;
    }
    return false;
  }

  private String locationKey(Location location) {
    return location.getWorld().getName() + ':' + location.getBlockX() + ':' + location.getBlockY()
        + ':' + location.getBlockZ();
  }

  private String color(String message) {
    return ChatColor.translateAlternateColorCodes('&', message);
  }

  private boolean isPreventedByDefault(Map<String, String> attributes, String key) {
    if (!attributes.containsKey(key)) {
      return true;
    }
    return itemService.boolValue(attributes, key);
  }

  private record ConfiguredParticle(
      Particle particle,
      Object data) {
  }
}
