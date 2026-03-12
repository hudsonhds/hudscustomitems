package com.hudscustomitems.customitems.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;

/**
 * Serializable definition of one custom item id.
 */
public final class CustomItemDefinition {
  private final String id;
  private Material material;
  private String displayName;
  private final List<String> lore;
  private final Map<String, String> attributes;

  /**
   * Creates a custom item definition.
   *
   * @param id unique id
   * @param material display material
   * @param displayName display name with color codes if needed
   * @param lore custom lore lines
   * @param attributes attribute map
   */
  public CustomItemDefinition(
      String id,
      Material material,
      String displayName,
      List<String> lore,
      Map<String, String> attributes) {
    this.id = id;
    this.material = material;
    this.displayName = displayName;
    this.lore = new ArrayList<>(lore);
    this.attributes = new LinkedHashMap<>(attributes);
  }

  public String id() {
    return id;
  }

  public Material material() {
    return material;
  }

  public void setMaterial(Material material) {
    this.material = material;
  }

  public String displayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public List<String> lore() {
    return lore;
  }

  public Map<String, String> attributes() {
    return attributes;
  }
}
