package com.hudscustomitems.customitems.service;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Sells full container inventories using hooked shop providers and Vault deposits.
 */
public final class SellContainerService {
  private final JavaPlugin plugin;
  private final VaultEconomyBridge vaultEconomyBridge;
  private final DecimalFormat moneyFormat;
  private final List<SellPriceProvider> priceProviders;

  /**
   * Creates a new sell container service.
   *
   * @param plugin owner plugin
   */
  public SellContainerService(JavaPlugin plugin) {
    this.plugin = plugin;
    this.vaultEconomyBridge = new VaultEconomyBridge(plugin);
    this.moneyFormat = new DecimalFormat("#,##0.00");
    this.priceProviders = createProviderChain();
  }

  /**
   * Sells everything in a container inventory if sell prices are available.
   *
   * @param player seller
   * @param clickedBlock clicked block
   * @return detailed outcome for user messaging
   */
  public SellOutcome sellContainer(Player player, Block clickedBlock) {
    BlockState state = clickedBlock.getState();
    if (!(state instanceof InventoryHolder holder)) {
      return SellOutcome.notHandled();
    }
    Inventory inventory = holder.getInventory();
    if (inventory == null) {
      return SellOutcome.handledFailure("That container has no inventory.");
    }
    if (!vaultEconomyBridge.available()) {
      return SellOutcome.handledFailure("Vault economy is not available.");
    }

    List<SoldStack> sold = new ArrayList<>();
    int totalSoldItems = 0;
    double totalEarnings = 0.0;
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      ItemStack stack = inventory.getItem(slot);
      if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) {
        continue;
      }
      Optional<PriceQuote> quote = quoteSellValue(player, stack);
      if (quote.isEmpty()) {
        continue;
      }
      double unitPrice = quote.get().unitPrice();
      if (unitPrice <= 0.0) {
        continue;
      }
      int amount = stack.getAmount();
      double stackValue = unitPrice * amount;
      sold.add(new SoldStack(slot, amount, stackValue, quote.get().providerName()));
      totalSoldItems += amount;
      totalEarnings += stackValue;
    }

    if (sold.isEmpty()) {
      return SellOutcome.handledFailure("No sellable items were found in this container.");
    }
    if (!vaultEconomyBridge.deposit(player, totalEarnings)) {
      return SellOutcome.handledFailure("Sale failed because Vault could not deposit funds.");
    }
    for (SoldStack soldStack : sold) {
      inventory.setItem(soldStack.slot(), null);
    }
    if (state instanceof Container container) {
      container.update(true, false);
    }
    Map<String, Double> providerBreakdown = new LinkedHashMap<>();
    for (SoldStack soldStack : sold) {
      providerBreakdown.merge(soldStack.providerName(), soldStack.stackValue(), Double::sum);
    }
    String moneyText = vaultEconomyBridge.format(totalEarnings)
        .orElse("$" + moneyFormat.format(totalEarnings));
    StringBuilder summary = new StringBuilder();
    summary.append("Sold ")
        .append(totalSoldItems)
        .append(" items for ")
        .append(moneyText)
        .append('.');
    if (!providerBreakdown.isEmpty()) {
      summary.append(" Source: ");
      List<String> parts = new ArrayList<>();
      for (Map.Entry<String, Double> entry : providerBreakdown.entrySet()) {
        parts.add(entry.getKey() + "=" + moneyFormat.format(entry.getValue()));
      }
      summary.append(String.join(", ", parts));
    }
    return SellOutcome.handledSuccess(totalSoldItems, totalEarnings, providerBreakdown,
        summary.toString());
  }

  private Optional<PriceQuote> quoteSellValue(Player player, ItemStack stack) {
    for (SellPriceProvider provider : configuredPriceProviders()) {
      if (!provider.available()) {
        continue;
      }
      OptionalDouble price = provider.sellPrice(player, stack);
      if (price.isPresent() && price.getAsDouble() > 0.0) {
        return Optional.of(new PriceQuote(provider.name(), price.getAsDouble()));
      }
    }
    return Optional.empty();
  }

  private List<SellPriceProvider> createProviderChain() {
    List<SellPriceProvider> chain = new ArrayList<>();
    chain.add(new ReflectionProvider(
        plugin,
        "EconomyShopGUI",
        List.of("EconomyShopGUI"),
        List.of("me.gypopo.economyshopgui.api.EconomyShopGUIHook"),
        List.of("getSellPrice", "getItemStackSellPrice", "getPrice")));
    chain.add(new ReflectionProvider(
        plugin,
        "ShopGUIPlus",
        List.of("ShopGUIPlus", "ShopGUI+"),
        List.of("net.brcdev.shopgui.ShopGuiPlusApi"),
        List.of("getItemStackPriceSell", "getSellPrice", "getPrice")));
    chain.add(new ReflectionProvider(
        plugin,
        "QuickShop",
        List.of("QuickShop"),
        List.of("com.ghostchu.quickshop.api.QuickShopAPI", "com.ghostchu.quickshop.api.QuickShop"),
        List.of("getSellPrice", "getPrice", "getWorth")));
    chain.add(new ReflectionProvider(
        plugin,
        "ChestShop",
        List.of("ChestShop"),
        List.of("com.Acrobot.ChestShop.API", "com.Acrobot.ChestShop.ChestShop"),
        List.of("getSellPrice", "getPrice", "getWorth")));
    chain.add(new ReflectionProvider(
        plugin,
        "Shopkeepers",
        List.of("Shopkeepers"),
        List.of("com.nisovin.shopkeepers.api.ShopkeepersAPI"),
        List.of("getSellPrice", "getPrice", "getWorth")));
    chain.add(new ReflectionProvider(
        plugin,
        "BossShopPro",
        List.of("BossShopPro"),
        List.of("org.black_ixx.bossshop.BossShop"),
        List.of("getSellPrice", "getPrice", "getWorth")));
    chain.add(new ReflectionProvider(
        plugin,
        "UltimateShop",
        List.of("UltimateShop"),
        List.of("com.songoda.ultimateshop.UltimateShop"),
        List.of("getSellPrice", "getPrice", "getWorth")));
    chain.add(new ReflectionProvider(
        plugin,
        "DynamicShop",
        List.of("DynamicShop"),
        List.of("me.sat7.dynamicshop.DynamicShop"),
        List.of("getSellPrice", "getPrice", "getWorth")));
    chain.add(new EssentialsWorthProvider(plugin, "EssentialsX", List.of("Essentials", "EssentialsX")));
    chain.add(new ReflectionProvider(
        plugin,
        "GUIShop",
        List.of("GUIShop"),
        List.of("org.popcraft.guishop.GuiShop", "com.epicnicity322.guishop.GuiShop"),
        List.of("getSellPrice", "getPrice", "getWorth")));
    chain.add(new ConfigWorthProvider(plugin, "Vault"));
    return chain;
  }

  private List<SellPriceProvider> configuredPriceProviders() {
    String forcedProvider = plugin.getConfig().getString("sell-container.provider", "auto");
    if (forcedProvider != null
        && !forcedProvider.isBlank()
        && !forcedProvider.equalsIgnoreCase("auto")) {
      List<SellPriceProvider> single = new ArrayList<>();
      for (SellPriceProvider provider : priceProviders) {
        if (provider.name().equalsIgnoreCase(forcedProvider.trim())) {
          single.add(provider);
          return single;
        }
      }
      return List.of();
    }
    List<String> configuredOrder = plugin.getConfig().getStringList("sell-container.provider-order");
    if (configuredOrder.isEmpty()) {
      return priceProviders;
    }
    Map<String, SellPriceProvider> byName = new LinkedHashMap<>();
    for (SellPriceProvider provider : priceProviders) {
      byName.put(provider.name().toLowerCase(Locale.ROOT), provider);
    }
    List<SellPriceProvider> ordered = new ArrayList<>();
    for (String providerName : configuredOrder) {
      SellPriceProvider matched = byName.remove(providerName.toLowerCase(Locale.ROOT).trim());
      if (matched != null) {
        ordered.add(matched);
      }
    }
    ordered.addAll(byName.values());
    return ordered;
  }

  private record SoldStack(int slot, int amount, double stackValue, String providerName) {
  }

  private record PriceQuote(String providerName, double unitPrice) {
  }

  /**
   * Immutable sell action result.
   *
   * @param handled true when block was a container and sale action was attempted
   * @param success true when sale completed
   * @param soldItems item amount sold
   * @param totalValue total money value
   * @param providerBreakdown totals by provider
   * @param message player-facing summary
   */
  public record SellOutcome(
      boolean handled,
      boolean success,
      int soldItems,
      double totalValue,
      Map<String, Double> providerBreakdown,
      String message) {
    static SellOutcome notHandled() {
      return new SellOutcome(false, false, 0, 0.0, Map.of(), "");
    }

    static SellOutcome handledFailure(String message) {
      return new SellOutcome(true, false, 0, 0.0, Map.of(), message);
    }

    static SellOutcome handledSuccess(
        int soldItems,
        double totalValue,
        Map<String, Double> providerBreakdown,
        String message) {
      return new SellOutcome(true, true, soldItems, totalValue, Map.copyOf(providerBreakdown),
          message);
    }
  }

  private interface SellPriceProvider {
    boolean available();

    String name();

    OptionalDouble sellPrice(Player player, ItemStack stack);
  }

  private static final class ReflectionProvider implements SellPriceProvider {
    private final JavaPlugin plugin;
    private final String providerName;
    private final List<String> pluginNames;
    private final List<String> classCandidates;
    private final List<String> methodCandidates;

    ReflectionProvider(
        JavaPlugin plugin,
        String providerName,
        List<String> pluginNames,
        List<String> classCandidates,
        List<String> methodCandidates) {
      this.plugin = plugin;
      this.providerName = providerName;
      this.pluginNames = pluginNames;
      this.classCandidates = classCandidates;
      this.methodCandidates = methodCandidates;
    }

    @Override
    public boolean available() {
      return findPlugin().isPresent();
    }

    @Override
    public String name() {
      return providerName;
    }

    @Override
    public OptionalDouble sellPrice(Player player, ItemStack stack) {
      Optional<Plugin> pluginInstance = findPlugin();
      if (pluginInstance.isEmpty()) {
        return OptionalDouble.empty();
      }
      for (String className : classCandidates) {
        Optional<Class<?>> found = classForName(className);
        if (found.isEmpty()) {
          continue;
        }
        OptionalDouble staticResult = invokePriceMethod(found.get(), null, player, stack);
        if (staticResult.isPresent()) {
          return staticResult;
        }
      }
      OptionalDouble pluginResult = invokePriceMethod(
          pluginInstance.get().getClass(),
          pluginInstance.get(),
          player,
          stack);
      return pluginResult.isPresent() ? pluginResult : OptionalDouble.empty();
    }

    private OptionalDouble invokePriceMethod(
        Class<?> targetClass,
        Object target,
        Player player,
        ItemStack stack) {
      for (Method method : targetClass.getMethods()) {
        String methodName = method.getName().toLowerCase(Locale.ROOT);
        boolean candidateName = methodCandidates
            .stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .anyMatch(methodName::contains);
        if (!candidateName) {
          continue;
        }
        Optional<Object> result = invokeIfSupported(method, target, player, stack);
        if (result.isEmpty()) {
          continue;
        }
        OptionalDouble extracted = extractPrice(result.get());
        if (extracted.isPresent() && extracted.getAsDouble() > 0.0) {
          return extracted;
        }
      }
      return OptionalDouble.empty();
    }

    private Optional<Object> invokeIfSupported(
        Method method,
        Object target,
        Player player,
        ItemStack stack) {
      Class<?>[] parameters = method.getParameterTypes();
      try {
        if (parameters.length == 2
            && Player.class.isAssignableFrom(parameters[0])
            && ItemStack.class.isAssignableFrom(parameters[1])) {
          return Optional.ofNullable(method.invoke(target, player, stack));
        }
        if (parameters.length == 2
            && ItemStack.class.isAssignableFrom(parameters[0])
            && Player.class.isAssignableFrom(parameters[1])) {
          return Optional.ofNullable(method.invoke(target, stack, player));
        }
        if (parameters.length == 1 && ItemStack.class.isAssignableFrom(parameters[0])) {
          return Optional.ofNullable(method.invoke(target, stack));
        }
        if (parameters.length == 2
            && Material.class.isAssignableFrom(parameters[0])
            && parameters[1] == int.class) {
          return Optional.ofNullable(method.invoke(target, stack.getType(), stack.getAmount()));
        }
        if (parameters.length == 1 && Material.class.isAssignableFrom(parameters[0])) {
          return Optional.ofNullable(method.invoke(target, stack.getType()));
        }
      } catch (ReflectiveOperationException ex) {
        return Optional.empty();
      }
      return Optional.empty();
    }

    private Optional<Plugin> findPlugin() {
      for (String pluginName : pluginNames) {
        Plugin found = Bukkit.getPluginManager().getPlugin(pluginName);
        if (found != null && found.isEnabled()) {
          return Optional.of(found);
        }
      }
      return Optional.empty();
    }

    private Optional<Class<?>> classForName(String className) {
      try {
        return Optional.of(Class.forName(className));
      } catch (ClassNotFoundException ex) {
        return Optional.empty();
      }
    }
  }

  private static final class EssentialsWorthProvider implements SellPriceProvider {
    private final JavaPlugin plugin;
    private final String providerName;
    private final List<String> pluginNames;
    private final Map<String, Double> worthValues;
    private long lastLoadMs;
    private long lastModified;

    EssentialsWorthProvider(JavaPlugin plugin, String providerName, List<String> pluginNames) {
      this.plugin = plugin;
      this.providerName = providerName;
      this.pluginNames = pluginNames;
      this.worthValues = new HashMap<>();
      this.lastLoadMs = 0L;
      this.lastModified = 0L;
    }

    @Override
    public boolean available() {
      return findEssentialsDataFolder().isPresent();
    }

    @Override
    public String name() {
      return providerName;
    }

    @Override
    public OptionalDouble sellPrice(Player player, ItemStack stack) {
      Optional<File> worthFile = findEssentialsWorthFile();
      if (worthFile.isEmpty()) {
        return OptionalDouble.empty();
      }
      reloadIfNeeded(worthFile.get());
      Double value = worthValues.get(normalizeMaterialKey(stack.getType().name()));
      return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    private Optional<File> findEssentialsWorthFile() {
      Optional<File> folder = findEssentialsDataFolder();
      if (folder.isEmpty()) {
        return Optional.empty();
      }
      File worthFile = new File(folder.get(), "worth.yml");
      return worthFile.exists() ? Optional.of(worthFile) : Optional.empty();
    }

    private Optional<File> findEssentialsDataFolder() {
      for (String pluginName : pluginNames) {
        Plugin found = Bukkit.getPluginManager().getPlugin(pluginName);
        if (found != null && found.isEnabled()) {
          return Optional.of(found.getDataFolder());
        }
      }
      return Optional.empty();
    }

    private void reloadIfNeeded(File worthFile) {
      long now = System.currentTimeMillis();
      if (now - lastLoadMs < 5000L && worthFile.lastModified() == lastModified) {
        return;
      }
      worthValues.clear();
      YamlConfiguration configuration = YamlConfiguration.loadConfiguration(worthFile);
      ConfigurationSection section = configuration.getConfigurationSection("worth");
      ConfigurationSection source = section == null ? configuration : section;
      for (String key : source.getKeys(true)) {
        Object value = source.get(key);
        if (value instanceof Number number) {
          worthValues.put(normalizeMaterialKey(key), number.doubleValue());
        }
      }
      lastModified = worthFile.lastModified();
      lastLoadMs = now;
      plugin.getLogger().fine("Loaded " + worthValues.size() + " Essentials worth values.");
    }
  }

  private static final class ConfigWorthProvider implements SellPriceProvider {
    private final JavaPlugin plugin;
    private final String providerName;

    ConfigWorthProvider(JavaPlugin plugin, String providerName) {
      this.plugin = plugin;
      this.providerName = providerName;
    }

    @Override
    public boolean available() {
      return true;
    }

    @Override
    public String name() {
      return providerName;
    }

    @Override
    public OptionalDouble sellPrice(Player player, ItemStack stack) {
      String key = "sell-container.fallback-worth." + stack.getType().name();
      if (!plugin.getConfig().contains(key)) {
        return OptionalDouble.empty();
      }
      return OptionalDouble.of(plugin.getConfig().getDouble(key));
    }
  }

  private static final class VaultEconomyBridge {
    private final Object economyProvider;
    private final Method depositMethod;
    private final Method formatMethod;

    VaultEconomyBridge(JavaPlugin plugin) {
      Object provider = null;
      Method deposit = null;
      Method format = null;
      try {
        Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
        RegisteredServiceProvider<?> registration =
            Bukkit.getServicesManager().getRegistration(economyClass);
        if (registration != null) {
          provider = registration.getProvider();
          if (provider != null) {
            deposit = findMethod(provider.getClass(), "depositPlayer", OfflinePlayer.class,
                double.class);
            if (deposit == null) {
              deposit = findMethod(provider.getClass(), "depositPlayer", String.class, double.class);
            }
            format = findMethod(provider.getClass(), "format", double.class);
          }
        }
      } catch (ClassNotFoundException ex) {
        plugin.getLogger().fine("Vault API not found at runtime.");
      }
      this.economyProvider = provider;
      this.depositMethod = deposit;
      this.formatMethod = format;
    }

    boolean available() {
      return economyProvider != null && depositMethod != null;
    }

    boolean deposit(Player player, double amount) {
      if (!available()) {
        return false;
      }
      try {
        Object response;
        if (depositMethod.getParameterTypes()[0] == OfflinePlayer.class) {
          response = depositMethod.invoke(economyProvider, player, amount);
        } else {
          response = depositMethod.invoke(economyProvider, player.getName(), amount);
        }
        return transactionSucceeded(response);
      } catch (ReflectiveOperationException ex) {
        return false;
      }
    }

    Optional<String> format(double amount) {
      if (economyProvider == null || formatMethod == null) {
        return Optional.empty();
      }
      try {
        Object formatted = formatMethod.invoke(economyProvider, amount);
        return formatted == null ? Optional.empty() : Optional.of(formatted.toString());
      } catch (ReflectiveOperationException ex) {
        return Optional.empty();
      }
    }

    private boolean transactionSucceeded(Object response) {
      if (response == null) {
        return false;
      }
      if (response instanceof Boolean bool) {
        return bool;
      }
      OptionalDouble direct = extractPrice(response);
      if (direct.isPresent()) {
        return direct.getAsDouble() >= 0.0;
      }
      try {
        Method method = response.getClass().getMethod("transactionSuccess");
        Object value = method.invoke(response);
        if (value instanceof Boolean bool) {
          return bool;
        }
      } catch (ReflectiveOperationException ignored) {
        // Ignore.
      }
      try {
        Field field = response.getClass().getField("transactionSuccess");
        Object value = field.get(response);
        if (value instanceof Boolean bool) {
          return bool;
        }
      } catch (ReflectiveOperationException ignored) {
        // Ignore.
      }
      return false;
    }

    private Method findMethod(Class<?> type, String name, Class<?>... params) {
      try {
        return type.getMethod(name, params);
      } catch (NoSuchMethodException ex) {
        return null;
      }
    }
  }

  private static OptionalDouble extractPrice(Object source) {
    if (source == null) {
      return OptionalDouble.empty();
    }
    if (source instanceof Number number) {
      return OptionalDouble.of(number.doubleValue());
    }
    if (source instanceof Optional<?> optional) {
      return optional.isPresent() ? extractPrice(optional.get()) : OptionalDouble.empty();
    }
    if (source instanceof OptionalDouble optionalDouble) {
      return optionalDouble;
    }
    if (source instanceof Map<?, ?> map) {
      for (Object value : map.values()) {
        OptionalDouble extracted = extractPrice(value);
        if (extracted.isPresent()) {
          return extracted;
        }
      }
      return OptionalDouble.empty();
    }
    try {
      Method method = source.getClass().getMethod("getPrice");
      return extractPrice(method.invoke(source));
    } catch (ReflectiveOperationException ignored) {
      // Ignore.
    }
    try {
      Method method = source.getClass().getMethod("getValue");
      return extractPrice(method.invoke(source));
    } catch (ReflectiveOperationException ignored) {
      // Ignore.
    }
    return OptionalDouble.empty();
  }

  private static String normalizeMaterialKey(String key) {
    String normalized = key.toLowerCase(Locale.ROOT).trim();
    if (normalized.startsWith("minecraft:")) {
      normalized = normalized.substring("minecraft:".length());
    }
    if (normalized.contains(":")) {
      normalized = normalized.substring(0, normalized.indexOf(':'));
    }
    return normalized.toUpperCase(Locale.ROOT);
  }
}
