package com.hudscustomitems.customitems.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Updates config.yml by adding missing defaults while preserving existing values.
 */
public final class ConfigUpdater {
  private static final int largeChangeThreshold = 10;
  private final JavaPlugin plugin;

  /**
   * Creates a new config updater.
   *
   * @param plugin owner plugin
   */
  public ConfigUpdater(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  /**
   * Applies missing defaults to config.yml and reloads active config.
   *
   * @return update summary
   */
  public UpdateSummary updateAndReload() {
    File configFile = new File(plugin.getDataFolder(), "config.yml");
    if (!configFile.exists()) {
      plugin.saveDefaultConfig();
      plugin.reloadConfig();
      return new UpdateSummary(false, 0, false, false, null);
    }
    YamlConfiguration current = YamlConfiguration.loadConfiguration(configFile);
    Optional<YamlConfiguration> defaults = loadBundledDefaults();
    if (defaults.isEmpty()) {
      plugin.reloadConfig();
      return new UpdateSummary(false, 0, false, false, null);
    }
    boolean versionChanged = isVersionChanged(current, defaults.get());
    int addedKeys = addMissingDefaults(current, defaults.get());
    boolean changed = addedKeys > 0;
    boolean largeChange = addedKeys >= largeChangeThreshold || versionChanged;
    String backupPath = null;
    if (changed && largeChange) {
      backupPath = backupConfig(configFile).orElse(null);
    }
    if (changed) {
      try {
        current.save(configFile);
      } catch (IOException ex) {
        plugin.getLogger().warning("Failed to save merged config.yml: " + ex.getMessage());
      }
    }
    plugin.reloadConfig();
    return new UpdateSummary(changed, addedKeys, largeChange, versionChanged, backupPath);
  }

  private Optional<YamlConfiguration> loadBundledDefaults() {
    try (InputStream stream = plugin.getResource("config.yml")) {
      if (stream == null) {
        return Optional.empty();
      }
      InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
      return Optional.of(YamlConfiguration.loadConfiguration(reader));
    } catch (IOException ex) {
      plugin.getLogger().warning("Failed to read bundled config.yml: " + ex.getMessage());
      return Optional.empty();
    }
  }

  private boolean isVersionChanged(YamlConfiguration current, YamlConfiguration defaults) {
    String currentVersion = Objects.toString(current.get("config-version"), "");
    String defaultVersion = Objects.toString(defaults.get("config-version"), "");
    return !currentVersion.equals(defaultVersion);
  }

  private int addMissingDefaults(YamlConfiguration current, YamlConfiguration defaults) {
    int added = 0;
    for (String path : defaults.getKeys(true)) {
      if (defaults.isConfigurationSection(path) || current.contains(path)) {
        continue;
      }
      current.set(path, defaults.get(path));
      added++;
    }
    return added;
  }

  private Optional<String> backupConfig(File configFile) {
    File backupDir = new File(plugin.getDataFolder(), "config-backups");
    if (!backupDir.exists() && !backupDir.mkdirs()) {
      plugin.getLogger().warning("Could not create config-backups directory.");
      return Optional.empty();
    }
    SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT);
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    String fileName = "config-" + format.format(new Date()) + ".yml";
    File backupFile = new File(backupDir, fileName);
    try {
      Files.copy(configFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
      return Optional.of("config-backups/" + fileName);
    } catch (IOException ex) {
      plugin.getLogger().warning("Failed to create config backup: " + ex.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Result of one config update pass.
   *
   * @param changed true if keys were added
   * @param addedKeys number of keys added
   * @param largeChange true when backup criteria was met
   * @param versionChanged true if config-version differs from bundled default
   * @param backupPath backup path relative to plugin folder, null when not created
   */
  public record UpdateSummary(
      boolean changed,
      int addedKeys,
      boolean largeChange,
      boolean versionChanged,
      String backupPath) {
  }
}
