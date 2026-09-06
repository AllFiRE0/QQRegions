package dev.qqregions.gui;

import com.sk89q.worldguard.protection.flags.BooleanFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.qqregions.QQRegions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Менеджер GUI-меню. Загружает файлы из папки menus/, хранит открытые
 * меню и обновляет их по update_interval, исполняет команды кнопок.
 */
public class MenuManager implements Listener {

    /** файл (без .yml) -> упорядоченные по приоритету шаблоны */
    private final Map<String, List<Menu>> menus = new HashMap<>();
    private final Map<UUID, OpenMenu> open = new HashMap<>();
    private final QQRegions plugin;

    public MenuManager(QQRegions plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        menus.clear();
        File dir = new File(plugin.getDataFolder(), "menus");
        if (!dir.exists()) {
            dir.mkdirs();
            plugin.saveResource("menus/flags.yml", false);
            plugin.saveResource("menus/info.yml", false);
        }
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                List<Menu> parsed = Menu.parseFile(plugin, f, 20);
                parsed.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
                menus.put(f.getName().replaceFirst("\\.yml$", ""), parsed);
            }
        }
    }

    /** Открыть меню флагов региона с учётом роли игрока (owner/member/other). */
    public boolean openFlags(Player player, org.bukkit.World world, ProtectedRegion region) {
        String w = world.getName();
        String r = roleOf(player, region);
        Map<String, String> ctx = new HashMap<>();
        ctx.put("region", region.getId());
        ctx.put("world", w);
        ctx.put("player", player.getName());
        ctx.put("role", r);
        return open(player, "flags", ctx, 0, r);
    }

    /** Роль игрока в регионе: owner / member / other (админ всегда owner). */
    private String roleOf(Player player, ProtectedRegion region) {
        if (player.hasPermission("qqregions.admin") || plugin.wg().isOwner(region, player.getUniqueId())) {
            return WgRoleHolder.OWNER.key;
        }
        if (plugin.wg().isMember(region, player.getUniqueId())) {
            return WgRoleHolder.MEMBER.key;
        }
        return WgRoleHolder.OTHER.key;
    }

    /** Информационное меню региона с учётом роли игрока (owner/member/other). */
    public boolean openInfo(Player player, org.bukkit.World world, ProtectedRegion region) {
        WgRoleHolder r;
        if (player.hasPermission("qqregions.admin") || plugin.wg().isOwner(region, player.getUniqueId())) {
            r = WgRoleHolder.OWNER;
        } else if (plugin.wg().isMember(region, player.getUniqueId())) {
            r = WgRoleHolder.MEMBER;
        } else {
            r = WgRoleHolder.OTHER;
        }
        Map<String, String> ctx = new HashMap<>();
        ctx.put("region", region.getId());
        ctx.put("world", world.getName());
        ctx.put("player", player.getName());
        ctx.put("role", r.key);
        ctx.put("owners", plugin.wg().owners(region));
        ctx.put("members", plugin.wg().members(region));
        ctx.put("type", region.getType().getName());
        ctx.put("area", String.valueOf(regionArea(region)));
        ctx.put("volume", String.valueOf(region.volume()));
        ctx.put("priority", String.valueOf(safePriority(region)));
        ctx.put("status", statusOf(region));
        return open(player, "info", ctx, 0, r.key);
    }

    private enum WgRoleHolder {
        OWNER("owner"), MEMBER("member"), OTHER("other");
        final String key;

        WgRoleHolder(String key) {
            this.key = key;
        }
    }

    private static long regionArea(ProtectedRegion region) {
        try {
            com.sk89q.worldedit.math.BlockVector3 min = region.getMinimumPoint();
            com.sk89q.worldedit.math.BlockVector3 max = region.getMaximumPoint();
            return (long) (max.x() - min.x() + 1) * (max.z() - min.z() + 1);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long safePriority(ProtectedRegion region) {
        try {
            return region.getPriority();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String statusOf(ProtectedRegion region) {
        try {
            return region.getType().getName();
        } catch (Throwable t) {
            return "?";
        }
    }

    public boolean open(Player player, String menuName, Map<String, String> ctx) {
        return open(player, menuName, ctx, 0, null);
    }

    public boolean open(Player player, String menuName, Map<String, String> ctx, int page) {
        return open(player, menuName, ctx, page, null);
    }

    public boolean open(Player player, String menuName, Map<String, String> ctx, int page, String role) {
        List<Menu> candidates = menus.get(menuName.toLowerCase(java.util.Locale.ROOT));
        if (candidates == null) {
            return false;
        }
        Menu best = null;
        for (Menu m : candidates) {
            if (!m.roleMatches(role)) {
                continue;
            }
            if (m.matches(player)) {
                best = m;
                break;
            }
        }
        if (best == null) {
            return false;
        }
        return render(player, best, ctx, page, role);
    }

    /** Перерендерить инвентарь меню. */
    private boolean render(Player player, Menu menu, Map<String, String> ctx, int page, String role) {
        List<MenuItem> dynItems = menu.dynamicFlags(plugin, player, ctx);
        int maxPages = menu.maxPages(dynItems.size());
        int safePage = Math.max(0, Math.min(maxPages - 1, page));
        Map<Integer, MenuItem> slotMap = new HashMap<>();
        Inventory inv = menu.build(plugin, player, ctx, safePage, maxPages, dynItems, slotMap);
        player.openInventory(inv);
        open.put(player.getUniqueId(), new OpenMenu(player, inv, menu, ctx, safePage, maxPages, role, slotMap));
        return true;
    }

    public void tick() {
        if (open.isEmpty()) {
            return;
        }
        for (OpenMenu om : List.copyOf(open.values())) {
            om.ticks += 5;
            if (om.ticks >= om.menu.updateInterval()) {
                om.ticks = 0;
                if (!om.player.isOnline()) {
                    continue;
                }
                render(om.player, om.menu, om.ctx, om.page, om.role);
            }
        }
    }

    public void closeAll() {
        for (OpenMenu om : open.values()) {
            if (om.player.isOnline()) {
                om.player.closeInventory();
            }
        }
        open.clear();
    }

    // ---------- события ----------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        OpenMenu om = open.get(p.getUniqueId());
        if (om == null || om.inv != e.getInventory()) {
            return;
        }
        e.setCancelled(true);
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= om.inv.getSize()) {
            return;
        }
        MenuItem item = om.slotMap.get(slot);
        if (item == null) {
            return;
        }
        if (item.permission() != null && !item.permission().isEmpty()
                && !p.hasPermission("qqregions.admin") && !p.hasPermission(item.permission())) {
            return;
        }
        // флаг-кнопка: требуется право <prefix><флаг> (из dynamic-flags.flag-permission-prefix)
        Menu.DynamicFlags dyn = om.menu.dynamicFlags();
        String flagPermPrefix = dyn == null ? null : dyn.permissionPrefix;
        if (item.isDynamic() && item.flag() != null && !item.flag().isEmpty()
                && flagPermPrefix != null && !flagPermPrefix.isEmpty()
                && !p.hasPermission("qqregions.admin")
                && !p.hasPermission(flagPermPrefix + item.flag().toLowerCase(java.util.Locale.ROOT))) {
            return;
        }
        List<String> cmds = item.commands();
        if (cmds == null) {
            return;
        }

        // динамическая кнопка флага: подставляем следующий статус
        if (item.isDynamic()) {
            cmds = new ArrayList<>(cmds);
            for (int i = 0; i < cmds.size(); i++) {
                cmds.set(i, cmds.get(i).replace("{next-state}", nextState(item, currentValue(om, item))));
            }
        }

        String context = applyContext(om.ctx, cmds);
        for (String c : context.split("\\n")) {
            c = c.trim();
            if (c.isEmpty()) {
                continue;
            }
            if (c.startsWith("@page:")) {
                String arg = c.substring("@page:".length()).trim();
                if (arg.equalsIgnoreCase("prev") && om.page > 0) {
                    render(p, om.menu, om.ctx, om.page - 1, om.role);
                } else if (arg.equalsIgnoreCase("next") && om.page < om.maxPages - 1) {
                    render(p, om.menu, om.ctx, om.page + 1, om.role);
                }
                continue;
            }
            if (c.startsWith("@menu:")) {
                String target = c.substring("@menu:".length()).trim();
                open(p, target, om.ctx, 0, om.role);
                continue;
            }
            if (c.equalsIgnoreCase("@teleport")) {
                teleportToRegion(p, om.ctx, om.role);
                continue;
            }
            if (c.startsWith("@flag:")) {
                setFlag(p, om.ctx, c.substring("@flag:".length()).trim());
                continue;
            }
            MenuAction.run(p, dev.qqregions.util.Papi.set(p, c));
        }
    }

    /** Текущее значение флага (через заполнитель WorldGuard) для группы кнопки. */
    private String currentValue(OpenMenu om, MenuItem item) {
        String id = item.flag();
        String group = item.group();
        String ph = (group == null || group.equalsIgnoreCase("all"))
                ? "%worldguard_region_has_flag_" + id + "%"
                : "%worldguard_region_has_flag_" + id + ":" + group + "%";
        String value = dev.qqregions.util.Papi.set(om.player, ph);
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Следующее значение в цикле states (или allow&lt;-&gt;deny для StateFlag). */
    private String nextState(MenuItem item, String current) {
        List<String> states = item.states();
        if (states == null || states.isEmpty()) {
            return "";
        }
        int idx = -1;
        for (int i = 0; i < states.size(); i++) {
            if (states.get(i).equalsIgnoreCase(current)) {
                idx = i;
                break;
            }
        }
        return idx < 0 ? states.get(0) : states.get((idx + 1) % states.size());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        open.remove(e.getPlayer().getUniqueId());
    }

    /** Телепорт в центр региона (только админ и владелец). */
    private void teleportToRegion(Player p, Map<String, String> ctx, String role) {
        if (!p.hasPermission("qqregions.admin") && !"owner".equalsIgnoreCase(role)) {
            p.sendMessage(dev.qqregions.util.Msg.color("&cТелепорт доступен только владельцам/админам."));
            return;
        }
        String worldName = ctx.get("world");
        if (worldName == null) {
            return;
        }
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
        ProtectedRegion region = world == null ? null : plugin.wg().byName(world, ctx.get("region"));
        if (region == null) {
            return;
        }
        try {
            com.sk89q.worldedit.math.BlockVector3 min = region.getMinimumPoint();
            com.sk89q.worldedit.math.BlockVector3 max = region.getMaximumPoint();
            int cx = (min.x() + max.x()) / 2;
            int cz = (min.z() + max.z()) / 2;
            int y = Math.max(min.y(), world.getMinHeight()) + 1;
            p.teleport(new org.bukkit.Location(world, cx + 0.5, y, cz + 0.5));
        } catch (Throwable t) {
            p.sendMessage(dev.qqregions.util.Msg.color("&cНе удалось телепортироваться."));
        }
    }

    /** Установить флаг региона через API WG: @flag:<имя>:{значение} (значение allow/deny/true/false). */
    private void setFlag(Player p, Map<String, String> ctx, String spec) {
        String worldName = ctx.get("world");
        org.bukkit.World world = worldName == null ? null : org.bukkit.Bukkit.getWorld(worldName);
        ProtectedRegion region = world == null ? null : plugin.wg().byName(world, ctx.get("region"));
        if (world == null || region == null) {
            return;
        }
        String[] parts = spec.split(":", 2);
        if (parts.length < 2) {
            return;
        }
        String flagName = parts[0].trim();
        String value = parts[1].trim();
        Flag<?> flag = plugin.wg().flag(flagName);
        if (flag == null) {
            return;
        }
        boolean allow = "allow".equalsIgnoreCase(value);
        if (flag instanceof StateFlag) {
            value = allow ? "allow" : "deny";
        } else if (flag instanceof BooleanFlag) {
            value = String.valueOf(allow);
        }
        plugin.wg().setFlagValue(world, region, flag, value);
        OpenMenu om = open.get(p.getUniqueId());
        if (om != null) {
            render(p, om.menu, om.ctx, 0, om.role);
        }
    }

    private String applyContext(Map<String, String> ctx, List<String> cmds) {
        StringBuilder sb = new StringBuilder();
        for (String c : cmds) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String line = c;
            for (Map.Entry<String, String> en : ctx.entrySet()) {
                line = line.replace("{" + en.getKey() + "}", en.getValue() == null ? "" : en.getValue());
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private static class OpenMenu {
        final Player player;
        final Inventory inv;
        final Menu menu;
        final Map<String, String> ctx;
        final Map<Integer, MenuItem> slotMap;
        final String role;
        final int page;
        final int maxPages;
        int ticks;

        OpenMenu(Player player, Inventory inv, Menu menu, Map<String, String> ctx,
                 int page, int maxPages, String role, Map<Integer, MenuItem> slotMap) {
            this.player = player;
            this.inv = inv;
            this.menu = menu;
            this.ctx = ctx;
            this.page = page;
            this.maxPages = maxPages;
            this.role = role;
            this.slotMap = slotMap;
        }
    }
}