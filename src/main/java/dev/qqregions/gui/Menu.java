package dev.qqregions.gui;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import dev.qqregions.QQRegions;
import dev.qqregions.util.Expressions;
import dev.qqregions.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Шаблон GUI: блок Menus.<имя> в файле меню.
 * Содержит приоритет, группы прав/заполнителей, title, size, fill, кнопки,
 * динамические кнопки флагов (dynamic-flags) с группами и навигацию.
 * Для игрока подбирается шаблон с наибольшим приоритетом, которому он подходит.
 */
public class Menu {

    private final String title;
    private final int rows;
    private final int updateInterval;
    private final int priority;
    private final String permissionGroup;
    private final String placeholderGroup;
    /** требуемая роль: owner / member / other / "" (любая) */
    private final String roleRequired;

    private final MenuItem fill;
    private final Map<Integer, MenuItem> buttons = new LinkedHashMap<>();

    private final DynamicFlags dyn;
    private final DynamicPlayers dynPlayers;
    private MenuItem navPrev;
    private MenuItem navNext;

    public Menu(String title, int rows, int updateInterval, int priority,
                String permissionGroup, String placeholderGroup, String roleRequired,
                MenuItem fill, DynamicFlags dyn, DynamicPlayers dynPlayers) {
        this.title = title;
        this.rows = rows;
        this.updateInterval = updateInterval;
        this.priority = priority;
        this.permissionGroup = permissionGroup;
        this.placeholderGroup = placeholderGroup;
        this.roleRequired = roleRequired;
        this.fill = fill;
        this.dyn = dyn;
        this.dynPlayers = dynPlayers;
    }

    public int priority() {
        return priority;
    }

    public String roleRequired() {
        return roleRequired;
    }

    /** Совпадает ли роль игрока в регионе с требованием шаблона ("" = любая). */
    public boolean roleMatches(String role) {
        if (roleRequired == null || roleRequired.trim().isEmpty()) {
            return true;
        }
        String r = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        return roleRequired.trim().equalsIgnoreCase(r);
    }

    public int updateInterval() {
        return updateInterval;
    }

    public boolean matches(Player player) {
        if (permissionGroup != null && !permissionGroup.isEmpty()
                && !permissionGroup.trim().equals("[]")
                && !player.hasPermission(permissionGroup)) {
            return false;
        }
        return Expressions.matches(placeholderGroup, player);
    }

    public void addButton(Integer slot, MenuItem item) {
        buttons.put(slot, item);
    }

    public Map<Integer, MenuItem> items() {
        return buttons;
    }

    public DynamicFlags dynamicFlags() {
        return dyn;
    }

    public DynamicPlayers dynamicPlayers() {
        return dynPlayers;
    }

    /** Есть ли активная динамическая секция (флаги или игроки). */
    private boolean dynamicEnabled() {
        return (dyn != null && dyn.enabled) || (dynPlayers != null && dynPlayers.enabled);
    }

    /** Пул слотов под динамические кнопки (из активной секции). */
    public List<Integer> poolSlots() {
        if (dyn != null && dyn.enabled) {
            return dyn.slots;
        }
        if (dynPlayers != null && dynPlayers.enabled) {
            return dynPlayers.slots;
        }
        return java.util.List.of(9);
    }

    public MenuItem navPrev() {
        return navPrev;
    }

    public MenuItem navNext() {
        return navNext;
    }

    /** Размер пула слотов под динамические кнопки (страница). */
    public int pageSize() {
        if (!dynamicEnabled()) {
            return 1;
        }
        return Math.max(1, poolSlots().size());
    }

    public int maxPages(int dynSize) {
        if (!dynamicEnabled() || dynSize <= 0) {
            return 1;
        }
        int pool = Math.max(1, poolSlots().size());
        return Math.max(1, (dynSize + pool - 1) / pool);
    }

    public void setNav(MenuItem prev, MenuItem next) {
        this.navPrev = prev;
        this.navNext = next;
    }

    // ---------- динамические кнопки флагов ----------

    /**
     * Строит список кнопок флагов (флаг x группа) из реестра WorldGuard
     * (WG + WGEFP + любые другие плагины).
     */
    public List<MenuItem> dynamicFlags(QQRegions plugin, Player player, Map<String, String> ctx) {
        List<MenuItem> out = new ArrayList<>();
        if (dyn == null || !dyn.enabled) {
            return out;
        }
        String worldName = ctx.get("world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        ProtectedRegion region = null;
        try {
            region = plugin.wg().byName(world, ctx.get("region"));
        } catch (Throwable ignored) {
        }

        for (Flag<?> flag : plugin.wg().allFlags()) {
            String id = flag.getName();
            String key = id == null ? "" : id.toLowerCase(Locale.ROOT);
            if (key.isEmpty() || dyn.ignore.contains(key)) {
                continue;
            }
            // право на флаг: qqregions.flags.<flag> (выдаётся в LuckPerms);
            // пустой префикс = флаг доступен всем
            String perm = dyn.permissionPrefix + key;
            if (!dyn.permissionPrefix.isEmpty() && !player.hasPermission(perm)) {
                continue;
            }
            boolean state = flag instanceof StateFlag;
            for (String group : dyn.groups) {
                String flagGroup = group.equalsIgnoreCase("all") ? id : id + ":" + group;
                // У флага ОДНА группа: значение видно только на кнопке той
                // группы, которая сейчас стоит у флага (для остальных "").
                String value = (world != null && region != null)
                        ? plugin.wg().flagValueFor(world, region, flag, group)
                        : "";
                String currentGroup = (world != null && region != null)
                        ? plugin.wg().flagGroup(world, region, flag)
                        : "all";
                String currentMark = currentGroup.equalsIgnoreCase(group) ? " &a✓" : "";
                String groupLabel = plugin.replace().resolve("flag-groups", group);
                String currentGroupLabel = plugin.replace().resolve("flag-groups", currentGroup);
                String valueLabel = value.isEmpty()
                        ? "&7не задано"
                        : plugin.replace().resolve("flag-values", value);

                Map<String, String> fc = new LinkedHashMap<>(ctx);
                fc.put("flag", id);
                fc.put("flag-value", value);
                fc.put("flag-value-label", valueLabel);
                fc.put("group", group);
                fc.put("group-label", groupLabel);
                fc.put("group-current", currentMark);
                fc.put("flag-group", currentGroup);
                fc.put("flag-group-label", currentGroupLabel);
                fc.put("flag-with-group", flagGroup);

                String name = bake(fc, dyn.name);
                List<String> lore = bakeList(fc, dyn.lore);
                List<String> commands = bakeList(fc, dyn.commands);

                String mat = dyn.materials.getOrDefault(id, dyn.material);
                List<String> states = state ? dyn.states : dyn.customStates;
                out.add(new MenuItem(mat, 1, null, name, lore, commands, "",
                        id, group, states, state));
            }
        }
        return out;
    }

    private static String bake(Map<String, String> ctx, String text) {
        String out = text == null ? "" : text;
        for (Map.Entry<String, String> e : ctx.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private static List<String> bakeList(Map<String, String> ctx, List<String> in) {
        if (in == null) {
            return null;
        }
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            out.add(bake(ctx, s));
        }
        return out;
    }

    /**
     * Собрать инвентарь для конкретной страницы; slotMap (может быть null)
     * заполняется соответствием слот-&gt;кнопка для обработки кликов.
     */
    public Inventory build(QQRegions plugin, Player player, Map<String, String> ctx,
                           int page, int maxPages, List<MenuItem> dynItems,
                           Map<Integer, MenuItem> slotMap) {
        int size = Math.max(1, Math.min(6, rows)) * 9;
        Map<String, String> titleCtx = new LinkedHashMap<>(ctx);
        titleCtx.put("page", String.valueOf(page + 1));
        titleCtx.put("pages", String.valueOf(maxPages));
        String titleProcessed = new MenuItem("STONE", 1, null, title, null, null, null)
                .process(plugin, player, titleCtx, title == null ? "" : title);
        Inventory inv = Bukkit.createInventory(null, size, Msg.color(titleProcessed));

        if (slotMap != null) {
            slotMap.clear();
        }
        for (Map.Entry<Integer, MenuItem> e : buttons.entrySet()) {
            Integer slot = e.getKey();
            if (slot != null && slot >= 0 && slot < size) {
                inv.setItem(slot, e.getValue().build(plugin, player, ctx));
                if (slotMap != null) {
                    slotMap.put(slot, e.getValue());
                }
            }
        }

        if (dynamicEnabled() && dynItems != null && !dynItems.isEmpty()) {
            List<Integer> pool = poolSlots();
            int poolSize = Math.max(1, pool.size());
            int start = Math.max(0, Math.min(dynItems.size(), page * poolSize));
            int end = Math.min(dynItems.size(), start + poolSize);
            for (int i = start; i < end; i++) {
                int slot = pool.get((i - start) % poolSize);
                if (slot < 0 || slot >= size || inv.getItem(slot) != null) {
                    continue;
                }
                inv.setItem(slot, dynItems.get(i).build(plugin, player, ctx));
                if (slotMap != null) {
                    slotMap.put(slot, dynItems.get(i));
                }
            }
        }

        if (navPrev != null && page > 0) {
            putNav(inv, size, navPrev, 45, plugin, player, ctx, slotMap);
        }
        if (navNext != null && maxPages > 1 && page < maxPages - 1) {
            putNav(inv, size, navNext, 53, plugin, player, ctx, slotMap);
        }

        if (fill != null) {
            for (int i = 0; i < size; i++) {
                if (inv.getItem(i) == null) {
                    inv.setItem(i, fill.build(plugin, player, ctx));
                }
            }
        }
        return inv;
    }

    private void putNav(Inventory inv, int size, MenuItem item, int defaultSlot,
                        QQRegions plugin, Player player, Map<String, String> ctx,
                        Map<Integer, MenuItem> slotMap) {
        Integer slot = item.slot();
        if (slot == null) {
            slot = defaultSlot;
        }
        if (slot < 0 || slot >= size || inv.getItem(slot) != null) {
            return;
        }
        inv.setItem(slot, item.build(plugin, player, ctx));
        if (slotMap != null) {
            slotMap.put(slot, item);
        }
    }

    // ---------- парсинг ----------

    public static List<Menu> parseFile(QQRegions plugin, File file, int defaultUpdate) {
        List<Menu> out = new ArrayList<>();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        int topUpdate = cfg.getInt("update_interval", defaultUpdate);
        ConfigurationSection menus = cfg.getConfigurationSection("Menus");
        if (menus == null) {
            menus = cfg;
        }
        for (String groupName : menus.getKeys(false)) {
            ConfigurationSection g = menus.getConfigurationSection(groupName);
            if (g == null) {
                continue;
            }
            Menu menu = parseGroup(g, topUpdate);
            if (menu != null) {
                out.add(menu);
            }
        }
        return out;
    }

    private static Menu parseGroup(ConfigurationSection g, int topUpdate) {
        String title = g.getString("title", "&0QQRegions");
        int rows = Math.max(1, Math.min(6, g.getInt("size", 6)));
        int update = g.getInt("update_interval", topUpdate);
        int priority = g.getInt("priority", 1);
        String permGroup = g.getString("permission-group", "");
        String phGroup = g.getString("placeholder-group", "");
        String roleRequired = g.getString("role-required", "");

        MenuItem fill = null;
        ConfigurationSection f = g.getConfigurationSection("fill");
        if (f != null && f.getBoolean("enabled", false)) {
            List<String> fillLore = f.getStringList("lore");
            fill = new MenuItem(f.getString("material", "BLACK_STAINED_GLASS_PANE"),
                    1, null,
                    f.getString("name", " "),
                    fillLore.isEmpty() ? null : fillLore,
                    null, null);
        }

        DynamicFlags dyn = DynamicFlags.parse(g.getConfigurationSection("dynamic-flags"));
        DynamicPlayers dynPlayers = DynamicPlayers.parse(g.getConfigurationSection("dynamic-players"));
        Menu menu = new Menu(title, rows, update, priority, permGroup, phGroup, roleRequired, fill, dyn, dynPlayers);

        ConfigurationSection btns = g.getConfigurationSection("buttons");
        if (btns != null) {
            int auto = 0;
            for (String key : btns.getKeys(false)) {
                ConfigurationSection b = btns.getConfigurationSection(key);
                if (b == null) {
                    continue;
                }
                Integer slot = null;
                if (b.contains("slot")) {
                    slot = b.getInt("slot");
                }
                if (slot == null) {
                    slot = auto++;
                }
                MenuItem item = new MenuItem(
                        b.getString("material", "STONE"),
                        b.getInt("amount", 1),
                        slot,
                        b.getString("name", " "),
                        b.getStringList("lore").isEmpty() ? null : b.getStringList("lore"),
                        b.getStringList("commands").isEmpty() ? null : b.getStringList("commands"),
                        b.getString("permission", ""));
                menu.addButton(slot, item);
            }
        }

        ConfigurationSection nav = g.getConfigurationSection("navigation");
        if (nav != null) {
            MenuItem prev = parseNav(nav, "prev", "@page:prev", null);
            MenuItem next = parseNav(nav, "next", "@page:next", null);
            menu.setNav(prev, next);
        }

        return menu;
    }

    private static MenuItem parseNav(ConfigurationSection nav, String key, String action, Integer defSlot) {
        ConfigurationSection b = nav.getConfigurationSection(key);
        if (b == null) {
            return null;
        }
        Integer slot = defSlot;
        if (b.contains("slot")) {
            slot = b.getInt("slot");
        }
        List<String> lore = b.getStringList("lore");
        return new MenuItem(
                b.getString("material", "ARROW"),
                b.getInt("amount", 1),
                slot,
                b.getString("name", " "),
                lore.isEmpty() ? null : lore,
                List.of(action),
                b.getString("permission", ""));
    }

    // ---------- настройки динамических кнопок флагов ----------

    public static class DynamicFlags {
        public final boolean enabled;
        public final List<Integer> slots;
        public final String material;
        public final String name;
        public final List<String> lore;
        public final List<String> commands;
        public final List<String> groups;
        public final List<String> states;
        public final List<String> customStates;
        public final Set<String> ignore;
        public final Map<String, String> materials;
        /** префикс права на флаг: показывается только если игрок имеет <prefix><флаг> */
        public final String permissionPrefix;

        DynamicFlags(boolean enabled, List<Integer> slots, String material, String name,
                     List<String> lore, List<String> commands, List<String> groups,
                     List<String> states, List<String> customStates, Set<String> ignore,
                     Map<String, String> materials, String permissionPrefix) {
            this.enabled = enabled;
            this.slots = slots;
            this.material = material;
            this.name = name;
            this.lore = lore;
            this.commands = commands;
            this.groups = groups;
            this.states = states;
            this.customStates = customStates;
            this.ignore = ignore;
            this.materials = materials;
            this.permissionPrefix = permissionPrefix;
        }

        public static DynamicFlags parse(ConfigurationSection d) {
            if (d == null || !d.getBoolean("enabled", false)) {
                return null;
            }
            List<Integer> slots = new ArrayList<>();
            if (d.isList("slots")) {
                for (String s : d.getStringList("slots")) {
                    try {
                        slots.add(Integer.parseInt(s.trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (slots.isEmpty()) {
                int first = Math.max(0, d.getInt("first-slot", 10));
                int last = Math.min(53, d.getInt("last-slot", 44));
                for (int i = first; i <= last; i++) {
                    slots.add(i);
                }
            }
            if (slots.isEmpty()) {
                slots.add(9);
            }
            List<String> groups = d.getStringList("groups");
            if (groups.isEmpty()) {
                groups = List.of("all");
            }
            Set<String> ignore = new HashSet<>();
            for (String s : d.getStringList("ignore-flags")) {
                ignore.add(s.toLowerCase(Locale.ROOT));
            }
            Map<String, String> materials = new HashMap<>();
            ConfigurationSection ms = d.getConfigurationSection("materials");
            if (ms != null) {
                for (String k : ms.getKeys(false)) {
                    materials.put(k, ms.getString(k, "STONE"));
                }
            }
            return new DynamicFlags(true, slots,
                    d.getString("material", "MAP"),
                    d.getString("name", "&f{flag}"),
                    d.getStringList("lore"),
                    d.getStringList("commands"),
                    groups,
                    List.of("allow", "deny"),
                    d.getStringList("states"),
                    ignore, materials,
                    d.getString("flag-permission-prefix", ""));
        }
    }

    // ---------- настройки динамических кнопок игроков ----------

    /**
     * Параметры списка участников в меню (как dynamic-flags, но по игрокам):
     *   slots, material/owner-material/member-material, name ({player}),
     *   lore (PAPI резолвится на КОНКРЕТНОГО игрока) и commands
     *   (для владельца — @player-del:{player-id}:{role}; для остальных пусто).
     */
    public static class DynamicPlayers {
        public final boolean enabled;
        public final List<Integer> slots;
        public final String material;
        public final String ownerMaterial;
        public final String memberMaterial;
        public final String name;
        public final List<String> lore;
        public final List<String> commands;

        DynamicPlayers(boolean enabled, List<Integer> slots, String material,
                       String ownerMaterial, String memberMaterial, String name,
                       List<String> lore, List<String> commands) {
            this.enabled = enabled;
            this.slots = slots;
            this.material = material;
            this.ownerMaterial = ownerMaterial;
            this.memberMaterial = memberMaterial;
            this.name = name;
            this.lore = lore;
            this.commands = commands;
        }

        public static DynamicPlayers parse(ConfigurationSection d) {
            if (d == null || !d.getBoolean("enabled", false)) {
                return null;
            }
            List<Integer> slots = new ArrayList<>();
            if (d.isList("slots")) {
                for (String s : d.getStringList("slots")) {
                    try {
                        slots.add(Integer.parseInt(s.trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (slots.isEmpty()) {
                int first = Math.max(0, d.getInt("first-slot", 10));
                int last = Math.min(53, d.getInt("last-slot", 34));
                for (int i = first; i <= last; i++) {
                    slots.add(i);
                }
            }
            if (slots.isEmpty()) {
                slots.add(9);
            }
            String material = d.getString("material", "GOLD_INGOT");
            return new DynamicPlayers(true, slots, material,
                    d.getString("owner-material", material),
                    d.getString("member-material", material),
                    d.getString("name", "{player}"),
                    d.getStringList("lore"),
                    d.getStringList("commands"));
        }
    }
}