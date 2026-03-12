package com.hudscustomitems.customitems.service;

import com.hudscustomitems.customitems.attribute.AttributeCatalog;
import com.hudscustomitems.customitems.attribute.AttributeDefinition;
import com.hudscustomitems.customitems.model.CustomItemDefinition;
import com.hudscustomitems.customitems.storage.ItemStorage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Core service responsible for item definitions, lore generation and runtime attribute access.
 */
public final class CustomItemService {
  private final JavaPlugin plugin;
  private final ItemStorage itemStorage;
  private final LegacyComponentSerializer legacySerializer;
  private final Map<String, CustomItemDefinition> definitions;
  private final Map<String, Long> cooldowns;
  private final Map<String, Long> chargeStarts;
  private final NamespacedKey itemIdKey;
  private final NamespacedKey oneTimeAttributesKey;
  private final NamespacedKey ownerUuidKey;
  private final NamespacedKey usesLeftKey;
  private final NamespacedKey energyLeftKey;

  /**
   * Creates a new service instance.
   *
   * @param plugin owner plugin
   */
  public CustomItemService(JavaPlugin plugin) {
    this.plugin = plugin;
    this.itemStorage = new ItemStorage(plugin);
    this.legacySerializer = LegacyComponentSerializer.legacyAmpersand();
    this.definitions = new LinkedHashMap<>();
    this.cooldowns = new ConcurrentHashMap<>();
    this.chargeStarts = new ConcurrentHashMap<>();
    this.itemIdKey = new NamespacedKey(plugin, "custom_item_id");
    this.oneTimeAttributesKey = new NamespacedKey(plugin, "one_time_attributes");
    this.ownerUuidKey = new NamespacedKey(plugin, "owner_uuid");
    this.usesLeftKey = new NamespacedKey(plugin, "uses_left");
    this.energyLeftKey = new NamespacedKey(plugin, "energy_left");
    reloadDefinitions();
  }

  /**
   * Reloads all item definitions from disk.
   */
  public void reloadDefinitions() {
    definitions.clear();
    definitions.putAll(itemStorage.loadDefinitions());
  }

  /**
   * Saves all in-memory item definitions.
   */
  public void saveDefinitions() {
    itemStorage.saveDefinitions(definitions);
  }

  /**
   * Gets one definition by id.
   *
   * @param id item id
   * @return optional definition
   */
  public Optional<CustomItemDefinition> definition(String id) {
    return Optional.ofNullable(definitions.get(normalizeId(id)));
  }

  /**
   * Gets all definition ids.
   *
   * @return id collection
   */
  public Collection<String> definitionIds() {
    return List.copyOf(definitions.keySet());
  }

  /**
   * Gets all item definitions.
   *
   * @return definitions in insertion order
   */
  public Collection<CustomItemDefinition> definitions() {
    return List.copyOf(definitions.values());
  }

  /**
   * Creates and stores a new item definition.
   *
   * @param id id
   * @param material material
   * @param displayName display name
   * @return created definition
   */
  public CustomItemDefinition createDefinition(String id, Material material, String displayName) {
    String normalized = normalizeId(id);
    CustomItemDefinition created = new CustomItemDefinition(
        normalized,
        material,
        displayName,
        List.of(),
        Map.of());
    definitions.put(normalized, created);
    saveDefinitions();
    return created;
  }

  /**
   * Deletes an item definition.
   *
   * @param id id
   * @return true if removed
   */
  public boolean removeDefinition(String id) {
    String normalized = normalizeId(id);
    CustomItemDefinition removed = definitions.remove(normalized);
    if (removed != null) {
      saveDefinitions();
      return true;
    }
    return false;
  }

  /**
   * Updates definition display name.
   *
   * @param id item id
   * @param displayName new name
   * @return true if updated
   */
  public boolean setDisplayName(String id, String displayName) {
    CustomItemDefinition definition = definitions.get(normalizeId(id));
    if (definition == null) {
      return false;
    }
    definition.setDisplayName(displayName);
    saveDefinitions();
    return true;
  }

  /**
   * Updates definition material.
   *
   * @param id item id
   * @param material material
   * @return true if updated
   */
  public boolean setMaterial(String id, Material material) {
    CustomItemDefinition definition = definitions.get(normalizeId(id));
    if (definition == null) {
      return false;
    }
    definition.setMaterial(material);
    saveDefinitions();
    return true;
  }

  /**
   * Replaces lore lines for a definition.
   *
   * @param id item id
   * @param lore lines
   * @return true if updated
   */
  public boolean setLore(String id, List<String> lore) {
    CustomItemDefinition definition = definitions.get(normalizeId(id));
    if (definition == null) {
      return false;
    }
    definition.lore().clear();
    definition.lore().addAll(lore);
    saveDefinitions();
    return true;
  }

  /**
   * Sets one attribute on a definition.
   *
   * @param id item id
   * @param attributeId attribute id
   * @param value raw value
   * @return empty if success, or error message
   */
  public Optional<String> setDefinitionAttribute(String id, String attributeId, String value) {
    CustomItemDefinition definition = definitions.get(normalizeId(id));
    if (definition == null) {
      return Optional.of("Item id does not exist.");
    }
    String normalizedAttribute = normalizeId(attributeId);
    Optional<AttributeDefinition> attributeDefinition = AttributeCatalog.byId(normalizedAttribute);
    if (attributeDefinition.isEmpty()) {
      return Optional.of("Unknown attribute id.");
    }
    AttributeDefinition known = attributeDefinition.get();
    if (!known.valueType().isValid(value)) {
      return Optional.of("Invalid value for type " + known.valueType().name()
          + ". Example: " + known.valueType().example());
    }
    definition.attributes().put(normalizedAttribute, value);
    saveDefinitions();
    return Optional.empty();
  }

  /**
   * Deletes one attribute from a definition.
   *
   * @param id item id
   * @param attributeId attribute id
   * @return true if removed
   */
  public boolean removeDefinitionAttribute(String id, String attributeId) {
    CustomItemDefinition definition = definitions.get(normalizeId(id));
    if (definition == null) {
      return false;
    }
    String normalizedAttribute = normalizeId(attributeId);
    if (definition.attributes().remove(normalizedAttribute) == null) {
      return false;
    }
    saveDefinitions();
    return true;
  }

  /**
   * Builds one item stack from definition id.
   *
   * @param id item id
   * @param amount amount
   * @param owner optional owner
   * @return optional stack if definition exists
   */
  public Optional<ItemStack> createStack(String id, int amount, UUID owner) {
    CustomItemDefinition definition = definitions.get(normalizeId(id));
    if (definition == null) {
      return Optional.empty();
    }
    ItemStack stack = new ItemStack(definition.material(), Math.max(1, amount));
    ItemMeta meta = stack.getItemMeta();
    if (meta == null) {
      return Optional.empty();
    }
    Map<String, String> attributes = definition.attributes();
    meta.displayName(buildDisplayName(definition, attributes));
    List<Component> lore = new ArrayList<>();
    lore.add(Component.text("ID: " + definition.id(), NamedTextColor.DARK_GRAY));
    for (String line : definition.lore()) {
      lore.add(legacySerializer.deserialize(line));
    }
    addCustomLoreAttributes(attributes, lore);
    addRuntimeLore(attributes, meta.getPersistentDataContainer(), lore);
    meta.lore(lore);
    PersistentDataContainer container = meta.getPersistentDataContainer();
    container.set(itemIdKey, PersistentDataType.STRING, definition.id());
    if (owner != null && boolValue(attributes, "owner_bound")) {
      container.set(ownerUuidKey, PersistentDataType.STRING, owner.toString());
    }
    if (intValue(attributes, "durability").orElse(0) > 0) {
      container.set(usesLeftKey, PersistentDataType.INTEGER,
          intValue(attributes, "durability").orElse(0));
    }
    if (intValue(attributes, "limited_uses").orElse(0) > 0) {
      container.set(usesLeftKey, PersistentDataType.INTEGER,
          intValue(attributes, "limited_uses").orElse(0));
    }
    if (intValue(attributes, "energy_system").orElse(0) > 0) {
      container.set(energyLeftKey, PersistentDataType.INTEGER,
          intValue(attributes, "energy_system").orElse(0));
    }
    if (boolValue(attributes, "unbreakable_mode")) {
      meta.setUnbreakable(true);
    }
    intValue(attributes, "custom_model_data").ifPresent(meta::setCustomModelData);
    if (boolValue(attributes, "glow_effect")) {
      meta.setEnchantmentGlintOverride(true);
    }
    stack.setItemMeta(meta);
    return Optional.of(stack);
  }

  /**
   * Gets custom item id from a stack.
   *
   * @param stack stack
   * @return optional id
   */
  public Optional<String> customItemId(ItemStack stack) {
    ItemMeta meta = stack == null ? null : stack.getItemMeta();
    if (meta == null) {
      return Optional.empty();
    }
    String id = meta.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
    return Optional.ofNullable(id);
  }

  /**
   * Resolves active attributes for a stack by merging base definition and one-time values.
   *
   * @param stack stack
   * @return merged immutable map
   */
  public Map<String, String> resolveAttributes(ItemStack stack) {
    ItemMeta meta = stack == null ? null : stack.getItemMeta();
    if (meta == null) {
      return Map.of();
    }
    Map<String, String> merged = new LinkedHashMap<>();
    String id = meta.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
    if (id != null) {
      CustomItemDefinition definition = definitions.get(id);
      if (definition != null) {
        merged.putAll(definition.attributes());
      }
    }
    Map<String, String> oneTime = readOneTimeAttributes(stack);
    if (!oneTime.isEmpty()) {
      merged.putAll(oneTime);
    }
    if (merged.isEmpty()) {
      return Map.of();
    }
    return Collections.unmodifiableMap(merged);
  }

  /**
   * Adds or replaces one-time attribute values on a concrete stack.
   *
   * @param stack stack
   * @param attributeId attribute id
   * @param value raw value
   * @return empty if success, error if invalid
   */
  public Optional<String> setOneTimeAttribute(ItemStack stack, String attributeId, String value) {
    if (stack == null || stack.getType() == Material.AIR) {
      return Optional.of("Hold an item first.");
    }
    String normalizedAttribute = normalizeId(attributeId);
    Optional<AttributeDefinition> knownAttribute = AttributeCatalog.byId(normalizedAttribute);
    if (knownAttribute.isEmpty()) {
      return Optional.of("Unknown attribute id.");
    }
    if (!knownAttribute.get().valueType().isValid(value)) {
      return Optional.of("Invalid value for " + normalizedAttribute + ".");
    }
    Map<String, String> oneTime = new LinkedHashMap<>(readOneTimeAttributes(stack));
    oneTime.put(normalizedAttribute, value);
    ensureManagedTag(stack);
    writeOneTimeAttributes(stack, oneTime);
    refreshRuntimeLore(stack);
    return Optional.empty();
  }

  /**
   * Removes one one-time attribute from stack.
   *
   * @param stack stack
   * @param attributeId attribute id
   * @return true if removed
   */
  public boolean removeOneTimeAttribute(ItemStack stack, String attributeId) {
    Map<String, String> oneTime = new LinkedHashMap<>(readOneTimeAttributes(stack));
    if (oneTime.remove(normalizeId(attributeId)) == null) {
      return false;
    }
    writeOneTimeAttributes(stack, oneTime);
    refreshRuntimeLore(stack);
    return true;
  }

  /**
   * Clears one-time attributes from stack.
   *
   * @param stack stack
   */
  public void clearOneTimeAttributes(ItemStack stack) {
    ItemMeta meta = stack.getItemMeta();
    if (meta == null) {
      return;
    }
    meta.getPersistentDataContainer().remove(oneTimeAttributesKey);
    stack.setItemMeta(meta);
    refreshRuntimeLore(stack);
  }

  /**
   * Checks restrictions and optional permission gates before item actions.
   *
   * @param player actor
   * @param stack stack
   * @param target optional target entity
   * @param notify send denial messages
   * @return true if allowed
   */
  public boolean canUse(Player player, ItemStack stack, Entity target, boolean notify) {
    if (stack == null || stack.getType() == Material.AIR) {
      return false;
    }
    Map<String, String> attributes = resolveAttributes(stack);
    if (attributes.isEmpty()) {
      return false;
    }
    FileConfiguration config = plugin.getConfig();
    String globalPermission = config.getString("use-permission-node", "customitems.use");
    if (!player.isOp() && !player.hasPermission(globalPermission)) {
      if (notify) {
        player.sendMessage(color("&cYou do not have permission to use custom items."));
      }
      return false;
    }
    String neededPermission = attributes.get("required_permission");
    if (neededPermission != null && !neededPermission.isBlank()) {
      if (!player.isOp() && !player.hasPermission(neededPermission)) {
        if (notify) {
          player.sendMessage(color("&cYou need permission &f" + neededPermission));
        }
        return false;
      }
    }
    Optional<Integer> requiredLevel = intValue(attributes, "required_level");
    if (requiredLevel.isPresent() && player.getLevel() < requiredLevel.get()) {
      if (notify) {
        player.sendMessage(color("&cYou need level &f" + requiredLevel.get()));
      }
      return false;
    }
    String requiredWorld = attributes.get("required_world");
    if (requiredWorld != null && !requiredWorld.isBlank()) {
      if (!player.getWorld().getName().equalsIgnoreCase(requiredWorld.trim())) {
        if (notify) {
          player.sendMessage(color("&cThis item only works in &f" + requiredWorld));
        }
        return false;
      }
    }
    if (boolValue(attributes, "owner_bound")) {
      Optional<UUID> owner = readOwner(stack);
      if (owner.isPresent() && !owner.get().equals(player.getUniqueId())) {
        if (notify) {
          player.sendMessage(color("&cThis item is owner-bound."));
        }
        return false;
      }
    }
    if (boolValue(attributes, "pvp_only_mode")) {
      if (target == null || !(target instanceof Player)) {
        if (notify) {
          player.sendMessage(color("&cThis item only works in PvP."));
        }
        return false;
      }
    }
    if (boolValue(attributes, "pve_only_mode")) {
      if (target != null && target instanceof Player) {
        if (notify) {
          player.sendMessage(color("&cThis item only works against mobs."));
        }
        return false;
      }
    }
    return true;
  }

  /**
   * Tries to pass cooldown and charge checks for an action.
   *
   * @param player actor
   * @param stack stack
   * @param actionKey logical action key
   * @param notify show messages
   * @return true if action can execute now
   */
  public boolean canTriggerAction(Player player, ItemStack stack, String actionKey, boolean notify) {
    Map<String, String> attributes = resolveAttributes(stack);
    if (attributes.isEmpty()) {
      return false;
    }
    if (!passesCharge(player, actionKey, attributes, notify)) {
      return false;
    }
    return passesCooldown(player, actionKey, attributes, notify);
  }

  /**
   * Consumes energy or durability use cost.
   *
   * @param player actor
   * @param stack stack
   * @return true if cost was paid
   */
  public boolean consumeUse(Player player, ItemStack stack) {
    Map<String, String> attributes = resolveAttributes(stack);
    if (attributes.isEmpty() || boolValue(attributes, "unbreakable_mode")) {
      return true;
    }
    int cost = Math.max(1, intValue(attributes, "durability_cost_per_use").orElse(1));
    Optional<Integer> energyMax = intValue(attributes, "energy_system");
    if (energyMax.isPresent() && energyMax.get() > 0) {
      int energyLeft = readEnergy(stack).orElse(energyMax.get());
      if (energyLeft < cost) {
        player.sendMessage(color("&cNot enough energy."));
        return false;
      }
      writeEnergy(stack, energyLeft - cost);
      refreshRuntimeLore(stack);
      return true;
    }
    int maxUses = intValue(attributes, "durability")
        .orElse(intValue(attributes, "limited_uses").orElse(0));
    if (maxUses <= 0) {
      return true;
    }
    int usesLeft = readUsesLeft(stack).orElse(maxUses);
    usesLeft -= cost;
    if (usesLeft <= 0) {
      PlayerInventory inventory = player.getInventory();
      ItemStack mainHand = inventory.getItemInMainHand();
      if (mainHand.equals(stack)) {
        int nextAmount = mainHand.getAmount() - 1;
        if (nextAmount <= 0) {
          inventory.setItemInMainHand(new ItemStack(Material.AIR));
        } else {
          mainHand.setAmount(nextAmount);
          inventory.setItemInMainHand(mainHand);
        }
      }
      player.sendMessage(color("&cYour custom item has been consumed."));
      return false;
    }
    writeUsesLeft(stack, usesLeft);
    refreshRuntimeLore(stack);
    return true;
  }

  /**
   * Performs periodic regen for items in one player inventory.
   *
   * @param player player to update
   */
  public void tickRegen(Player player) {
    for (ItemStack stack : player.getInventory().getContents()) {
      if (stack == null || stack.getType() == Material.AIR || customItemId(stack).isEmpty()) {
        continue;
      }
      Map<String, String> attributes = resolveAttributes(stack);
      int maxUses = intValue(attributes, "durability")
          .orElse(intValue(attributes, "limited_uses").orElse(0));
      int maxEnergy = intValue(attributes, "energy_system").orElse(0);
      int usesGain = (int) Math.floor(doubleValue(attributes, "durability_regen").orElse(0.0));
      int energyGain = (int) Math.floor(doubleValue(attributes, "energy_regen_rate").orElse(0.0));
      boolean updated = false;
      if (maxUses > 0 && usesGain > 0) {
        int current = readUsesLeft(stack).orElse(maxUses);
        int next = Math.min(maxUses, current + usesGain);
        if (next != current) {
          writeUsesLeft(stack, next);
          updated = true;
        }
      }
      if (maxEnergy > 0 && energyGain > 0) {
        int current = readEnergy(stack).orElse(maxEnergy);
        int next = Math.min(maxEnergy, current + energyGain);
        if (next != current) {
          writeEnergy(stack, next);
          updated = true;
        }
      }
      if (updated) {
        refreshRuntimeLore(stack);
      }
    }
  }

  /**
   * Synchronizes all id-tagged items in a player inventory back to their definition metadata.
   *
   * @param player player whose inventory should be normalized
   */
  public void synchronizeInventory(Player player) {
    for (ItemStack stack : player.getInventory().getContents()) {
      if (stack == null || stack.getType() == Material.AIR) {
        continue;
      }
      synchronizeStack(stack, player.getUniqueId());
    }
    for (ItemStack stack : player.getInventory().getArmorContents()) {
      if (stack == null || stack.getType() == Material.AIR) {
        continue;
      }
      synchronizeStack(stack, player.getUniqueId());
    }
    ItemStack offhand = player.getInventory().getItemInOffHand();
    if (offhand.getType() != Material.AIR) {
      synchronizeStack(offhand, player.getUniqueId());
    }
  }

  /**
   * Forces one id-tagged stack back to configured material, display, lore and metadata.
   *
   * @param stack stack to normalize
   * @param holderId current holder id used as owner fallback
   * @return true when a definition-backed stack was synchronized
   */
  public boolean synchronizeStack(ItemStack stack, UUID holderId) {
    Optional<String> id = customItemId(stack);
    if (id.isEmpty()) {
      return false;
    }
    CustomItemDefinition definition = definitions.get(id.get());
    if (definition == null) {
      return false;
    }
    ItemMeta existingMeta = stack.getItemMeta();
    if (existingMeta == null) {
      return false;
    }
    Integer uses = existingMeta.getPersistentDataContainer()
        .get(usesLeftKey, PersistentDataType.INTEGER);
    Integer energy = existingMeta.getPersistentDataContainer()
        .get(energyLeftKey, PersistentDataType.INTEGER);
    String ownerRaw = existingMeta.getPersistentDataContainer()
        .get(ownerUuidKey, PersistentDataType.STRING);
    String oneTime = existingMeta.getPersistentDataContainer()
        .get(oneTimeAttributesKey, PersistentDataType.STRING);
    UUID ownerForRebuild = parseUuid(ownerRaw).orElse(holderId);
    Optional<ItemStack> rebuiltOptional = createStack(id.get(), stack.getAmount(), ownerForRebuild);
    if (rebuiltOptional.isEmpty()) {
      return false;
    }
    ItemStack rebuilt = rebuiltOptional.get();
    ItemMeta rebuiltMeta = rebuilt.getItemMeta();
    if (rebuiltMeta == null) {
      return false;
    }
    PersistentDataContainer dataContainer = rebuiltMeta.getPersistentDataContainer();
    if (uses != null) {
      dataContainer.set(usesLeftKey, PersistentDataType.INTEGER, uses);
    }
    if (energy != null) {
      dataContainer.set(energyLeftKey, PersistentDataType.INTEGER, energy);
    }
    if (ownerRaw != null) {
      dataContainer.set(ownerUuidKey, PersistentDataType.STRING, ownerRaw);
    }
    if (oneTime != null) {
      dataContainer.set(oneTimeAttributesKey, PersistentDataType.STRING, oneTime);
    }
    rebuilt.setItemMeta(rebuiltMeta);
    stack.setType(rebuilt.getType());
    stack.setAmount(rebuilt.getAmount());
    stack.setItemMeta(rebuilt.getItemMeta());
    return true;
  }

  /**
   * Lists one-time attributes attached to a concrete stack.
   *
   * @param stack stack
   * @return map of one-time values
   */
  public Map<String, String> oneTimeAttributes(ItemStack stack) {
    return readOneTimeAttributes(stack);
  }

  /**
   * Gets namespaced key used for id tags.
   *
   * @return id key
   */
  public NamespacedKey itemIdKey() {
    return itemIdKey;
  }

  /**
   * Parses a boolean attribute from resolved map.
   *
   * @param attributes merged attributes
   * @param key key
   * @return boolean value
   */
  public boolean boolValue(Map<String, String> attributes, String key) {
    String value = attributes.get(key);
    if (value == null) {
      return false;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return normalized.equals("true")
        || normalized.equals("yes")
        || normalized.equals("on")
        || normalized.equals("1");
  }

  /**
   * Parses integer attribute from resolved map.
   *
   * @param attributes merged attributes
   * @param key key
   * @return optional integer
   */
  public Optional<Integer> intValue(Map<String, String> attributes, String key) {
    String value = attributes.get(key);
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(Integer.parseInt(value.trim()));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  /**
   * Parses double attribute from resolved map.
   *
   * @param attributes merged attributes
   * @param key key
   * @return optional decimal
   */
  public Optional<Double> doubleValue(Map<String, String> attributes, String key) {
    String value = attributes.get(key);
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(Double.parseDouble(value.trim()));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private boolean passesCooldown(
      Player player,
      String actionKey,
      Map<String, String> attributes,
      boolean notify) {
    long cooldownMs = intValue(attributes, "cooldown_time")
        .orElse(plugin.getConfig().getInt("default-cooldown-ms", 1250));
    if (cooldownMs <= 0) {
      return true;
    }
    boolean perPlayer = boolValue(attributes, "cooldown_per_player");
    String key = actionKey + ":" + (perPlayer ? player.getUniqueId() : "global");
    long now = System.currentTimeMillis();
    Long readyAt = cooldowns.get(key);
    if (readyAt != null && readyAt > now) {
      if (notify) {
        long wait = readyAt - now;
        player.sendMessage(color("&cAbility cooldown: " + wait + "ms"));
      }
      return false;
    }
    cooldowns.put(key, now + cooldownMs);
    return true;
  }

  private boolean passesCharge(
      Player player,
      String actionKey,
      Map<String, String> attributes,
      boolean notify) {
    long chargeMs = intValue(attributes, "charge_time").orElse(0);
    if (chargeMs <= 0) {
      return true;
    }
    String key = actionKey + ":" + player.getUniqueId();
    long now = System.currentTimeMillis();
    Long startedAt = chargeStarts.get(key);
    if (startedAt == null) {
      chargeStarts.put(key, now);
      if (notify) {
        player.sendMessage(color("&7Charging started... use again to trigger."));
      }
      return false;
    }
    long elapsed = now - startedAt;
    if (elapsed < chargeMs) {
      if (notify) {
        player.sendMessage(color("&cCharge remaining: " + (chargeMs - elapsed) + "ms"));
      }
      return false;
    }
    chargeStarts.remove(key);
    return true;
  }

  private void writeUsesLeft(ItemStack stack, int uses) {
    updateMeta(stack, meta -> meta.getPersistentDataContainer()
        .set(usesLeftKey, PersistentDataType.INTEGER, Math.max(0, uses)));
  }

  private Optional<Integer> readUsesLeft(ItemStack stack) {
    ItemMeta meta = stack.getItemMeta();
    if (meta == null) {
      return Optional.empty();
    }
    Integer value = meta.getPersistentDataContainer().get(usesLeftKey, PersistentDataType.INTEGER);
    return Optional.ofNullable(value);
  }

  private void writeEnergy(ItemStack stack, int energy) {
    updateMeta(stack, meta -> meta.getPersistentDataContainer()
        .set(energyLeftKey, PersistentDataType.INTEGER, Math.max(0, energy)));
  }

  private Optional<Integer> readEnergy(ItemStack stack) {
    ItemMeta meta = stack.getItemMeta();
    if (meta == null) {
      return Optional.empty();
    }
    Integer value = meta.getPersistentDataContainer().get(energyLeftKey, PersistentDataType.INTEGER);
    return Optional.ofNullable(value);
  }

  private Optional<UUID> readOwner(ItemStack stack) {
    ItemMeta meta = stack.getItemMeta();
    if (meta == null) {
      return Optional.empty();
    }
    String owner = meta.getPersistentDataContainer().get(ownerUuidKey, PersistentDataType.STRING);
    if (owner == null || owner.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(owner));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  private Optional<UUID> parseUuid(String input) {
    if (input == null || input.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(input));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  private void refreshRuntimeLore(ItemStack stack) {
    Optional<String> id = customItemId(stack);
    if (id.isEmpty()) {
      return;
    }
    CustomItemDefinition definition = definitions.get(id.get());
    ItemMeta meta = stack.getItemMeta();
    if (meta == null) {
      return;
    }
    Map<String, String> mergedAttributes = resolveAttributes(stack);
    List<Component> lore = new ArrayList<>();
    lore.add(Component.text("ID: " + id.get(), NamedTextColor.DARK_GRAY));
    if (definition != null) {
      for (String line : definition.lore()) {
        lore.add(legacySerializer.deserialize(line));
      }
    }
    addCustomLoreAttributes(mergedAttributes, lore);
    addRuntimeLore(mergedAttributes, meta.getPersistentDataContainer(), lore);
    meta.lore(lore);
    stack.setItemMeta(meta);
  }

  private void ensureManagedTag(ItemStack stack) {
    updateMeta(stack, meta -> {
      PersistentDataContainer container = meta.getPersistentDataContainer();
      if (!container.has(itemIdKey, PersistentDataType.STRING)) {
        container.set(itemIdKey, PersistentDataType.STRING, "oneoff");
      }
    });
  }

  private void addCustomLoreAttributes(Map<String, String> attributes, List<Component> lore) {
    String customLore = attributes.get("custom_lore_lines");
    if (customLore != null && !customLore.isBlank()) {
      for (String split : customLore.split("\\|")) {
        lore.add(legacySerializer.deserialize(split));
      }
    }
    Map<String, String> visible = attributes.entrySet()
        .stream()
        .filter(entry -> !entry.getKey().equals("custom_lore_lines"))
        .filter(entry -> !entry.getKey().equals("animated_lore"))
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            Map.Entry::getValue,
            (left, right) -> right,
            LinkedHashMap::new));
    if (!visible.isEmpty()) {
      lore.add(Component.text("Attributes", NamedTextColor.GRAY));
      for (Map.Entry<String, String> entry : visible.entrySet()) {
        lore.add(Component.text("- " + entry.getKey() + ": " + entry.getValue(),
            NamedTextColor.DARK_GRAY));
      }
    }
  }

  private void addRuntimeLore(
      Map<String, String> attributes,
      PersistentDataContainer dataContainer,
      List<Component> lore) {
    int maxUses = intValue(attributes, "durability")
        .orElse(intValue(attributes, "limited_uses").orElse(0));
    if (maxUses > 0) {
      int uses = Optional.ofNullable(dataContainer.get(usesLeftKey, PersistentDataType.INTEGER))
          .orElse(maxUses);
      lore.add(Component.text("Uses: " + uses + "/" + maxUses, NamedTextColor.YELLOW));
    }
    int maxEnergy = intValue(attributes, "energy_system").orElse(0);
    if (maxEnergy > 0) {
      int energy = Optional.ofNullable(dataContainer.get(energyLeftKey, PersistentDataType.INTEGER))
          .orElse(maxEnergy);
      lore.add(Component.text("Energy: " + energy + "/" + maxEnergy, NamedTextColor.AQUA));
    }
    String owner = dataContainer.get(ownerUuidKey, PersistentDataType.STRING);
    if (owner != null) {
      lore.add(Component.text("Owner: " + owner, NamedTextColor.BLUE));
    }
  }

  private Component buildDisplayName(
      CustomItemDefinition definition,
      Map<String, String> attributes) {
    String raw = definition.displayName();
    String colorInput = attributes.get("custom_name_color");
    if (colorInput == null || colorInput.isBlank()) {
      return legacySerializer.deserialize(raw);
    }
    TextColor color = parseColor(colorInput);
    if (color == null) {
      return legacySerializer.deserialize(raw);
    }
    return Component.text(stripLegacy(raw), color);
  }

  private String stripLegacy(String raw) {
    return raw.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
  }

  private TextColor parseColor(String input) {
    String normalized = input.trim();
    if (normalized.matches("^#[0-9A-Fa-f]{6}$")) {
      return TextColor.fromHexString(normalized);
    }
    return switch (normalized.toLowerCase(Locale.ROOT)) {
      case "black" -> NamedTextColor.BLACK;
      case "dark_blue" -> NamedTextColor.DARK_BLUE;
      case "dark_green" -> NamedTextColor.DARK_GREEN;
      case "dark_aqua" -> NamedTextColor.DARK_AQUA;
      case "dark_red" -> NamedTextColor.DARK_RED;
      case "dark_purple" -> NamedTextColor.DARK_PURPLE;
      case "gold" -> NamedTextColor.GOLD;
      case "gray" -> NamedTextColor.GRAY;
      case "dark_gray" -> NamedTextColor.DARK_GRAY;
      case "blue" -> NamedTextColor.BLUE;
      case "green" -> NamedTextColor.GREEN;
      case "aqua" -> NamedTextColor.AQUA;
      case "red" -> NamedTextColor.RED;
      case "light_purple" -> NamedTextColor.LIGHT_PURPLE;
      case "yellow" -> NamedTextColor.YELLOW;
      case "white" -> NamedTextColor.WHITE;
      default -> null;
    };
  }

  private Map<String, String> readOneTimeAttributes(ItemStack stack) {
    ItemMeta meta = stack == null ? null : stack.getItemMeta();
    if (meta == null) {
      return Map.of();
    }
    String serialized = meta.getPersistentDataContainer()
        .get(oneTimeAttributesKey, PersistentDataType.STRING);
    if (serialized == null || serialized.isBlank()) {
      return Map.of();
    }
    Map<String, String> parsed = new LinkedHashMap<>();
    for (String pair : splitEscaped(serialized, ';')) {
      List<String> keyValue = splitEscaped(pair, '=');
      if (keyValue.size() != 2) {
        continue;
      }
      parsed.put(unescape(keyValue.get(0)), unescape(keyValue.get(1)));
    }
    return parsed;
  }

  private void writeOneTimeAttributes(ItemStack stack, Map<String, String> values) {
    updateMeta(stack, meta -> {
      if (values.isEmpty()) {
        meta.getPersistentDataContainer().remove(oneTimeAttributesKey);
        return;
      }
      String serialized = values.entrySet()
          .stream()
          .map(entry -> escape(entry.getKey()) + "=" + escape(entry.getValue()))
          .collect(Collectors.joining(";"));
      meta.getPersistentDataContainer()
          .set(oneTimeAttributesKey, PersistentDataType.STRING, serialized);
    });
  }

  private List<String> splitEscaped(String text, char delimiter) {
    List<String> split = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean escaped = false;
    for (char next : text.toCharArray()) {
      if (escaped) {
        current.append(next);
        escaped = false;
        continue;
      }
      if (next == '\\') {
        escaped = true;
        continue;
      }
      if (next == delimiter) {
        split.add(current.toString());
        current = new StringBuilder();
      } else {
        current.append(next);
      }
    }
    split.add(current.toString());
    return split;
  }

  private String escape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace("=", "\\=");
  }

  private String unescape(String value) {
    StringBuilder output = new StringBuilder();
    boolean escaped = false;
    for (char next : value.toCharArray()) {
      if (escaped) {
        output.append(next);
        escaped = false;
      } else if (next == '\\') {
        escaped = true;
      } else {
        output.append(next);
      }
    }
    return output.toString();
  }

  private void updateMeta(ItemStack stack, java.util.function.Consumer<ItemMeta> updater) {
    ItemMeta meta = stack.getItemMeta();
    if (meta == null) {
      return;
    }
    updater.accept(meta);
    if (meta instanceof Damageable damageable && boolValue(resolveAttributes(stack),
        "unbreakable_mode")) {
      damageable.setDamage(0);
    }
    stack.setItemMeta(meta);
  }

  private String normalizeId(String id) {
    return id.trim().toLowerCase(Locale.ROOT);
  }

  private String color(String text) {
    return ChatColor.translateAlternateColorCodes('&', text);
  }
}
