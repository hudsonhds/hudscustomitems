package com.hudscustomitems.customitems.attribute;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffectType;

/**
 * Value types accepted by custom item attributes.
 */
public enum AttributeValueType {
  BOOLEAN("true/false") {
    @Override
    public boolean isValid(String value) {
      String normalized = value.trim().toLowerCase();
      return normalized.equals("true")
          || normalized.equals("false")
          || normalized.equals("yes")
          || normalized.equals("no")
          || normalized.equals("on")
          || normalized.equals("off");
    }
  },
  INTEGER("whole number") {
    @Override
    public boolean isValid(String value) {
      try {
        Integer.parseInt(value.trim());
        return true;
      } catch (NumberFormatException ex) {
        return false;
      }
    }
  },
  DECIMAL("decimal number") {
    @Override
    public boolean isValid(String value) {
      try {
        Double.parseDouble(value.trim());
        return true;
      } catch (NumberFormatException ex) {
        return false;
      }
    }
  },
  TEXT("text") {
    @Override
    public boolean isValid(String value) {
      return !value.isBlank();
    }
  },
  MATERIAL("material") {
    @Override
    public boolean isValid(String value) {
      return parseMaterial(value) != null;
    }
  },
  MATERIAL_LIST("material,material,...") {
    @Override
    public boolean isValid(String value) {
      String[] split = value.split(",");
      if (split.length == 0) {
        return false;
      }
      for (String part : split) {
        if (parseMaterial(part) == null) {
          return false;
        }
      }
      return true;
    }
  },
  ENTITY_LIST("entity,entity,...") {
    @Override
    public boolean isValid(String value) {
      String[] split = value.split(",");
      if (split.length == 0) {
        return false;
      }
      for (String part : split) {
        try {
          EntityType.valueOf(part.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
          return false;
        }
      }
      return true;
    }
  },
  SOUND("sound key") {
    @Override
    public boolean isValid(String value) {
      try {
        Sound.valueOf(value.trim().toUpperCase());
        return true;
      } catch (IllegalArgumentException ex) {
        return false;
      }
    }
  },
  PARTICLE("particle key") {
    @Override
    public boolean isValid(String value) {
      String normalized = value.trim().toUpperCase();
      try {
        Particle.valueOf(normalized);
        return true;
      } catch (IllegalArgumentException ex) {
        String materialName = normalized.startsWith("BLOCK:")
            ? normalized.substring("BLOCK:".length())
            : normalized;
        Material material = parseMaterial(materialName);
        return material != null && material.isBlock();
      }
    }
  },
  POTION("potion effect key") {
    @Override
    public boolean isValid(String value) {
      try {
        return PotionEffectType.getByName(value.trim().toUpperCase()) != null;
      } catch (IllegalArgumentException ex) {
        return false;
      }
    }
  },
  COLOR("hex color or named color") {
    @Override
    public boolean isValid(String value) {
      String normalized = value.trim();
      if (normalized.matches("^#[0-9A-Fa-f]{6}$")) {
        return true;
      }
      return normalized.matches("^[a-zA-Z_]+$");
    }
  };

  private final String example;

  AttributeValueType(String example) {
    this.example = example;
  }

  public String example() {
    return example;
  }

  /**
   * Validates raw attribute input text.
   *
   * @param value raw text from commands or config
   * @return true if the text matches expected type
   */
  public abstract boolean isValid(String value);

  private static Material parseMaterial(String value) {
    try {
      return Material.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
