package com.hudscustomitems.customitems.storage;

import com.hudscustomitems.customitems.model.CustomItemDefinition;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Reads and writes custom item definitions to items.yml.
 */
public final class ItemStorage {
  private final JavaPlugin plugin;
  private final File file;

  /**
   * Creates a new storage helper.
   *
   * @param plugin owner plugin
   */
  public ItemStorage(JavaPlugin plugin) {
    this.plugin = plugin;
    this.file = new File(plugin.getDataFolder(), "items.yml");
    ensureFile();
  }

  /**
   * Loads all item definitions from disk.
   *
   * @return map keyed by id
   */
  public Map<String, CustomItemDefinition> loadDefinitions() {
    YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
    Map<String, CustomItemDefinition> loaded = new LinkedHashMap<>();
    ConfigurationSection itemSection = configuration.getConfigurationSection("items");
    if (itemSection == null) {
      return loaded;
    }
    for (String id : itemSection.getKeys(false)) {
      String base = "items." + id + ".";
      String materialName = configuration.getString(base + "material", "STONE");
      Material material = parseMaterial(materialName);
      if (material == null) {
        plugin.getLogger().warning("Skipping item '" + id + "', invalid material: " + materialName);
        continue;
      }
      String displayName = configuration.getString(base + "display-name", id);
      List<String> lore = configuration.getStringList(base + "lore");
      Map<String, String> attributes = new LinkedHashMap<>();
      ConfigurationSection attrsSection = configuration.getConfigurationSection(base + "attributes");
      if (attrsSection != null) {
        for (String attributeId : attrsSection.getKeys(false)) {
          attributes.put(attributeId, String.valueOf(attrsSection.get(attributeId)));
        }
      }
      String normalized = id.toLowerCase(Locale.ROOT);
      loaded.put(normalized,
          new CustomItemDefinition(normalized, material, displayName, lore, attributes));
    }
    return loaded;
  }

  /**
   * Saves all item definitions to disk.
   *
   * @param definitions map keyed by id
   */
  public void saveDefinitions(Map<String, CustomItemDefinition> definitions) {
    FileConfiguration configuration = new YamlConfiguration();
    for (CustomItemDefinition definition : definitions.values()) {
      String base = "items." + definition.id() + ".";
      configuration.set(base + "material", definition.material().name());
      configuration.set(base + "display-name", definition.displayName());
      configuration.set(base + "lore", definition.lore());
      for (Map.Entry<String, String> attribute : definition.attributes().entrySet()) {
        configuration.set(base + "attributes." + attribute.getKey(), attribute.getValue());
      }
    }
    try {
      configuration.save(file);
    } catch (IOException ex) {
      plugin.getLogger().severe("Failed to save items.yml: " + ex.getMessage());
    }
  }

  private void ensureFile() {
    if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
      plugin.getLogger().warning("Could not create plugin data folder.");
    }
    if (!file.exists()) {
      plugin.saveResource("items.yml", false);
    }
  }

  private Material parseMaterial(String name) {
    try {
      return Material.valueOf(name.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
