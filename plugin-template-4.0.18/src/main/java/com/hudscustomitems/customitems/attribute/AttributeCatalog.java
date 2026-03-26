package com.hudscustomitems.customitems.attribute;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Static registry of supported attributes.
 */
public final class AttributeCatalog {
  private static final Map<String, AttributeDefinition> ATTRIBUTES = new LinkedHashMap<>();

  static {
    register("tool_types", "Tool Type(s)", AttributeCategory.TOOL_BLOCK, AttributeValueType.TEXT,
        "Tool categories this item counts as, such as pickaxe+shovel.");
    register("mining_speed_multiplier", "Mining Speed Multiplier", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.DECIMAL, "Multiplies base mining speed.");
    register("break_radius", "Break Radius", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.INTEGER, "Breaks blocks around the target block, like 3x3.");
    register("break_volume", "Break Volume", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.TEXT, "Breaks a full cube volume such as 3x3x3.");
    register("directional_mining", "Directional Mining", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.BOOLEAN, "Mines in facing direction instead of centered.");
    register("vein_mining", "Vein Mining", AttributeCategory.TOOL_BLOCK, AttributeValueType.BOOLEAN,
        "Breaks connected blocks of the same type.");
    register("auto_smelt", "Auto Smelt", AttributeCategory.TOOL_BLOCK, AttributeValueType.BOOLEAN,
        "Automatically smelts mined drops.");
    register("silk_touch_mode", "Silk Touch Mode", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.BOOLEAN, "Forces silk-touch style drops.");
    register("fortune_mode", "Fortune Mode", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.DECIMAL, "Applies a fortune-style drop multiplier.");
    register("block_whitelist", "Block Whitelist", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.MATERIAL_LIST, "Item only works on listed blocks.");
    register("block_blacklist", "Block Blacklist", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.MATERIAL_LIST, "Item cannot break listed blocks.");
    register("harvest_level", "Harvest Level", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.INTEGER, "Minimum mining tier this item supports.");
    register("tree_feller", "Tree Feller", AttributeCategory.TOOL_BLOCK, AttributeValueType.BOOLEAN,
        "Breaking one log can break the full tree.");
    register("crop_harvester", "Crop Harvester", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.INTEGER, "Harvestes crops in an area.");
    register("auto_replant", "Auto Replant", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.BOOLEAN, "Automatically replants harvested crops.");
    register("till_radius", "Till Radius", AttributeCategory.TOOL_BLOCK, AttributeValueType.INTEGER,
        "Converts soil to farmland in an area.");
    register("block_placement_radius", "Block Placement Radius", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.TEXT, "Places blocks in a line, wall, or area pattern.");
    register("block_replace_mode", "Block Replace Mode", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.TEXT, "Replaces one block type with another.");
    register("block_drop_multiplier", "Block Drop Multiplier", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.DECIMAL, "Multiplies mined block drops.");
    register("no_block_drops", "No Block Drops", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.BOOLEAN, "Prevents drops when blocks break.");
    register("sell_container", "Sell Container", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.BOOLEAN,
        "Right click a container to sell its contents with hooked shop prices.");
    register("sell_container_on_click", "Sell Container on Click", AttributeCategory.TOOL_BLOCK,
        AttributeValueType.BOOLEAN,
        "Right click a container to sell its contents without breaking it.");

    register("attack_damage", "Attack Damage", AttributeCategory.COMBAT, AttributeValueType.DECIMAL,
        "Extra base damage dealt.");
    register("attack_speed", "Attack Speed", AttributeCategory.COMBAT, AttributeValueType.DECIMAL,
        "Adjusts attack cooldown speed.");
    register("critical_chance", "Critical Chance", AttributeCategory.COMBAT,
        AttributeValueType.DECIMAL, "Chance for critical hits.");
    register("critical_damage_multiplier", "Critical Damage Multiplier", AttributeCategory.COMBAT,
        AttributeValueType.DECIMAL, "Damage multiplier applied to crits.");
    register("knockback_strength", "Knockback Strength", AttributeCategory.COMBAT,
        AttributeValueType.DECIMAL, "Pushback strength on hit.");
    register("sweeping_radius", "Sweeping Radius", AttributeCategory.COMBAT,
        AttributeValueType.DECIMAL, "Radius for sweep style damage.");
    register("life_steal", "Life Steal", AttributeCategory.COMBAT, AttributeValueType.DECIMAL,
        "Heals attacker based on damage.");
    register("armor_penetration", "Armor Penetration", AttributeCategory.COMBAT,
        AttributeValueType.DECIMAL, "Ignores part of armor with bonus damage.");
    register("bonus_damage_vs_mobs", "Bonus Damage vs Mobs", AttributeCategory.COMBAT,
        AttributeValueType.DECIMAL, "Extra damage against non-player mobs.");
    register("bonus_damage_vs_players", "Bonus Damage vs Players", AttributeCategory.COMBAT,
        AttributeValueType.DECIMAL, "Extra damage in PvP.");
    register("burn_target", "Burn Target", AttributeCategory.COMBAT, AttributeValueType.INTEGER,
        "Sets hit targets on fire for N seconds.");
    register("freeze_target", "Freeze Target", AttributeCategory.COMBAT, AttributeValueType.INTEGER,
        "Applies freeze ticks to hit targets.");
    register("poison_target", "Poison Target", AttributeCategory.COMBAT, AttributeValueType.INTEGER,
        "Applies poison for N seconds.");
    register("chain_lightning", "Chain Lightning", AttributeCategory.COMBAT,
        AttributeValueType.INTEGER, "Hit jumps to nearby mobs.");
    register("projectile_launch", "Projectile Launch", AttributeCategory.COMBAT,
        AttributeValueType.TEXT, "Launches configured projectile from the item.");
    register("explosive_hit", "Explosive Hit", AttributeCategory.COMBAT, AttributeValueType.DECIMAL,
        "Creates explosion on hit.");
    register("pull_effect", "Pull Effect", AttributeCategory.COMBAT, AttributeValueType.DECIMAL,
        "Pulls nearby mobs toward attacker.");
    register("push_effect", "Push Effect", AttributeCategory.COMBAT, AttributeValueType.DECIMAL,
        "Knockback blast on hit.");
    register("multi_target_strike", "Multi Target Strike", AttributeCategory.COMBAT,
        AttributeValueType.DECIMAL, "Damages multiple entities on attack.");
    register("shield_breaker", "Shield Breaker", AttributeCategory.COMBAT,
        AttributeValueType.BOOLEAN, "Disables shields on hit.");

    register("health_bonus", "Health Bonus", AttributeCategory.PLAYER_BUFF,
        AttributeValueType.DECIMAL, "Adds max health while held.");
    register("speed_bonus", "Speed Bonus", AttributeCategory.PLAYER_BUFF,
        AttributeValueType.INTEGER, "Movement speed bonus level.");
    register("jump_boost", "Jump Boost", AttributeCategory.PLAYER_BUFF,
        AttributeValueType.INTEGER, "Jump height bonus level.");
    register("haste", "Haste", AttributeCategory.PLAYER_BUFF, AttributeValueType.INTEGER,
        "Mining speed potion level.");
    register("strength_boost", "Strength Boost", AttributeCategory.PLAYER_BUFF,
        AttributeValueType.INTEGER, "Damage potion level.");
    register("night_vision", "Night Vision", AttributeCategory.PLAYER_BUFF,
        AttributeValueType.BOOLEAN, "Night vision while holding item.");
    register("water_breathing", "Water Breathing", AttributeCategory.PLAYER_BUFF,
        AttributeValueType.BOOLEAN, "Water breathing while holding item.");
    register("fire_resistance", "Fire Resistance", AttributeCategory.PLAYER_BUFF,
        AttributeValueType.BOOLEAN, "Fire resistance while holding item.");
    register("regeneration", "Regeneration", AttributeCategory.PLAYER_BUFF,
        AttributeValueType.INTEGER, "Regeneration potion level.");
    register("absorption_hearts", "Absorption Hearts", AttributeCategory.PLAYER_BUFF,
        AttributeValueType.DECIMAL, "Temporary extra hearts while held.");

    register("teleport_on_right_click", "Teleport on Right Click", AttributeCategory.UTILITY,
        AttributeValueType.DECIMAL, "Teleports forward by configured distance.");
    register("dash_ability", "Dash Ability", AttributeCategory.UTILITY,
        AttributeValueType.DECIMAL, "Applies a forward dash.");
    register("double_jump", "Double Jump", AttributeCategory.UTILITY, AttributeValueType.INTEGER,
        "Second-jump power percent (100 = normal).");
    register("glide_mode", "Glide Mode", AttributeCategory.UTILITY, AttributeValueType.BOOLEAN,
        "Slows falling while holding item.");
    register("magnet_mode", "Magnet Mode", AttributeCategory.UTILITY, AttributeValueType.BOOLEAN,
        "Pulls nearby drops toward player.");
    register("auto_pickup", "Auto Pickup", AttributeCategory.UTILITY, AttributeValueType.BOOLEAN,
        "Sends nearby drops directly to inventory.");
    register("xp_magnet", "XP Magnet", AttributeCategory.UTILITY, AttributeValueType.BOOLEAN,
        "Pulls xp orbs toward player.");
    register("item_vacuum_radius", "Item Vacuum Radius", AttributeCategory.UTILITY,
        AttributeValueType.DECIMAL, "Pickup attraction range.");
    register("item_vacuum_pull_radius", "Item Vacuum Pull Radius", AttributeCategory.UTILITY,
        AttributeValueType.DECIMAL, "Pull strength toward the player.");
    register("block_highlighting", "Block Highlighting", AttributeCategory.UTILITY,
        AttributeValueType.BOOLEAN, "Highlights valuable blocks nearby.");
    register("mob_detection", "Mob Detection", AttributeCategory.UTILITY,
        AttributeValueType.BOOLEAN, "Shows nearby mobs through walls.");

    register("durability", "Durability", AttributeCategory.DURABILITY,
        AttributeValueType.INTEGER, "Total uses before item breaks.");
    register("durability_cost_per_use", "Durability Cost Per Use", AttributeCategory.DURABILITY,
        AttributeValueType.INTEGER, "Uses consumed per action.");
    register("durability_regen", "Durability Regen", AttributeCategory.DURABILITY,
        AttributeValueType.DECIMAL, "Uses restored per second.");
    register("unbreakable", "Unbreakable", AttributeCategory.DURABILITY,
        AttributeValueType.BOOLEAN, "Marks item unbreakable and preserves vanilla durability.");
    register("unbreakable_mode", "Unbreakable Mode", AttributeCategory.DURABILITY,
        AttributeValueType.BOOLEAN, "Prevents usage loss.");
    register("repair_material", "Repair Material", AttributeCategory.DURABILITY,
        AttributeValueType.MATERIAL, "Material used for repairs.");
    register("repair_amount", "Repair Amount", AttributeCategory.DURABILITY,
        AttributeValueType.INTEGER, "Uses restored per repair.");
    register("cooldown_time", "Cooldown Time", AttributeCategory.DURABILITY,
        AttributeValueType.INTEGER, "Action cooldown in milliseconds.");
    register("charge_time", "Charge Time", AttributeCategory.DURABILITY, AttributeValueType.INTEGER,
        "Milliseconds needed before use.");
    register("energy_system", "Energy System", AttributeCategory.DURABILITY,
        AttributeValueType.INTEGER, "Max custom energy pool.");
    register("energy_regen_rate", "Energy Regen Rate", AttributeCategory.DURABILITY,
        AttributeValueType.DECIMAL, "Energy restored per second.");

    register("custom_model_data", "Custom Model Data", AttributeCategory.VISUAL,
        AttributeValueType.INTEGER, "Custom model data integer.");
    register("glow_effect", "Glow Effect", AttributeCategory.VISUAL, AttributeValueType.BOOLEAN,
        "Enchanted style glow.");
    register("particle_trail", "Particle Trail", AttributeCategory.VISUAL,
        AttributeValueType.PARTICLE, "Particles while held.");
    register("swing_particles", "Swing Particles", AttributeCategory.VISUAL,
        AttributeValueType.PARTICLE, "Particles on attack animation.");
    register("mining_particles", "Mining Particles", AttributeCategory.VISUAL,
        AttributeValueType.PARTICLE, "Particles when mining blocks.");
    register("sound_on_use", "Sound on Use", AttributeCategory.VISUAL, AttributeValueType.SOUND,
        "Sound played on right click use.");
    register("sound_on_hit", "Sound on Hit", AttributeCategory.VISUAL, AttributeValueType.SOUND,
        "Sound played on combat hit.");
    register("custom_name_color", "Custom Name Color", AttributeCategory.VISUAL,
        AttributeValueType.COLOR, "Name color in hex or named value.");
    register("custom_lore_lines", "Custom Lore Lines", AttributeCategory.VISUAL,
        AttributeValueType.TEXT, "Lore lines split with |.");
    register("animated_lore", "Animated Lore", AttributeCategory.VISUAL, AttributeValueType.TEXT,
        "Animated lore states split with |.");
    register("enchantments", "Enchantments", AttributeCategory.SPECIAL, AttributeValueType.TEXT,
        "Comma list like sharpness:255,unbreaking:255 with max level 255.");

    register("right_click_ability", "Right Click Ability", AttributeCategory.SPECIAL,
        AttributeValueType.TEXT, "Named ability action for right click.");
    register("shift_ability", "Shift Ability", AttributeCategory.SPECIAL, AttributeValueType.TEXT,
        "Named ability action while sneaking.");
    register("combo_system", "Combo System", AttributeCategory.SPECIAL, AttributeValueType.INTEGER,
        "Trigger after N consecutive hits.");
    register("charge_attack", "Charge Attack", AttributeCategory.SPECIAL,
        AttributeValueType.DECIMAL, "Damage multiplier for charged attacks.");
    register("area_ability", "Area Ability", AttributeCategory.SPECIAL,
        AttributeValueType.DECIMAL, "Area radius for special skill.");
    register("summon_entity", "Summon Entity", AttributeCategory.SPECIAL,
        AttributeValueType.TEXT, "Entity type to summon.");
    register("block_ability", "Block Ability", AttributeCategory.SPECIAL,
        AttributeValueType.INTEGER, "Temporary defense effect duration.");
    register("time_slow_ability", "Time Slow Ability", AttributeCategory.SPECIAL,
        AttributeValueType.INTEGER, "Slowness duration around player.");
    register("gravity_pull", "Gravity Pull", AttributeCategory.SPECIAL,
        AttributeValueType.DECIMAL, "Pull entities to target point.");
    register("explosion_ability", "Explosion Ability", AttributeCategory.SPECIAL,
        AttributeValueType.DECIMAL, "Right click explosion power.");

    register("required_permission", "Required Permission", AttributeCategory.RESTRICTION,
        AttributeValueType.TEXT, "Permission needed to use this item.");
    register("required_level", "Required Level", AttributeCategory.RESTRICTION,
        AttributeValueType.INTEGER, "Minimum xp level required.");
    register("required_world", "Required World", AttributeCategory.RESTRICTION,
        AttributeValueType.TEXT, "World name this item works in.");
    register("pvp_only_mode", "PvP Only Mode", AttributeCategory.RESTRICTION,
        AttributeValueType.BOOLEAN, "Only active against players.");
    register("pve_only_mode", "PvE Only Mode", AttributeCategory.RESTRICTION,
        AttributeValueType.BOOLEAN, "Only active against mobs.");
    register("cooldown_per_player", "Cooldown Per Player", AttributeCategory.RESTRICTION,
        AttributeValueType.BOOLEAN, "Tracks cooldowns by player.");
    register("limited_uses", "Limited Uses", AttributeCategory.RESTRICTION,
        AttributeValueType.INTEGER, "Item disappears after this many uses.");
    register("owner_bound", "Owner Bound", AttributeCategory.RESTRICTION,
        AttributeValueType.BOOLEAN, "Only original owner can use.");
    register("soulbound", "Soulbound", AttributeCategory.RESTRICTION,
        AttributeValueType.BOOLEAN, "Cannot be dropped or traded.");
    register("infinite", "Infinite", AttributeCategory.RESTRICTION,
        AttributeValueType.BOOLEAN,
        "Allows normal usage while preventing bundle transfer.");
    register("prevent_placement", "Prevent Placement", AttributeCategory.RESTRICTION,
        AttributeValueType.BOOLEAN, "Prevents this custom item from being placed as a block.");
    register("prevent_eating", "Prevent Eating", AttributeCategory.RESTRICTION,
        AttributeValueType.BOOLEAN, "Prevents this custom item from being eaten.");
    register("prevent_feeding", "Prevent Feeding", AttributeCategory.RESTRICTION,
        AttributeValueType.BOOLEAN, "Prevents this custom item from being fed to animals.");
    register("prevent_bundle", "Prevent Bundle", AttributeCategory.RESTRICTION,
        AttributeValueType.BOOLEAN, "Prevents this custom item from being used with bundles.");
    register("upgradeable", "Upgradeable", AttributeCategory.RESTRICTION,
        AttributeValueType.BOOLEAN, "Can gain new levels and stats.");
  }

  private AttributeCatalog() {
  }

  /**
   * Gets one attribute definition by id.
   *
   * @param id attribute id
   * @return optional definition
   */
  public static Optional<AttributeDefinition> byId(String id) {
    return Optional.ofNullable(ATTRIBUTES.get(id.toLowerCase(Locale.ROOT)));
  }

  /**
   * Returns all definitions in registration order.
   *
   * @return immutable list of all definitions
   */
  public static List<AttributeDefinition> definitions() {
    return List.copyOf(ATTRIBUTES.values());
  }

  /**
   * Lists all attribute ids.
   *
   * @return immutable list of ids
   */
  public static List<String> ids() {
    return List.copyOf(ATTRIBUTES.keySet());
  }

  /**
   * Produces grouped definitions for command output.
   *
   * @return grouped immutable map
   */
  public static Map<AttributeCategory, List<AttributeDefinition>> groupedByCategory() {
    Map<AttributeCategory, List<AttributeDefinition>> grouped = new LinkedHashMap<>();
    for (AttributeCategory category : AttributeCategory.values()) {
      grouped.put(category, new ArrayList<>());
    }
    for (AttributeDefinition definition : ATTRIBUTES.values()) {
      grouped.get(definition.category()).add(definition);
    }
    Map<AttributeCategory, List<AttributeDefinition>> immutable = new LinkedHashMap<>();
    for (Map.Entry<AttributeCategory, List<AttributeDefinition>> entry : grouped.entrySet()) {
      immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(immutable);
  }

  /**
   * Returns only ids matching a partial prefix.
   *
   * @param prefix typed prefix
   * @return matching ids
   */
  public static Collection<String> suggestIds(String prefix) {
    String normalized = prefix.toLowerCase(Locale.ROOT);
    return ATTRIBUTES.keySet()
        .stream()
        .filter(key -> key.startsWith(normalized))
        .collect(Collectors.toList());
  }

  private static void register(
      String id,
      String displayName,
      AttributeCategory category,
      AttributeValueType type,
      String description) {
    ATTRIBUTES.put(id, new AttributeDefinition(id, displayName, category, type, description));
  }
}
