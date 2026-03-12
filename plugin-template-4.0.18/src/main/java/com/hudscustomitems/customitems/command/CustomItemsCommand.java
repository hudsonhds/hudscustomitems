package com.hudscustomitems.customitems.command;

import com.hudscustomitems.customitems.attribute.AttributeCatalog;
import com.hudscustomitems.customitems.attribute.AttributeCategory;
import com.hudscustomitems.customitems.attribute.AttributeDefinition;
import com.hudscustomitems.customitems.attribute.AttributeValueType;
import com.hudscustomitems.customitems.model.CustomItemDefinition;
import com.hudscustomitems.customitems.service.CustomItemService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Command executor and tab completion for /customitems.
 */
public final class CustomItemsCommand implements CommandExecutor, TabCompleter {
  private final JavaPlugin plugin;
  private final CustomItemService itemService;

  /**
   * Creates command handler.
   *
   * @param plugin owner plugin
   * @param itemService service instance
   */
  public CustomItemsCommand(JavaPlugin plugin, CustomItemService itemService) {
    this.plugin = plugin;
    this.itemService = itemService;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
      sendHelp(sender, label);
      return true;
    }
    if (!hasAdminAccess(sender)) {
      sender.sendMessage(color("&cYou do not have permission."));
      return true;
    }
    String sub = args[0].toLowerCase(Locale.ROOT);
    switch (sub) {
      case "create" -> handleCreate(sender, args);
      case "edit" -> handleEdit(sender, args);
      case "remove" -> handleRemove(sender, args);
      case "give" -> handleGive(sender, args);
      case "reload" -> handleReload(sender);
      case "list" -> handleList(sender);
      case "attributes", "attrs" -> handleAttributes(sender, args);
      case "inspect" -> handleInspect(sender, args);
      case "oneoff" -> handleOneOff(sender, args);
      default -> sendHelp(sender, label);
    }
    return true;
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender,
      Command command,
      String alias,
      String[] args) {
    if (args.length == 1) {
      return filterPrefix(args[0], List.of(
          "help",
          "create",
          "edit",
          "remove",
          "give",
          "reload",
          "list",
          "attributes",
          "inspect",
          "oneoff"));
    }
    if (args[0].equalsIgnoreCase("create")) {
      if (args.length == 3) {
        return materialSuggestions(args[2]);
      }
      if (args.length == 4) {
        return filterPrefix(args[3], List.of(args[1], "CustomItem"));
      }
      if (args.length >= 5) {
        int relative = args.length - 4;
        if (relative % 2 == 1) {
          Set<String> used = new HashSet<>();
          for (int i = 4; i < args.length - 1; i += 2) {
            used.add(args[i].toLowerCase(Locale.ROOT));
          }
          List<String> unused = AttributeCatalog.ids()
              .stream()
              .filter(id -> !used.contains(id))
              .collect(Collectors.toList());
          return filterPrefix(args[args.length - 1], unused);
        }
        return attributeValueSuggestions(args[args.length - 2], args[args.length - 1]);
      }
      return List.of();
    }
    if (args[0].equalsIgnoreCase("edit")) {
      if (args.length == 2) {
        return filterPrefix(args[1], itemService.definitionIds());
      }
      if (args.length == 3) {
        return filterPrefix(args[2], List.of("name", "material", "lore", "setattr", "delattr"));
      }
      if (args.length == 4 && args[2].equalsIgnoreCase("material")) {
        return materialSuggestions(args[3]);
      }
      if (args.length == 4
          && (args[2].equalsIgnoreCase("setattr") || args[2].equalsIgnoreCase("delattr"))) {
        return filterPrefix(args[3], AttributeCatalog.ids());
      }
      if (args.length >= 5 && args[2].equalsIgnoreCase("setattr")) {
        return attributeValueSuggestions(args[3], args[args.length - 1]);
      }
      return List.of();
    }
    if (args[0].equalsIgnoreCase("remove") && args.length == 2) {
      return filterPrefix(args[1], itemService.definitionIds());
    }
    if (args[0].equalsIgnoreCase("give")) {
      if (args.length == 2) {
        return filterPrefix(args[1], Bukkit.getOnlinePlayers()
            .stream()
            .map(Player::getName)
            .collect(Collectors.toList()));
      }
      if (args.length == 3) {
        return filterPrefix(args[2], itemService.definitionIds());
      }
      return List.of();
    }
    if ((args[0].equalsIgnoreCase("attributes") || args[0].equalsIgnoreCase("attrs"))
        && args.length == 2) {
      return filterPrefix(args[1], AttributeCatalog.ids());
    }
    if (args[0].equalsIgnoreCase("inspect") && args.length == 2) {
      return filterPrefix(args[1], Bukkit.getOnlinePlayers()
          .stream()
          .map(Player::getName)
          .collect(Collectors.toList()));
    }
    if (args[0].equalsIgnoreCase("oneoff")) {
      if (args.length == 2) {
        return filterPrefix(args[1], List.of("add", "remove", "clear", "list"));
      }
      if (args.length == 3
          && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
        return filterPrefix(args[2], AttributeCatalog.ids());
      }
      if (args.length >= 4 && args[1].equalsIgnoreCase("add")) {
        return attributeValueSuggestions(args[2], args[args.length - 1]);
      }
      return List.of();
    }
    return List.of();
  }

  private void handleCreate(CommandSender sender, String[] args) {
    if (args.length < 4) {
      sender.sendMessage(color("&cUsage: /customitems create <id> <material> <display_name>"
          + " [attribute value]..."));
      sender.sendMessage(color("&7Use /customitems edit <id> name <multi word name> afterwards."));
      return;
    }
    String id = args[1].toLowerCase(Locale.ROOT);
    if (itemService.definition(id).isPresent()) {
      sender.sendMessage(color("&cThat id already exists."));
      return;
    }
    Material material = parseMaterial(args[2]);
    if (material == null) {
      sender.sendMessage(color("&cInvalid material."));
      return;
    }
    String displayName = args[3];
    if ((args.length - 4) % 2 != 0) {
      sender.sendMessage(color("&cAttributes must be provided as pairs:"
          + " <attribute> <value> <attribute> <value> ..."));
      return;
    }
    Map<String, String> pendingAttributes = new LinkedHashMap<>();
    for (int index = 4; index < args.length; index += 2) {
      String attributeId = args[index].toLowerCase(Locale.ROOT);
      String value = args[index + 1];
      Optional<AttributeDefinition> definition = AttributeCatalog.byId(attributeId);
      if (definition.isEmpty()) {
        sender.sendMessage(color("&cUnknown attribute '&f" + attributeId + "&c'."));
        return;
      }
      if (!definition.get().valueType().isValid(value)) {
        sender.sendMessage(color("&cInvalid value for '&f" + attributeId + "&c'. Expected "
            + definition.get().valueType().example()));
        return;
      }
      pendingAttributes.put(attributeId, value);
    }
    itemService.createDefinition(id, material, displayName);
    for (Map.Entry<String, String> attribute : pendingAttributes.entrySet()) {
      Optional<String> error = itemService.setDefinitionAttribute(
          id,
          attribute.getKey(),
          attribute.getValue());
      if (error.isPresent()) {
        itemService.removeDefinition(id);
        sender.sendMessage(color("&cFailed to create item: " + error.get()));
        return;
      }
    }
    sender.sendMessage(color("&aCreated custom item '&f" + id + "&a'."));
    if (!pendingAttributes.isEmpty()) {
      sender.sendMessage(color("&7Applied &f" + pendingAttributes.size() + "&7 attributes."));
    }
    sender.sendMessage(color("&7Tip: /customitems edit " + id + " name <multi word name>"));
  }

  private void handleEdit(CommandSender sender, String[] args) {
    if (args.length < 4) {
      sender.sendMessage(color("&cUsage: /customitems edit <id> <field> <value...>"));
      return;
    }
    String id = args[1];
    Optional<CustomItemDefinition> optionalDefinition = itemService.definition(id);
    if (optionalDefinition.isEmpty()) {
      sender.sendMessage(color("&cUnknown id."));
      return;
    }
    String field = args[2].toLowerCase(Locale.ROOT);
    switch (field) {
      case "name" -> {
        String name = join(args, 3);
        itemService.setDisplayName(id, name);
        sender.sendMessage(color("&aUpdated display name."));
      }
      case "material" -> {
        Material material = parseMaterial(args[3]);
        if (material == null) {
          sender.sendMessage(color("&cInvalid material."));
          return;
        }
        itemService.setMaterial(id, material);
        sender.sendMessage(color("&aUpdated material to &f" + material.name()));
      }
      case "lore" -> {
        String raw = join(args, 3);
        List<String> lore = List.of(raw.split("\\|"));
        itemService.setLore(id, lore);
        sender.sendMessage(color("&aUpdated lore lines."));
      }
      case "setattr" -> {
        if (args.length < 5) {
          sender.sendMessage(color("&cUsage: /customitems edit <id> setattr <attribute> <value>"));
          return;
        }
        String attributeId = args[3];
        String value = join(args, 4);
        Optional<String> error = itemService.setDefinitionAttribute(id, attributeId, value);
        if (error.isPresent()) {
          sender.sendMessage(color("&c" + error.get()));
          return;
        }
        sender.sendMessage(color("&aSet &f" + attributeId + "&a on &f" + id));
      }
      case "delattr" -> {
        String attributeId = args[3];
        boolean removed = itemService.removeDefinitionAttribute(id, attributeId);
        if (!removed) {
          sender.sendMessage(color("&cThat attribute is not set."));
          return;
        }
        sender.sendMessage(color("&aRemoved attribute &f" + attributeId));
      }
      default -> sender.sendMessage(color("&cUnknown field. Use name/material/lore/setattr/delattr"));
    }
  }

  private void handleRemove(CommandSender sender, String[] args) {
    if (args.length < 2) {
      sender.sendMessage(color("&cUsage: /customitems remove <id>"));
      return;
    }
    boolean removed = itemService.removeDefinition(args[1]);
    if (!removed) {
      sender.sendMessage(color("&cUnknown id."));
      return;
    }
    sender.sendMessage(color("&aRemoved item '&f" + args[1] + "&a'."));
  }

  private void handleGive(CommandSender sender, String[] args) {
    if (args.length < 3) {
      sender.sendMessage(color("&cUsage: /customitems give <player> <id> [amount]"));
      return;
    }
    Player target = Bukkit.getPlayerExact(args[1]);
    if (target == null) {
      sender.sendMessage(color("&cPlayer not found."));
      return;
    }
    int amount = 1;
    if (args.length >= 4) {
      try {
        amount = Math.max(1, Integer.parseInt(args[3]));
      } catch (NumberFormatException ex) {
        sender.sendMessage(color("&cAmount must be a number."));
        return;
      }
    }
    Optional<ItemStack> stack = itemService.createStack(args[2], amount, target.getUniqueId());
    if (stack.isEmpty()) {
      sender.sendMessage(color("&cUnknown item id."));
      return;
    }
    target.getInventory().addItem(stack.get());
    sender.sendMessage(color("&aGave &f" + amount + "x " + args[2] + "&a to &f" + target.getName()));
  }

  private void handleReload(CommandSender sender) {
    plugin.reloadConfig();
    itemService.reloadDefinitions();
    sender.sendMessage(color("&aReloaded config.yml and items.yml"));
  }

  private void handleList(CommandSender sender) {
    Collection<CustomItemDefinition> items = itemService.definitions();
    if (items.isEmpty()) {
      sender.sendMessage(color("&7No custom items have been created yet."));
      return;
    }
    sender.sendMessage(color("&eCustom Items (&f" + items.size() + "&e):"));
    for (CustomItemDefinition definition : items) {
      sender.sendMessage(color("&7- &f" + definition.id() + "&7 => "
          + definition.material().name() + "&7, "
          + definition.attributes().size() + " attrs"));
    }
  }

  private void handleAttributes(CommandSender sender, String[] args) {
    if (args.length >= 2) {
      Optional<AttributeDefinition> found = AttributeCatalog.byId(args[1].toLowerCase(Locale.ROOT));
      if (found.isPresent()) {
        AttributeDefinition definition = found.get();
        sender.sendMessage(color("&e" + definition.displayName() + " &7(" + definition.id() + ")"));
        sender.sendMessage(color("&7Category: &f" + definition.category().name()));
        sender.sendMessage(color("&7Type: &f" + definition.valueType().name()
            + " &7(" + definition.valueType().example() + ")"));
        sender.sendMessage(color("&7" + definition.description()));
        return;
      }
    }
    sender.sendMessage(color("&eSupported Attributes:"));
    Map<AttributeCategory, List<AttributeDefinition>> grouped = AttributeCatalog.groupedByCategory();
    for (Map.Entry<AttributeCategory, List<AttributeDefinition>> entry : grouped.entrySet()) {
      sender.sendMessage(color("&6[" + prettify(entry.getKey().name()) + "]"));
      List<AttributeDefinition> sorted = entry.getValue()
          .stream()
          .sorted(Comparator.comparing(AttributeDefinition::id))
          .collect(Collectors.toList());
      for (AttributeDefinition definition : sorted) {
        sender.sendMessage(color("&7- &f" + definition.id() + "&7: " + definition.description()));
      }
    }
    sender.sendMessage(color("&7Use /customitems attributes <id> for details."));
  }

  private void handleInspect(CommandSender sender, String[] args) {
    Player target;
    if (args.length >= 2) {
      target = Bukkit.getPlayerExact(args[1]);
      if (target == null) {
        sender.sendMessage(color("&cPlayer not found."));
        return;
      }
    } else if (sender instanceof Player player) {
      target = player;
    } else {
      sender.sendMessage(color("&cConsole must specify a player."));
      return;
    }
    ItemStack stack = target.getInventory().getItemInMainHand();
    Optional<String> itemId = itemService.customItemId(stack);
    if (itemId.isEmpty()) {
      sender.sendMessage(color("&7No custom item in main hand."));
      return;
    }
    sender.sendMessage(color("&eInspecting &f" + target.getName() + "&e main hand"));
    sender.sendMessage(color("&7ID: &f" + itemId.get()));
    Map<String, String> attributes = itemService.resolveAttributes(stack);
    if (attributes.isEmpty()) {
      sender.sendMessage(color("&7No attributes"));
      return;
    }
    for (Map.Entry<String, String> entry : attributes.entrySet()) {
      sender.sendMessage(color("&7- &f" + entry.getKey() + "&7 = &f" + entry.getValue()));
    }
  }

  private void handleOneOff(CommandSender sender, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(color("&cOnly players can use oneoff commands."));
      return;
    }
    if (args.length < 2) {
      sender.sendMessage(color("&cUsage: /customitems oneoff <add|remove|clear|list> ..."));
      return;
    }
    ItemStack held = player.getInventory().getItemInMainHand();
    String action = args[1].toLowerCase(Locale.ROOT);
    switch (action) {
      case "add" -> {
        if (args.length < 4) {
          sender.sendMessage(color("&cUsage: /customitems oneoff add <attribute> <value>"));
          return;
        }
        String attribute = args[2];
        String value = join(args, 3);
        Optional<String> error = itemService.setOneTimeAttribute(held, attribute, value);
        if (error.isPresent()) {
          sender.sendMessage(color("&c" + error.get()));
          return;
        }
        sender.sendMessage(color("&aApplied one-time attribute &f" + attribute));
      }
      case "remove" -> {
        if (args.length < 3) {
          sender.sendMessage(color("&cUsage: /customitems oneoff remove <attribute>"));
          return;
        }
        boolean removed = itemService.removeOneTimeAttribute(held, args[2]);
        if (!removed) {
          sender.sendMessage(color("&cOne-time attribute not found."));
          return;
        }
        sender.sendMessage(color("&aRemoved one-time attribute."));
      }
      case "clear" -> {
        itemService.clearOneTimeAttributes(held);
        sender.sendMessage(color("&aCleared one-time attributes."));
      }
      case "list" -> {
        Map<String, String> oneTime = itemService.oneTimeAttributes(held);
        if (oneTime.isEmpty()) {
          sender.sendMessage(color("&7No one-time attributes on held item."));
          return;
        }
        sender.sendMessage(color("&eOne-time attributes:"));
        for (Map.Entry<String, String> entry : oneTime.entrySet()) {
          sender.sendMessage(color("&7- &f" + entry.getKey() + "&7 = &f" + entry.getValue()));
        }
      }
      default -> sender.sendMessage(color("&cUsage: /customitems oneoff <add|remove|clear|list>"));
    }
  }

  private boolean hasAdminAccess(CommandSender sender) {
    String permission = plugin.getConfig().getString("permission-node", "customitems.admin");
    return sender.isOp() || sender.hasPermission(permission);
  }

  private List<String> materialSuggestions(String input) {
    List<String> suggestions = new ArrayList<>();
    String lower = input.toLowerCase(Locale.ROOT);
    for (Material material : Material.values()) {
      if (material.name().toLowerCase(Locale.ROOT).startsWith(lower)) {
        suggestions.add(material.name());
      }
    }
    return suggestions;
  }

  private List<String> attributeValueSuggestions(String attributeId, String partialInput) {
    Optional<AttributeDefinition> found = AttributeCatalog.byId(attributeId.toLowerCase(Locale.ROOT));
    if (found.isEmpty()) {
      return List.of();
    }
    AttributeDefinition definition = found.get();
    AttributeValueType type = definition.valueType();
    List<String> suggestions = switch (type) {
      case BOOLEAN -> List.of("true", "false");
      case INTEGER -> List.of("1", "2", "3", "5", "10", "20");
      case DECIMAL -> List.of("0.1", "0.25", "0.5", "1.0", "2.0", "3.0");
      case MATERIAL -> materialSuggestions(partialInput);
      case MATERIAL_LIST -> List.of("STONE,COBBLESTONE", "DIAMOND_ORE,DEEPSLATE_DIAMOND_ORE");
      case ENTITY_LIST -> entitySuggestions(partialInput);
      case SOUND -> soundSuggestions(partialInput);
      case PARTICLE -> particleSuggestions(partialInput);
      case POTION -> List.of("SPEED", "HASTE", "STRENGTH", "REGENERATION");
      case COLOR -> List.of("red", "gold", "aqua", "green", "#55FF55");
      case TEXT -> textSuggestions(definition.id());
    };
    return filterPrefix(partialInput, suggestions);
  }

  private List<String> textSuggestions(String attributeId) {
    return switch (attributeId) {
      case "tool_types" -> List.of(
          "pickaxe",
          "shovel",
          "axe",
          "hoe",
          "sword",
          "pickaxe+shovel",
          "pickaxe+axe+shovel");
      case "break_volume" -> List.of("3x3x3", "5x3x1", "3x5x3");
      case "block_placement_radius" -> List.of("line:5", "wall:3", "area:5");
      case "block_replace_mode" -> List.of("STONE>DIAMOND_ORE", "DIRT>GRASS_BLOCK");
      case "projectile_launch" -> List.of("arrow:2.0", "snowball:1.6", "fireball:1.2");
      case "summon_entity" -> List.of("WOLF", "IRON_GOLEM", "ZOMBIE");
      case "required_permission" -> List.of("customitems.use", "customitems.vip");
      case "required_world" -> List.of("world", "world_nether", "world_the_end");
      case "right_click_ability", "shift_ability" -> List.of(
          "heal",
          "blink",
          "burst",
          "projectile",
          "block");
      case "custom_lore_lines", "animated_lore" -> List.of(
          "&7Line 1|&eLine 2",
          "&aFrame 1|&bFrame 2|&dFrame 3");
      default -> List.of();
    };
  }

  private List<String> particleSuggestions(String input) {
    List<String> values = new ArrayList<>();
    String lower = input.toLowerCase(Locale.ROOT);
    for (Particle particle : Particle.values()) {
      String name = particle.name();
      if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
        values.add(name);
      }
    }
    return values;
  }

  private List<String> soundSuggestions(String input) {
    List<String> values = new ArrayList<>();
    String lower = input.toLowerCase(Locale.ROOT);
    for (Sound sound : Sound.values()) {
      String name = sound.name();
      if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
        values.add(name);
      }
    }
    return values;
  }

  private List<String> entitySuggestions(String input) {
    List<String> values = new ArrayList<>();
    String lower = input.toLowerCase(Locale.ROOT);
    for (EntityType type : EntityType.values()) {
      String name = type.name();
      if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
        values.add(name);
      }
    }
    return values;
  }

  private List<String> filterPrefix(String input, Collection<String> candidates) {
    String lower = input.toLowerCase(Locale.ROOT);
    return candidates
        .stream()
        .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(lower))
        .collect(Collectors.toList());
  }

  private Material parseMaterial(String input) {
    try {
      return Material.valueOf(input.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private String join(String[] args, int startIndex) {
    if (startIndex >= args.length) {
      return "";
    }
    return String.join(" ", List.of(args).subList(startIndex, args.length));
  }

  private String color(String input) {
    return ChatColor.translateAlternateColorCodes('&', input);
  }

  private String prettify(String enumName) {
    String[] split = enumName.toLowerCase(Locale.ROOT).split("_");
    List<String> words = new ArrayList<>();
    for (String part : split) {
      words.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1));
    }
    return String.join(" ", words);
  }

  private void sendHelp(CommandSender sender, String label) {
    sender.sendMessage(color("&eCustomItems Commands"));
    sender.sendMessage(color("&7/" + label
        + " create <id> <material> <display_name> [attribute value]..."));
    sender.sendMessage(color("&7/" + label + " edit <id> name|material|lore|setattr|delattr ..."));
    sender.sendMessage(color("&7/" + label + " give <player> <id> [amount]"));
    sender.sendMessage(color("&7/" + label + " remove <id>"));
    sender.sendMessage(color("&7/" + label + " list"));
    sender.sendMessage(color("&7/" + label + " attributes [attribute-id]"));
    sender.sendMessage(color("&7/" + label + " inspect [player]"));
    sender.sendMessage(color("&7/" + label + " oneoff add|remove|clear|list ..."));
    sender.sendMessage(color("&7/" + label + " reload"));
  }
}
