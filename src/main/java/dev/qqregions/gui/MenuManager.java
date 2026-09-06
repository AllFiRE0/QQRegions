package dev.qqregions.gui;

import com.sk89q.worldguard.protection.flags.BooleanFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.qqregions.QQRegions;
import org.bukkit.Bukkit;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Менеджер GUI-меню. Загружает файлы из папки menus/, хранит открытые
 * меню и обновляет их по update_interval, исполняет команды кнопок.
 */
public class MenuManager implements Listener {

    /** Режимы сортировки предложений рынка (переключаются @sort). */
    private static final List<String> MARKET_SORT = List.of("name", "price", "default");

    /** файл (без .yml) -> упорядоченные по приоритету шаблоны */
    private final Map<String, List<Menu>> menus = new HashMap<>();
    private final Map<UUID, OpenMenu> open = new HashMap<>();
    /** ожидание ввода ника для добавления: UUID -> контекст промпта.
     *  Храним копию OpenMenu: открытие чата закрывает инвентарь (onClose
     *  чистит open), но промпт должен пережить это и обработать сообщение. */
    private final Map<UUID, AddPrompt> pendingAdd = new ConcurrentHashMap<>();
    /** Ожидание поискового запроса в чат: value = "flag" | "market". */
    private final Map<UUID, String> pendingSearch = new ConcurrentHashMap<>();
    /** История переходов между меню для кнопки @back. */
    private final Map<UUID, Deque<NavState>> history = new HashMap<>();
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
        }
        saveMissingResource("menus/flags.yml", dir);
        saveMissingResource("menus/info.yml", dir);
        saveMissingResource("menus/players.yml", dir);
        saveMissingResource("menus/market.yml", dir);
        saveMissingResource("menus/flagshop.yml", dir);
        saveMissingResource("menus/blocks.yml", dir);
        saveMissingResource("menus/myflags.yml", dir);
        saveMissingResource("menus/help.yml", dir);
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                List<Menu> parsed = Menu.parseFile(plugin, f, 20);
                parsed.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
                menus.put(f.getName().replaceFirst("\\.yml$", ""), parsed);
            }
        }
    }

    /** Скопировать дефолтный файл меню из jar, если его ещё нет на диске. */
    private void saveMissingResource(String path, File dir) {
        File target = new File(dir, path.substring(path.indexOf('/') + 1));
        if (!target.exists()) {
            plugin.saveResource(path, false);
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
        FLAGS, PLAYERS, MARKET, FLAG_SHOP, BLOCK_SHOP, MY_FLAGS
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
        return open(player, menuName, ctx, 0, null, true);
    }

    public boolean open(Player player, String menuName, Map<String, String> ctx, int page) {
        return open(player, menuName, ctx, page, null, true);
    }

    public boolean open(Player player, String menuName, Map<String, String> ctx, int page, String role) {
        return open(player, menuName, ctx, page, role, true);
    }

    /**
     * Открыть меню. При record=true и переходе в ДРУГОЙ файл меню текущее
     * меню запоминается в истории (кнопка @back возвращает на него).
     */
    public boolean open(Player player, String menuName, Map<String, String> ctx, int page, String role, boolean record) {
        Menu best = pick(player, menuName, role);
        if (best == null) {
            return false;
        }
        if (record) {
            pushHistory(player, menuName.toLowerCase(java.util.Locale.ROOT));
        }
        if ("info".equalsIgnoreCase(menuName)) {
            enrichInfoContext(ctx);
        }
        Kind kind = kindOf(menuName);
        return render(player, best, ctx, page, role, kind);
    }

    private Kind kindOf(String menuName) {
        return switch (menuName.toLowerCase(java.util.Locale.ROOT)) {
            case "players" -> Kind.PLAYERS;
            case "market" -> Kind.MARKET;
            case "flagshop" -> Kind.FLAG_SHOP;
            case "blocks" -> Kind.BLOCK_SHOP;
            case "myflags" -> Kind.MY_FLAGS;
            default -> Kind.FLAGS;
        };
    }

    /** Запомнить текущее открытое меню перед переходом в другой файл. */
    private void pushHistory(Player p, String target) {
        OpenMenu cur = open.get(p.getUniqueId());
        if (cur == null || cur.menu.file() == null || cur.menu.file().equalsIgnoreCase(target)) {
            return;
        }
        Deque<NavState> stack = history.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        stack.push(new NavState(cur.menu.file(), new HashMap<>(cur.ctx), cur.role));
        while (stack.size() > 20) {
            stack.removeLast();
        }
    }

    /** Кнопка @back: вернуться к предыдущему меню (без записи в историю). */
    private void goBack(Player p) {
        Deque<NavState> stack = history.get(p.getUniqueId());
        NavState prev = stack == null ? null : stack.pollFirst();
        if (prev == null) {
            if (stack != null && stack.isEmpty()) {
                history.remove(p.getUniqueId());
            }
            p.sendMessage(dev.qqregions.util.Msg.color("&7Нет предыдущего меню."));
            return;
        }
        if (stack.isEmpty()) {
            history.remove(p.getUniqueId());
        }
        open(p, prev.menuName, prev.ctx, 0, prev.role, false);
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

    /** Меню рынка: продажа/аренда регионов. */
    public boolean openMarket(Player player) {
        Map<String, String> ctx = new HashMap<>();
        ctx.put("world", player.getWorld().getName());
        ctx.put("region", "");
        ctx.put("player", player.getName());
        ctx.put("role", "other");
        Menu best = pick(player, "market", null);
        return best != null && render(player, best, ctx, 0, null, Kind.MARKET);
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
        List<MenuItem> dynItems;
        Set<String> owned = plugin.shop().ownedFlags(player.getUniqueId());
        switch (kind) {
            case PLAYERS -> dynItems = playerItems(menu, ctx);
            case MARKET -> dynItems = marketItems(menu, ctx);
            case FLAG_SHOP -> dynItems = flagShopItems(menu, player, ctx);
            case BLOCK_SHOP -> dynItems = blockShopItems(menu, player, ctx);
            case MY_FLAGS -> dynItems = menu.purchasedItems(plugin, player, ctx, owned);
            default -> dynItems = menu.flagItems(plugin, player, ctx, menu.dynamicFlags(), false, owned);
        }
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
        history.clear();
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
// флаг-кнопка: требуется право <prefix><флаг> (из dynamic-flags.flag-permission-prefix).
        // Бесплатные флаги (flags-menu.whitelist) и купленные в магазине кликаются и без права.
        Menu.DynamicFlags dyn = om.menu.dynamicFlags();
        String flagPermPrefix = dyn == null ? null : dyn.permissionPrefix;
        if (item.isDynamic() && item.flag() != null && !item.flag().isEmpty()
                && flagPermPrefix != null && !flagPermPrefix.isEmpty()
                && !p.hasPermission("qqregions.admin")
                && !p.hasPermission(flagPermPrefix + item.flag().toLowerCase(java.util.Locale.ROOT))) {
            String flagKey = item.flag().toLowerCase(java.util.Locale.ROOT);
            if (!plugin.config().flagsMenuWhitelist().contains(flagKey)
                    && !plugin.shop().ownedFlags(p.getUniqueId()).contains(flagKey)) {
                return;
            }
        }
        List<String> cmds = item.commands();
        if (cmds == null) {
            return;
        }

        // Кнопка флага: ПКМ = сменить группу флага (без переключения значения),
        // ЛКМ = сменить значение для текущей группы.
        if (item.isDynamic() && item.flag() != null && !item.flag().isEmpty()
                && (e.isRightClick() || e.getClick() == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT)) {
            cycleFlagGroup(p, om, item);
            return;
        }
        // Меню рынка: ЛКМ = принять, ПКМ = отменить предложение.
        if (om.kind == Kind.MARKET && cmds != null && !cmds.isEmpty()
                && (e.isRightClick() || e.getClick() == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT)) {
            for (String c : cmds) {
                if (c.startsWith("@market:")) {
                    String body = c.substring("@market:".length()).trim();
                    String[] parts = body.split(":", 2);
                    if (parts.length == 2) {
                        marketAction(p, "cancel:" + parts[1]);
                    }
                }
            }
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
                if ("myflags".equalsIgnoreCase(target)) {
                    openMyFlags(p);
                } else {
                    open(p, target, om.ctx, 0, om.role);
                }
                continue;
            }
            if (c.equalsIgnoreCase("@teleport")) {
                teleportToRegion(p, om.ctx, om.role);
                continue;
            }
            if (c.equalsIgnoreCase("@back")) {
                goBack(p);
                continue;
            }
            if (c.equalsIgnoreCase("@highlight")) {
                highlightRegion(p, om.ctx);
                continue;
            }
            if (c.startsWith("@shop-buy:")) {
                shopBuy(p, c.substring("@shop-buy:".length()).trim());
                continue;
            }
            if (c.startsWith("@flag:")) {
                setFlag(p, om, c.substring("@flag:".length()).trim(), item.group());
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
            if (c.startsWith("@market:")) {
                marketAction(p, c.substring("@market:".length()).trim());
                continue;
            }
            if (c.startsWith("@raid:")) {
                raidAction(p, c.substring("@raid:".length()).trim(), om.ctx);
                continue;
            }
            if (c.equalsIgnoreCase("@flag-search")) {
                startSearchPrompt(p, om, "flag");
                continue;
            }
            if (c.equalsIgnoreCase("@market-search")) {
                startSearchPrompt(p, om, "market");
                continue;
            }
            if (c.equalsIgnoreCase("@sort")) {
                cycleSort(p, om);
                continue;
            }
            MenuAction.run(p, dev.qqregions.util.Papi.set(p, c));
        }
    }

    /**
     * Текущее (сырое) значение флага для группы кнопки. Считается локально
     * через API WG (без WGEFP/PAPI): значение хранится одно на флаг и видно
     * только на кнопке текущей группы; для остальных групп — "".
     */
    private String currentValue(OpenMenu om, MenuItem item) {
        String id = item.flag();
        String group = item.group();
        String worldName = om.ctx.get("world");
        if (id == null || worldName == null) {
            return "";
        }
        org.bukkit.World w = org.bukkit.Bukkit.getWorld(worldName);
        ProtectedRegion region = w == null ? null : plugin.wg().byName(w, om.ctx.get("region"));
        Flag<?> flag = region == null ? null : plugin.wg().flag(id);
        if (w == null || region == null || flag == null) {
            return "";
        }
        String v = plugin.wg().flagValueFor(w, region, flag, group);
        return v == null ? "" : v.trim().toLowerCase(java.util.Locale.ROOT);
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
        UUID id = e.getPlayer().getUniqueId();
        open.remove(id);
        // Закрытие инвентаря случается и при открытии чата («введите ник») —
        // тогда промпт должен ПЕРЕЖИТЬ закрытие. Но если игрок просто закрыл
        // меню, ждём 5 сек и убираем промпт, чтобы не глотать следующий чат.
        if (pendingAdd.containsKey(id)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> pendingAdd.remove(id), 100L);
        }
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

    /** Установить флаг региона через API WG: @flag:<имя>:{значение}
     *  (значение allow/deny/true/false). group — группа кнопки
     *  (all/members/owners/nonmembers/nonowners), ставится вместе со значением. */
    private void setFlag(Player p, OpenMenu om, String spec, String group) {
        String worldName = om.ctx.get("world");
        org.bukkit.World world = worldName == null ? null : org.bukkit.Bukkit.getWorld(worldName);
        ProtectedRegion region = world == null ? null : plugin.wg().byName(world, om.ctx.get("region"));
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
        if (group == null || group.isEmpty() || group.equalsIgnoreCase("all")) {
            plugin.wg().setFlagValue(world, region, flag, value, "all");
        } else {
            plugin.wg().setFlagValue(world, region, flag, value, group);
        }
        OpenMenu live = open.get(p.getUniqueId());
        if (live != null) {
            // сохраняем текущую страницу (не сбрасываем на первую)
            render(p, live.menu, live.ctx, live.page, live.role, live.kind);
        }
    }

    /** ПКМ по кнопке флага: циклически переключает группу (region group) флага.
     *  Значение флага сохраняется (переносится на новую группу), затем меню
     *  перерисовывается с новой текущей группой. */
    private void cycleFlagGroup(Player p, OpenMenu om, MenuItem item) {
        String worldName = om.ctx.get("world");
        org.bukkit.World world = worldName == null ? null : org.bukkit.Bukkit.getWorld(worldName);
        ProtectedRegion region = world == null ? null : plugin.wg().byName(world, om.ctx.get("region"));
        Flag<?> flag = region == null ? null : plugin.wg().flag(item.flag());
        if (world == null || region == null || flag == null) {
            return;
        }
        Menu.DynamicFlags dyn = om.menu.dynamicFlags();
        if (dyn == null || dyn.groups == null || dyn.groups.isEmpty()) {
            return;
        }
        String current = plugin.wg().flagGroup(world, region, flag);
        if (current == null || current.isEmpty()) {
            current = "all";
        }
        String next = null;
        for (int i = 0; i < dyn.groups.size(); i++) {
            if (dyn.groups.get(i).equalsIgnoreCase(current)) {
                next = dyn.groups.get((i + 1) % dyn.groups.size());
                break;
            }
        }
        if (next == null) {
            next = dyn.groups.get(0);
        }
        if (next.equalsIgnoreCase(current)) {
            return;
        }
        boolean ok = plugin.wg().setFlagGroup(world, region, flag, next);
        String label = plugin.replace().resolve("flag-groups", next);
        p.sendMessage(dev.qqregions.util.Msg.color(
                (ok ? "&a" : "&c") + "Флаг &f" + flag.getName() + "&r: группа &f" + label
                        + (ok ? "&a." : "&c (не удалось сменить).")));
        OpenMenu live = open.get(p.getUniqueId());
        if (live != null) {
            render(p, live.menu, live.ctx, live.page, live.role, live.kind);
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

    /** Кнопки меню рынка: все живые предложения (PENDING/ACTIVE) на регионы.
     *  ЛКМ — контрагент принимает (@market:accept:<id>), ПКМ-инициатор отменяет.
     *  Поиск (ctx["_marketsearch"]) фильтрует по названию региона;
     *  сортировка (ctx["_sort"]) — name | price | default. */
    private List<MenuItem> marketItems(Menu menu, Map<String, String> ctx) {
        List<MenuItem> out = new ArrayList<>();
        MenuItem tpl = new MenuItem("STONE", 1, null, "", null, null, "");
        String queryCtx = ctx.get("_marketsearch");
        Pattern qpat = queryCtx == null ? null : Menu.searchPattern(queryCtx);
        String sort = ctx.getOrDefault("_sort", "default").toLowerCase(java.util.Locale.ROOT);
        List<dev.qqregions.market.Offer> cols = new ArrayList<>();
        for (dev.qqregions.market.Offer o : plugin.market().offers()) {
            if (o.status != dev.qqregions.market.Offer.Status.PENDING
                    && o.status != dev.qqregions.market.Offer.Status.ACTIVE) {
                continue;
            }
            if (qpat != null && !qpat.matcher(o.region).find()) {
                continue;
            }
            cols.add(o);
        }
        if (sort.equals("name")) {
            cols.sort((a, b) -> a.region.compareToIgnoreCase(b.region));
        } else if (sort.equals("price")) {
            cols.sort((a, b) -> Double.compare(a.price, b.price));
        }
        for (dev.qqregions.market.Offer o : cols) {
            boolean sale = o.kind == dev.qqregions.market.Offer.Kind.SALE;
            String type = sale ? "продажа" : "аренда";
            String who = sale
                    ? plugin.market().nameOf(o.buyer)
                    : plugin.market().nameOf(o.tenant);
            String owner = plugin.market().nameOf(o.owner != null ? o.owner : o.seller);
            Map<String, String> pc = new HashMap<>(ctx);
            pc.put("market-type", type);
            pc.put("market-region", o.region);
            pc.put("market-world", o.world);
            pc.put("market-price", plugin.market().economy().format(o.price));
            pc.put("market-who", who);
            pc.put("market-owner", owner);
            pc.put("market-status", o.status == dev.qqregions.market.Offer.Status.ACTIVE
                    ? (sale ? "активна" : "аренда") : "ожидает");

            String name = tpl.process(plugin, null, pc,
                    "&f{market-type} &8· &7{market-region}");
            List<String> lore = new ArrayList<>();
            lore.add(tpl.process(plugin, null, pc, "&7Цена: &f{market-price}"));
            lore.add(tpl.process(plugin, null, pc,
                    sale ? "&7Покупатель: &f{market-who}" : "&7Арендатор: &f{market-who}"));
            lore.add(tpl.process(plugin, null, pc, "&7Статус: &f{market-status}"));
            lore.add("&9ЛКМ — принять • &eПКМ — отменить");

            List<String> cmds = List.of("@market:accept:" + o.id);
            out.add(new MenuItem(sale ? "GOLD_INGOT" : "EMERALD", 1, null, name, lore, cmds, ""));
        }
        return out;
    }

    /** Обработчик @market:<accept|decline|cancel>:<id> из кнопок меню рынка. */
    private void marketAction(Player p, String spec) {
        String[] parts = spec.split(":", 2);
        if (parts.length < 2) {
            return;
        }
        String action = parts[0].trim().toLowerCase(java.util.Locale.ROOT);
        dev.qqregions.market.Offer o = plugin.market().byId(parts[1].trim());
        if (o == null) {
            p.sendMessage(dev.qqregions.util.Msg.color("&cПредложение не найдено."));
            return;
        }
        String res;
        switch (action) {
            case "accept":
                res = plugin.market().accept(o, p);
                p.sendMessage(dev.qqregions.util.Msg.color("ok".equals(res)
                        ? "&aПринято: &f" + o.region
                        : "&cНе удалось принять: &f" + res));
                break;
            case "decline":
                res = plugin.market().decline(o, p);
                p.sendMessage(dev.qqregions.util.Msg.color("ok".equals(res)
                        ? "&eОтклонено: &f" + o.region
                        : "&cНе удалось отклонить: &f" + res));
                break;
            case "cancel":
                res = plugin.market().cancel(o, p);
                p.sendMessage(dev.qqregions.util.Msg.color("ok".equals(res)
                        ? "&eОтменено: &f" + o.region
                        : "&cНе удалось отменить: &f" + res));
                break;
            default:
                return;
        }
        if (open.containsKey(p.getUniqueId())) {
            OpenMenu live = open.get(p.getUniqueId());
            render(p, live.menu, live.ctx, live.page, live.role, live.kind);
        }
    }

    /** Обработчик @raid:<action> из кнопок меню (запуск рейда). */
    private void raidAction(Player p, String spec, Map<String, String> ctx) {
        String action = spec.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"start".equals(action)) {
            return;
        }
        if (!p.hasPermission("qqregions.admin") && !p.hasPermission("qqregions.raid")) {
            p.sendMessage(dev.qqregions.util.Msg.color("&cУ вас нет права на рейд."));
            return;
        }
        String res;
        org.bukkit.World w = worldFrom(ctx);
        ProtectedRegion r = w == null ? null : plugin.wg().byName(w, ctx.get("region"));
        if (w != null && r != null) {
            res = plugin.raid().start(p, r);
        } else {
            res = plugin.raid().start(p);
        }
        if (res != null) {
            p.sendMessage(dev.qqregions.util.Msg.color("&c" + res));
        } else {
            p.sendMessage(dev.qqregions.util.Msg.color("&aРейд запущен! Все нападающие должны оставаться в регионе."));
        }
    }

    /** Кнопка @highlight: показать подсветку границ текущего региона. */
    private void highlightRegion(Player p, Map<String, String> ctx) {
        org.bukkit.World w = worldFrom(ctx);
        ProtectedRegion r = w == null ? null : plugin.wg().byName(w, ctx.get("region"));
        if (w == null || r == null) {
            p.sendMessage(dev.qqregions.util.Msg.color("&cРегион не найден."));
            return;
        }
        plugin.highlight().show(p, w, r, plugin.highlight().typeOf(p));
    }

    // ---------- магазин флагов и расширений ----------

    /** Меню магазина флагов (flagshop.yml). */
    public boolean openFlagShop(Player p) {
        Map<String, String> ctx = new HashMap<>();
        ctx.put("world", p.getWorld().getName());
        ctx.put("region", "");
        ctx.put("player", p.getName());
        ctx.put("role", "other");
        return open(p, "flagshop", ctx, 0, null, true);
    }

    /** Меню расширений площади/регионов (blocks.yml). */
    public boolean openBlockShop(Player p) {
        Map<String, String> ctx = new HashMap<>();
        ctx.put("world", p.getWorld().getName());
        ctx.put("region", "");
        ctx.put("player", p.getName());
        ctx.put("role", "other");
        return open(p, "blocks", ctx, 0, null, true);
    }

    /** Меню справки (help.yml). */
    public boolean openHelp(Player p) {
        Map<String, String> ctx = new HashMap<>();
        ctx.put("world", p.getWorld().getName());
        ctx.put("region", "");
        ctx.put("player", p.getName());
        ctx.put("role", "other");
        return open(p, "help", ctx, 0, null, true);
    }

    /** «Мои флаги»: список купленных в магазине флагов. */
    public boolean openMyFlags(Player p) {
        if (plugin.shop().ownedFlags(p.getUniqueId()).isEmpty()) {
            p.sendMessage(plugin.lang().compPrefixed("shop.none-owned"));
            return false;
        }
        Map<String, String> ctx = new HashMap<>();
        ctx.put("world", "");
        ctx.put("region", "");
        ctx.put("player", p.getName());
        ctx.put("role", "other");
        return open(p, "myflags", ctx, 0, null, true);
    }

    /** Кнопки магазина флагов: не купленные, не whitelist, не shop-ignore. */
    private List<MenuItem> flagShopItems(Menu menu, Player player, Map<String, String> ctx) {
        List<MenuItem> out = new ArrayList<>();
        MenuItem tpl = new MenuItem("STONE", 1, null, "", null, null, "");
        Set<String> whitelist = plugin.config().flagsMenuWhitelist();
        Set<String> shopIgnore = plugin.config().flagsShopIgnore();
        Set<String> owned = plugin.shop().ownedFlags(player.getUniqueId());
        boolean allFree = whitelist.isEmpty();
        Menu.DynamicFlags dyn = menu.dynamicFlags();
        for (com.sk89q.worldguard.protection.flags.Flag<?> flag : plugin.wg().allFlags()) {
            String id = flag.getName();
            String key = id == null ? "" : id.toLowerCase(java.util.Locale.ROOT);
            if (key.isEmpty() || allFree || whitelist.contains(key)
                    || shopIgnore.contains(key) || owned.contains(key)) {
                continue;
            }
            double price = plugin.shop().priceOf(key);
            if (price <= 0) {
                continue;
            }
            String flagQuery = ctx.get("_flagsearch");
            if (flagQuery != null) {
                String q = flagQuery.trim();
                if (q.isEmpty()) {
                    continue;
                }
                Pattern pat = Menu.searchPattern(q);
                String translated = plugin.config().flagName(id);
                String plain = dev.qqregions.util.Msg.toLegacy(dev.qqregions.util.Msg.color(translated));
                if (!pat.matcher(id).find() && !pat.matcher(translated).find()
                        && !pat.matcher(plain).find()) {
                    continue;
                }
            }
            Map<String, String> pc = new HashMap<>(ctx);
            pc.put("flag-name", plugin.config().flagName(id));
            pc.put("price", plugin.market().economy().format(price));
            String name = tpl.process(plugin, player, pc, "&f{flag-name}");
            List<String> lore = new ArrayList<>();
            lore.add(tpl.process(plugin, player, pc, "&7Цена: &f{price}"));
            lore.add("&7После покупки появится в меню флагов");
            lore.add("&eЛКМ — купить");
            String mat = dyn == null ? null : dyn.materials.get(id);
            out.add(new MenuItem(mat == null ? "EMERALD" : mat, 1, null, name, lore,
                    List.of("@shop-buy:flag:" + key), ""));
        }
        return out;
    }

    /** Кнопки расширений: пакеты площади и пакеты «+регион». */
    private List<MenuItem> blockShopItems(Menu menu, Player player, Map<String, String> ctx) {
        List<MenuItem> out = new ArrayList<>();
        MenuItem tpl = new MenuItem("STONE", 1, null, "", null, null, "");
        Set<String> ownedArea = plugin.shop().ownedAreaPacks(player.getUniqueId());
        for (dev.qqregions.shop.ShopManager.Pack p : plugin.shop().areaPacks()) {
            if (ownedArea.contains(p.id())) {
                continue;
            }
            Map<String, String> pc = new HashMap<>(ctx);
            pc.put("pack-name", p.name());
            pc.put("pack-amount", String.valueOf(p.amount()));
            pc.put("price", plugin.market().economy().format(p.price()));
            String name = tpl.process(plugin, player, pc, "&f{pack-name}");
            List<String> lore = new ArrayList<>();
            lore.add(tpl.process(plugin, player, pc, "&7Увеличивает выделение на &f{pack-amount}&7 блоков"));
            lore.add(tpl.process(plugin, player, pc, "&7Цена: &f{price}"));
            lore.add("&eЛКМ — купить");
            out.add(new MenuItem("GOLD_INGOT", 1, null, name, lore,
                    List.of("@shop-buy:area:" + p.id()), ""));
        }
        for (dev.qqregions.shop.ShopManager.Pack p : plugin.shop().regionPacks()) {
            Map<String, String> pc = new HashMap<>(ctx);
            pc.put("pack-name", p.name());
            pc.put("pack-amount", String.valueOf(p.amount()));
            pc.put("price", plugin.market().economy().format(p.price()));
            String name = tpl.process(plugin, player, pc, "&f{pack-name}");
            List<String> lore = new ArrayList<>();
            lore.add(tpl.process(plugin, player, pc, "&7Добавляет &f{pack-amount}&7 к лимиту регионов"));
            lore.add(tpl.process(plugin, player, pc, "&7Цена: &f{price} &8(повторяемый)"));
            lore.add("&eЛКМ — купить");
            out.add(new MenuItem("EMERALD", 1, null, name, lore,
                    List.of("@shop-buy:region:" + p.id()), ""));
        }
        return out;
    }

    /** Обработчик @shop-buy:<flag|area|region>:<id>. */
    private void shopBuy(Player p, String spec) {
        String[] parts = spec.split(":", 2);
        if (parts.length < 2) {
            return;
        }
        String kind = parts[0].trim().toLowerCase(java.util.Locale.ROOT);
        String id = parts[1].trim();
        String res;
        switch (kind) {
            case "flag" -> res = plugin.shop().buyFlag(p.getUniqueId(), id);
            case "area" -> res = plugin.shop().buyAreaPack(p.getUniqueId(), id);
            case "region" -> res = plugin.shop().buyRegionPack(p.getUniqueId(), id);
            default -> {
                return;
            }
        }
        switch (res) {
            case "ok" -> {
                if ("flag".equals(kind)) {
                    p.sendMessage(plugin.lang().compPrefixed("shop.flag-bought",
                            "flag-name", plugin.config().flagName(id),
                            "price", plugin.market().economy().format(plugin.shop().priceOf(id))));
                } else {
                    p.sendMessage(plugin.lang().compPrefixed("shop.pack-bought",
                            "pack-name", packName(kind, id),
                            "price", plugin.market().economy().format(packPrice(kind, id))));
                }
            }
            case "already" -> p.sendMessage(plugin.lang().compPrefixed("shop.already",
                    "flag-name", plugin.config().flagName(id)));
            case "no-money" -> p.sendMessage(plugin.lang().compPrefixed("shop.no-money"));
            case "not-found" -> p.sendMessage(plugin.lang().compPrefixed("shop.not-found"));
            case "no-economy" -> p.sendMessage(plugin.lang().compPrefixed("shop.no-economy"));
            default -> p.sendMessage(plugin.lang().compPrefixed("shop.error"));
        }
        OpenMenu live = open.get(p.getUniqueId());
        if (live != null) {
            render(p, live.menu, live.ctx, live.page, live.role, live.kind);
        }
    }

    private String packName(String kind, String id) {
        for (dev.qqregions.shop.ShopManager.Pack p : packList(kind)) {
            if (p.id().equalsIgnoreCase(id)) {
                return p.name();
            }
        }
        return id;
    }

    private double packPrice(String kind, String id) {
        for (dev.qqregions.shop.ShopManager.Pack p : packList(kind)) {
            if (p.id().equalsIgnoreCase(id)) {
                return p.price();
            }
        }
        return 0;
    }

    private List<dev.qqregions.shop.ShopManager.Pack> packList(String kind) {
        return "region".equals(kind) ? plugin.shop().regionPacks() : plugin.shop().areaPacks();
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

    /** Начать поиск флагов по названию (по id И переводу {flag-name}). */
    private void startSearchPrompt(Player p, OpenMenu om, String kind) {
        pendingSearch.put(p.getUniqueId(), kind);
        p.sendMessage(plugin.lang().comp("market.search-prompt"));
        p.sendMessage(plugin.lang().comp("market.search-cancel"));
    }

    /** Цикл сортировки предложений рынка: Название → Цена → По умолчанию. */
    private void cycleSort(Player p, OpenMenu om) {
        String cur = om.ctx.getOrDefault("_sort", "default");
        int idx = MARKET_SORT.indexOf(cur);
        String next = MARKET_SORT.get((idx + 1) % MARKET_SORT.size());
        om.ctx.put("_sort", next);
        p.sendMessage(plugin.lang().comp("market.sort-set", "mode", next));
        render(p, om.menu, om.ctx, om.page, om.role, om.kind);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSearchChat(AsyncPlayerChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        String kind = pendingSearch.get(id);
        if (kind == null) {
            return;
        }
        e.setCancelled(true);
        final String text = e.getMessage().trim();
        pendingSearch.remove(id);
        if (text.isEmpty()) {
            e.getPlayer().sendMessage(plugin.lang().comp("market.search-off"));
            return;
        }
        if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("отмена")
                || text.equalsIgnoreCase("сброс") || text.equalsIgnoreCase("off")) {
            e.getPlayer().sendMessage(plugin.lang().comp("market.search-off"));
            return;
        }
        final String q = text;
        final UUID pid = id;
        plugin.getServer().getScheduler().runTask(plugin, () -> applySearch(pid, kind, q));
    }

    private void applySearch(UUID id, String kind, String query) {
        Player p = plugin.getServer().getPlayer(id);
        if (p == null || !p.isOnline()) {
            return;
        }
        OpenMenu om = open.get(id);
        if (om == null) {
            return;
        }
        if ("market".equals(kind)) {
            om.ctx.put("_marketsearch", query);
            p.sendMessage(plugin.lang().comp("market.search-set", "query", query));
        } else {
            om.ctx.put("_flagsearch", query);
            p.sendMessage(plugin.lang().comp("market.search-set", "query", query));
        }
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
        pendingAdd.put(p.getUniqueId(),
                new AddPrompt(om, kind.toLowerCase(java.util.Locale.ROOT)));
        p.sendMessage(dev.qqregions.util.Msg.color(
                "&eВведите в чат ник игрока, которого добавить "
                        + ("owner".equalsIgnoreCase(kind) ? "&bвладельцем&e" : "&eучастником")
                        + ". Напишите &cотмена&e, чтобы отменить."));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        AddPrompt pr = pendingAdd.get(id);
        if (pr == null) {
            return;
        }
        e.setCancelled(true);
        final String text = e.getMessage().trim();
        pendingAdd.remove(id);
        final UUID pid = id;
        plugin.getServer().getScheduler().runTask(plugin, () -> addPlayerFromChat(pid, pr, text));
    }

    private void addPlayerFromChat(UUID id, AddPrompt pr, String name) {
        Player p = pr.om.player;
        if (!p.isOnline()) {
            return;
        }
        if (name.isEmpty() || name.equalsIgnoreCase("отмена") || name.equalsIgnoreCase("cancel")) {
            p.sendMessage(dev.qqregions.util.Msg.color("&7Добавление отменено."));
            return;
        }
        org.bukkit.World world = worldFrom(pr.om.ctx);
        ProtectedRegion region = world == null ? null : plugin.wg().byName(world, pr.om.ctx.get("region"));
        if (world == null || region == null) {
            p.sendMessage(dev.qqregions.util.Msg.color("&cРегион не найден."));
            return;
        }
        plugin.wg().addPlayerByName(world, region, name, "owner".equalsIgnoreCase(pr.kind));
        p.sendMessage(dev.qqregions.util.Msg.color("&aИгрок &f" + name + " &aдобавлен"
                + ("owner".equalsIgnoreCase(pr.kind) ? " как владелец." : " как участник.")));
        // Меню мог закрыться, когда игрок открыл чат (клиент закрывает инвентарь).
        // Если закрыто — переоткрываем список игроков, чтобы было видно результат.
        if (open.containsKey(id)) {
            OpenMenu live = open.get(id);
            render(p, live.menu, live.ctx, live.page, live.role, live.kind);
        } else {
            open(p, "players", pr.om.ctx, 0, pr.om.role);
        }
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

    /** Контекст промпта «введите ник»: копия OpenMenu + роль добавления
     *  ("owner"/"member"). Переживает закрытие меню открытием чата. */
    private static class AddPrompt {
        final OpenMenu om;
        final String kind;

        AddPrompt(OpenMenu om, String kind) {
            this.om = om;
            this.kind = kind;
        }
    }

    /** Точка истории для кнопки @back (откуда пришли, чтобы вернуться). */
    private record NavState(String menuName, Map<String, String> ctx, String role) {
    }
}