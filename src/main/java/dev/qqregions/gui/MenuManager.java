package dev.qqregions.gui;

import com.sk89q.worldguard.protection.flags.BooleanFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.qqregions.QQRegions;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Менеджер GUI-меню. Загружает файлы из папки menus/, хранит открытые
 * меню и обновляет их по update_interval, исполняет команды кнопок.
 */
public class MenuManager implements Listener {

    /** файл (без .yml) -> упорядоченные по приоритету шаблоны */
    private final Map<String, List<Menu>> menus = new HashMap<>();
    private final Map<UUID, OpenMenu> open = new HashMap<>();
    /** ожидание ввода ника для добавления: UUID -> "owner"|"member" */
    private final Map<UUID, String> pendingAdd = new ConcurrentHashMap<>();
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
            plugin.saveResource("menus/players.yml", false);
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

    /** Меню игроков региона: список владельцев/участников (клик = убрать),
     * кнопки добавления владельца/участника через ввод ника в чат. */
    public boolean openPlayers(Player player, org.bukkit.World world, ProtectedRegion region) {
        String role = roleOf(player, region);
        Map<String, String> ctx = new HashMap<>();
        ctx.put("region", region.getId());
        ctx.put("world", world.getName());
        ctx.put("player", player.getName());
        ctx.put("role", role);
        ctx.put("owners", plugin.wg().owners(region));
        ctx.put("members", plugin.wg().members(region));
        Menu best = pick(player, "players", role);
        return best != null && render(player, best, ctx, 0, role, Kind.PLAYERS);
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

    /** Чем заполняются динамические слоты меню. */
    private enum Kind {
        FLAGS, PLAYERS
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
        Menu best = pick(player, menuName, role);
        if (best == null) {
            return false;
        }
        if ("info".equalsIgnoreCase(menuName)) {
            enrichInfoContext(ctx);
        }
        Kind kind = "players".equalsIgnoreCase(menuName) ? Kind.PLAYERS : Kind.FLAGS;
        return render(player, best, ctx, page, role, kind);
    }

    /** До-заполнить контекст информационного меню, если его открыли из другого. */
    private void enrichInfoContext(Map<String, String> ctx) {
        if (ctx.containsKey("type") && ctx.get("type") != null) {
            return;
        }
        org.bukkit.World w = worldFrom(ctx);
        ProtectedRegion region = w == null ? null : plugin.wg().byName(w, ctx.get("region"));
        if (w == null || region == null) {
            return;
        }
        ctx.put("type", region.getType().getName());
        ctx.put("area", String.valueOf(regionArea(region)));
        ctx.put("volume", String.valueOf(region.volume()));
        ctx.put("priority", String.valueOf(safePriority(region)));
        ctx.put("status", statusOf(region));
    }

    /** Шаблон меню с наибольшим приоритетом, подходящий роли и игроку. */
    private Menu pick(Player player, String menuName, String role) {
        List<Menu> candidates = menus.get(menuName.toLowerCase(java.util.Locale.ROOT));
        if (candidates == null) {
            return null;
        }
        for (Menu m : candidates) {
            if (!m.roleMatches(role)) {
                continue;
            }
            if (m.matches(player)) {
                return m;
            }
        }
        return null;
    }

    /** Перерендерить инвентарь меню (kind: чем заполняются динамические слоты). */
    private boolean render(Player player, Menu menu, Map<String, String> ctx, int page, String role, Kind kind) {
        List<MenuItem> dynItems = kind == Kind.PLAYERS
                ? playerItems(menu, ctx)
                : menu.dynamicFlags(plugin, player, ctx);
        int maxPages = menu.maxPages(dynItems.size());
        int safePage = Math.max(0, Math.min(maxPages - 1, page));
        Map<Integer, MenuItem> slotMap = new HashMap<>();
        Inventory inv = menu.build(plugin, player, ctx, safePage, maxPages, dynItems, slotMap);
        player.openInventory(inv);
        open.put(player.getUniqueId(), new OpenMenu(player, inv, menu, ctx, safePage, maxPages, role, slotMap, kind));
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
                render(om.player, om.menu, om.ctx, om.page, om.role, om.kind);
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
                    render(p, om.menu, om.ctx, om.page - 1, om.role, om.kind);
                } else if (arg.equalsIgnoreCase("next") && om.page < om.maxPages - 1) {
                    render(p, om.menu, om.ctx, om.page + 1, om.role, om.kind);
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
            if (c.startsWith("@player-del:")) {
                removeParticipant(p, om, c.substring("@player-del:".length()).trim());
                continue;
            }
            if (c.startsWith("@add:")) {
                startPrompt(p, om, c.substring("@add:".length()).trim());
                continue;
            }
            if (c.startsWith("@pf:")) {
                String f = c.substring("@pf:".length()).trim().toLowerCase(java.util.Locale.ROOT);
                if (f.equals("members") || f.equals("owners") || f.equals("all")) {
                    om.ctx.put("_filter", f);
                    render(p, om.menu, om.ctx, om.page, om.role, om.kind);
                }
                continue;
            }
            MenuAction.run(p, dev.qqregions.util.Papi.set(p, c));
        }
    }

    /**
     * Текущее значение флага для группы кнопки.
     * Для группы "all" читаем напрямую из API региона (работает без PAPI);
     * для остальных групп — через заполнитель WorldGuard.
     */
    private String currentValue(OpenMenu om, MenuItem item) {
        String id = item.flag();
        String group = item.group();
        if (group == null || group.equalsIgnoreCase("all")) {
            String worldName = om.ctx.get("world");
            if (worldName != null) {
                org.bukkit.World w = org.bukkit.Bukkit.getWorld(worldName);
                ProtectedRegion region = w == null ? null : plugin.wg().byName(w, om.ctx.get("region"));
                Flag<?> flag = region == null ? null : plugin.wg().flag(id);
                if (w != null && region != null && flag != null) {
                    String v = plugin.wg().flagValue(w, region, flag);
                    if (v != null && !v.isEmpty()) {
                        return v.trim().toLowerCase(java.util.Locale.ROOT);
                    }
                }
            }
        }
        String ph = (group == null || group.equalsIgnoreCase("all"))
                ? "%worldguard_region_has_flag_" + id + "%"
                : "%worldguard_region_has_flag_" + id + ":" + group + "%";
        String value = dev.qqregions.util.Papi.set(om.player, ph);
        if (value == null || value.isBlank() || value.contains("%")) {
            return "";
        }
        return value.trim().toLowerCase(java.util.Locale.ROOT);
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
            // сохраняем текущую страницу (не сбрасываем на первую)
            render(p, om.menu, om.ctx, om.page, om.role, om.kind);
        }
    }

    /** Кнопки списка участников (dynamic-players): владельцы, затем участники.
     * Имя/lore берутся из конфига меню; PAPI резолвится на КОНКРЕТНОГО игрока
     * (например %vault_eco_balance%). Владельцу шаблон задаёт команду
     * @player-del:{player-id}:{role}; у остальных ролей — просмотр без команд.
     * Фильтр: ctx["_filter"] = all|owners|members. */
    private List<MenuItem> playerItems(Menu menu, Map<String, String> ctx) {
        List<MenuItem> out = new ArrayList<>();
        Menu.DynamicPlayers dp = menu.dynamicPlayers();
        if (dp == null) {
            return out;
        }
        String worldName = ctx.get("world");
        if (worldName == null) {
            return out;
        }
        org.bukkit.World w = org.bukkit.Bukkit.getWorld(worldName);
        ProtectedRegion region = w == null ? null : plugin.wg().byName(w, ctx.get("region"));
        if (w == null || region == null) {
            return out;
        }
        String filter = ctx.getOrDefault("_filter", "all").toLowerCase(java.util.Locale.ROOT);
        MenuItem template = new MenuItem(dp.material, 1, null, dp.name, dp.lore, dp.commands,
                "", null, null, null, false);
        for (dev.qqregions.wg.Wg.Participant part : plugin.wg().participants(region)) {
            if (("members".equals(filter) && part.owner())
                    || ("owners".equals(filter) && !part.owner())) {
                continue;
            }
            String label = part.name() != null
                    ? part.name()
                    : (part.uuid() != null ? part.uuid().toString().substring(0, 8) : "?");
            org.bukkit.OfflinePlayer op = part.uuid() == null ? null : org.bukkit.Bukkit.getOfflinePlayer(part.uuid());
            Map<String, String> pc = new HashMap<>(ctx);
            pc.put("player", label);
            pc.put("player-id", part.uuid() != null ? part.uuid().toString() : label);
            pc.put("role", part.owner() ? "owner" : "member");
            pc.put("role-ru", part.owner() ? "Владелец" : "Участник");

            String name = template.process(plugin, op, pc, dp.name);
            List<String> lore = null;
            if (dp.lore != null && !dp.lore.isEmpty()) {
                lore = new ArrayList<>();
                for (String l : dp.lore) {
                    lore.add(l == null ? "" : template.process(plugin, op, pc, l));
                }
            }
            List<String> cmds = null;
            if (dp.commands != null && !dp.commands.isEmpty()) {
                cmds = new ArrayList<>();
                for (String c : dp.commands) {
                    cmds.add(template.process(plugin, op, pc, c));
                }
            }
            Material mat = Material.matchMaterial(part.owner() ? dp.ownerMaterial : dp.memberMaterial);
            if (mat == null) {
                mat = Material.matchMaterial(dp.material);
            }
            if (mat == null) {
                mat = Material.GOLD_INGOT;
            }
            out.add(new MenuItem(mat.name(), 1, null, name, lore, cmds, ""));
        }
        return out;
    }

    /** Кнопка входа на псевдокоманды меню игроков. */
    private void removeParticipant(Player p, OpenMenu om, String spec) {
        if (!p.hasPermission("qqregions.admin") && !"owner".equalsIgnoreCase(om.role)) {
            p.sendMessage(dev.qqregions.util.Msg.color("&cУправление участниками доступно только владельцам."));
            return;
        }
        String[] parts = spec.split(":", 2);
        if (parts.length < 2) {
            return;
        }
        String id = parts[0].trim();
        boolean owner = "owner".equalsIgnoreCase(parts[1].trim());
        org.bukkit.World world = worldFrom(om.ctx);
        ProtectedRegion region = world == null ? null : plugin.wg().byName(world, om.ctx.get("region"));
        if (world == null || region == null) {
            return;
        }
        plugin.wg().removePlayerId(world, region, id, owner);
        p.sendMessage(dev.qqregions.util.Msg.color("&aИгрок убран из региона."));
        render(p, om.menu, om.ctx, om.page, om.role, om.kind);
    }

    /** Начать ввод ника в чат для добавления владельца/участника. */
    private void startPrompt(Player p, OpenMenu om, String kind) {
        if (!p.hasPermission("qqregions.admin") && !"owner".equalsIgnoreCase(om.role)) {
            p.sendMessage(dev.qqregions.util.Msg.color("&cУправление участниками доступно только владельцам."));
            return;
        }
        if (!"owner".equalsIgnoreCase(kind) && !"member".equalsIgnoreCase(kind)) {
            return;
        }
        pendingAdd.put(p.getUniqueId(), kind.toLowerCase(java.util.Locale.ROOT));
        p.sendMessage(dev.qqregions.util.Msg.color(
                "&eВведите в чат ник игрока, которого добавить "
                        + ("owner".equalsIgnoreCase(kind) ? "&bвладельцем&e" : "&eучастником")
                        + ". Напишите &cотмена&e, чтобы отменить."));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (!pendingAdd.containsKey(id)) {
            return;
        }
        e.setCancelled(true);
        final String text = e.getMessage().trim();
        final String kind = pendingAdd.remove(id);
        final UUID pid = id;
        plugin.getServer().getScheduler().runTask(plugin, () -> addPlayerFromChat(pid, kind, text));
    }

    private void addPlayerFromChat(UUID id, String kind, String name) {
        OpenMenu om = open.get(id);
        if (om == null) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null && p.isOnline()) {
                p.sendMessage(dev.qqregions.util.Msg.color("&cМеню закрыто — добавление отменено."));
            }
            return;
        }
        Player p = om.player;
        if (name.isEmpty() || name.equalsIgnoreCase("отмена") || name.equalsIgnoreCase("cancel")) {
            p.sendMessage(dev.qqregions.util.Msg.color("&7Добавление отменено."));
            return;
        }
        org.bukkit.World world = worldFrom(om.ctx);
        ProtectedRegion region = world == null ? null : plugin.wg().byName(world, om.ctx.get("region"));
        if (world == null || region == null) {
            p.sendMessage(dev.qqregions.util.Msg.color("&cРегион не найден."));
            return;
        }
        plugin.wg().addPlayerByName(world, region, name, "owner".equalsIgnoreCase(kind));
        p.sendMessage(dev.qqregions.util.Msg.color("&aИгрок &f" + name + " &aдобавлен"
                + ("owner".equalsIgnoreCase(kind) ? " как владелец." : " как участник.")));
        render(p, om.menu, om.ctx, om.page, om.role, om.kind);
    }

    private static org.bukkit.World worldFrom(Map<String, String> ctx) {
        String name = ctx.get("world");
        return name == null ? null : org.bukkit.Bukkit.getWorld(name);
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
        final Kind kind;
        int ticks;

        OpenMenu(Player player, Inventory inv, Menu menu, Map<String, String> ctx,
                 int page, int maxPages, String role, Map<Integer, MenuItem> slotMap, Kind kind) {
            this.player = player;
            this.inv = inv;
            this.menu = menu;
            this.ctx = ctx;
            this.page = page;
            this.maxPages = maxPages;
            this.role = role;
            this.slotMap = slotMap;
            this.kind = kind;
        }
    }
}