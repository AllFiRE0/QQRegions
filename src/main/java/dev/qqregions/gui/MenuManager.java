package dev.qqregions.gui;

import com.sk89q.worldguard.protection.flags.BooleanFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.qqregions.QQRegions;
import dev.qqregions.config.Config;
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
    /** open-commands: команда -> файл меню (свои команды-открыватели). */
    private final Map<String, String> openCommandMenus = new HashMap<>();
    private final java.util.List<org.bukkit.command.Command> registeredOpen = new ArrayList<>();
    private final java.util.List<String> registeredOpenNames = new ArrayList<>();
    private final Map<UUID, OpenMenu> open = new HashMap<>();
    /** ожидание ввода ника для добавления: UUID -> контекст промпта.
     *  Храним копию OpenMenu: открытие чата закрывает инвентарь (onClose
     *  чистит open), но промпт должен пережить это и обработать сообщение. */
    private final Map<UUID, AddPrompt> pendingAdd = new ConcurrentHashMap<>();
    /** Ожидание поискового запроса в чат: значение = SearchPrompt(kind: flag|market|region). */
    private final Map<UUID, SearchPrompt> pendingSearch = new ConcurrentHashMap<>();
    /** Ожидание ввода срока объявления аренды в чат: UUID -> предложение. */
    private final Map<UUID, dev.qqregions.market.Offer> pendingDur = new ConcurrentHashMap<>();
    /** Ожидание ввода срока АРЕНДЫ (период арендатора) в чат: UUID -> предложение. */
    private final Map<UUID, dev.qqregions.market.Offer> pendingPeriod = new ConcurrentHashMap<>();
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
        // Авто-обновление файлов меню из jar: при смене config-version
        // файл пересоздаётся (старая версия — <имя>.old), новые кнопки и
        // раскладки подхватываются без ручной правки.
        String[] menuResources = {
                "menus/flags.yml", "menus/info.yml", "menus/players.yml",
                "menus/market.yml", "menus/flagshop.yml", "menus/blocks.yml",
                "menus/myflags.yml", "menus/help.yml", "menus/main.yml",
                "menus/regionsearch.yml", "menus/marketconfirm.yml"
        };
        for (String r : menuResources) {
            dev.qqregions.util.Yml.upgrade(plugin, r, new File(dir, r.substring(r.indexOf('/') + 1)));
        }
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                List<Menu> parsed = Menu.parseFile(plugin, f, 20);
                parsed.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
                menus.put(f.getName().replaceFirst("\\.yml$", ""), parsed);
            }
        }
        registerOpenCommands();
    }

    /** Зарегистрировать open-commands из всех меню (команда = открыть меню). */
    private void registerOpenCommands() {
        unregisterOpenCommands();
        openCommandMenus.clear();
        for (Map.Entry<String, List<Menu>> e : menus.entrySet()) {
            for (Menu m : e.getValue()) {
                for (String cmd : m.openCommands()) {
                    openCommandMenus.putIfAbsent(cmd, e.getKey());
                }
            }
        }
        if (openCommandMenus.isEmpty()) {
            return;
        }
        Map<String, org.bukkit.command.Command> known = commandMap().getKnownCommands();
        for (Map.Entry<String, String> e : openCommandMenus.entrySet()) {
            String name = e.getKey();
            org.bukkit.command.Command c = new org.bukkit.command.Command(name,
                    "QQRegions menu shortcut", "/" + name, java.util.List.of()) {
                @Override
                public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
                    if (!(sender instanceof Player p)) {
                        return false;
                    }
                    String menuName = openCommandMenus.get(name);
                    if (menuName == null) {
                        return false;
                    }
                    return open(p, menuName, defaultCtx(p), 0, null, true);
                }
            };
            commandMap().register(name, "qqregions", c);
            known.putIfAbsent(name, c);
            registeredOpen.add(c);
            registeredOpenNames.add(name);
        }
    }

    private void unregisterOpenCommands() {
        if (registeredOpen.isEmpty()) {
            return;
        }
        Map<String, org.bukkit.command.Command> known = commandMap().getKnownCommands();
        for (String name : registeredOpenNames) {
            org.bukkit.command.Command cmd = known.get(name);
            if (cmd != null && registeredOpen.contains(cmd)) {
                known.remove(name);
            }
        }
        for (org.bukkit.command.Command c : registeredOpen) {
            c.unregister(commandMap());
        }
        registeredOpen.clear();
        registeredOpenNames.clear();
    }

    /** Стандартный контекст для открытия своего меню командой. */
    private Map<String, String> defaultCtx(Player p) {
        Map<String, String> ctx = new HashMap<>();
        ctx.put("world", p.getWorld().getName());
        ProtectedRegion r = plugin.wg().current(p);
        ctx.put("region", r == null ? "" : r.getId());
        ctx.put("player", p.getName());
        ctx.put("role", "other");
        if (r != null) {
            ctx.put("region-name", r.getId());
            ctx.put("owners", multilineNicks(r, true));
            ctx.put("members", multilineNicks(r, false));
        }
        return ctx;
    }

    private org.bukkit.command.CommandMap commandMap() {
        try {
            return Bukkit.getCommandMap();
        } catch (Throwable t) {
            try {
                java.lang.reflect.Method m = Bukkit.getServer().getClass().getMethod("getCommandMap");
                return (org.bukkit.command.CommandMap) m.invoke(Bukkit.getServer());
            } catch (Throwable t2) {
                throw new IllegalStateException("Командная карта недоступна", t2);
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
        FLAGS, PLAYERS, MARKET, FLAG_SHOP, BLOCK_SHOP, MY_FLAGS, MAIN, REGION_SEARCH
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
            case "main" -> Kind.MAIN;
            case "regionsearch" -> Kind.REGION_SEARCH;
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

    /** Кнопка @back: вернуться к предыдущему меню (без записи в историю).
     *  Если истории нет (меню открыто командой /region market и т.п.) —
     *  открываем главное меню территорий, а не «молчим». */
    private void goBack(Player p) {
        Deque<NavState> stack = history.get(p.getUniqueId());
        NavState prev = stack == null ? null : stack.pollFirst();
        if (prev == null) {
            if (stack != null && stack.isEmpty()) {
                history.remove(p.getUniqueId());
            }
            open(p, "main", new HashMap<>(), 0, null, false);
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

    /** Главное меню территорий: открывается по /region (и алиасам) без аргументов.
     *  Слот 0 «Назад» добавляется только если включено в config.yml
     *  (interactive.main-menu.back-enabled) — по умолчанию выключен. */
    public boolean openMain(Player player) {
        Map<String, String> ctx = new HashMap<>();
        ctx.put("world", player.getWorld().getName());
        ctx.put("region", "");
        ctx.put("player", player.getName());
        ctx.put("role", "other");
        // «Мой регион»: если стоим в регионе — подкладываем имя и списки
        // владельцев/участников для лора кнопки; иначе — пустые строки.
        ProtectedRegion here = plugin.wg().current(player);
        if (here != null) {
            ctx.put("region", here.getId());
            ctx.put("region-name", here.getId());
            ctx.put("owners", multilineNicks(here, true));
            ctx.put("members", multilineNicks(here, false));
        } else {
            ctx.put("region-name", "");
            ctx.put("owners", "");
            ctx.put("members", "");
        }
        Menu best = pick(player, "main", null);
        if (best == null) {
            return false;
        }
        List<MenuItem> dynItems = marketItems(best, player, ctx);
        int maxPages = best.maxPages(dynItems.size());
        applyBackTarget(player, ctx);
        Map<Integer, MenuItem> slotMap = new HashMap<>();
        Inventory inv = best.build(plugin, player, ctx, 0, maxPages, dynItems, slotMap);
        if (plugin.config().mainMenuBackEnabled()) {
            String cmd = plugin.config().mainMenuBackCommand();
            if (cmd != null && !cmd.trim().isEmpty()) {
                MenuItem back = new MenuItem("ARROW", 1, 0,
                        plugin.lang().get("menu.main-back-name"),
                        List.of(plugin.lang().get("menu.main-back-lore")),
                        List.of(cmd.trim()), "");
                if (inv.getItem(0) == null) {
                    inv.setItem(0, back.build(plugin, player, ctx));
                    slotMap.put(0, back);
                }
            }
        }
        player.openInventory(inv);
        open.put(player.getUniqueId(), new OpenMenu(player, inv, best, ctx, 0, maxPages, null, slotMap, Kind.MAIN));
        return true;
    }

    /** Ники владельцев/участников каждый на своей строке (для лора кнопки). */
    private String multilineNicks(ProtectedRegion region, boolean owner) {
        List<String> nick = new ArrayList<>();
        for (dev.qqregions.wg.Wg.Participant pa : plugin.wg().participants(region)) {
            if (pa.owner() == owner) {
                nick.add(pa.name());
            }
        }
        if (nick.isEmpty()) {
            return "&7—";
        }
        StringBuilder sb = new StringBuilder();
        for (String n : nick) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("&f  ").append(n);
        }
        return sb.toString();
    }

    /** «Инфо по региону» из главного меню: регион, в котором стоит игрок.
     *  Открывает info через openInfo(), чтобы подбирался шаблон по РОЛИ игрока
     *  (владелец/участник/все остальные) и контекст заполнялся полностью. */
    private void openInfoCurrent(Player p, OpenMenu om) {
        ProtectedRegion r = plugin.wg().current(p);
        if (r == null) {
            plugin.lang().send(p, "info.none");
            OpenMenu live = open.get(p.getUniqueId());
            if (live != null) {
                render(p, live.menu, live.ctx, live.page, live.role, live.kind);
            }
            return;
        }
        if (!openInfo(p, p.getWorld(), r)) {
            plugin.lang().send(p, "info.menu-disabled");
            OpenMenu live = open.get(p.getUniqueId());
            if (live != null) {
                render(p, live.menu, live.ctx, live.page, live.role, live.kind);
            }
        }
    }

    /** Разрешено ли действие при включённом guard: TPS >= min-tps и пинг <= max-ping. */
    private static boolean guardOk(Player p, Config.GuardOptions g) {
        if (Config.GuardOptions.tps() < g.minTps) {
            return false;
        }
        try {
            if (g.maxPing > 0 && p.getPing() > g.maxPing) {
                return false;
            }
        } catch (Throwable ignored) {
            // старый API без getPing — проверку пинга пропускаем
        }
        return true;
    }

    /** Перерендерить инвентарь меню (kind: чем заполняются динамические слоты). */
    private boolean render(Player player, Menu menu, Map<String, String> ctx, int page, String role, Kind kind) {
        // Рынок: подставлять название кнопки-вкладки («Мои объявления»/«Все объявления»)
        // и заголовок меню {market-title} — чтобы по заголовку было видно,
        // в какой вкладке игрок находится.
        if (kind == Kind.MARKET) {
            boolean mineView = truthy(ctx.get("_mine"));
            ctx.put("market-tab-name", mineView
                    ? plugin.lang().get("menu.market-tab-all")
                    : plugin.lang().get("menu.market-tab-mine"));
            ctx.put("market-title", mineView
                    ? plugin.lang().get("menu.market-title-mine")
                    : plugin.lang().get("menu.market-title-all"));
        }
        List<MenuItem> dynItems;
        Set<String> owned = plugin.shop().ownedFlags(player.getUniqueId());
        switch (kind) {
            case PLAYERS -> dynItems = playerItems(menu, ctx);
            case MARKET -> dynItems = marketItems(menu, player, ctx);
            case FLAG_SHOP -> dynItems = flagShopItems(menu, player, ctx);
            case BLOCK_SHOP -> dynItems = blockShopItems(menu, player, ctx);
            case MY_FLAGS -> dynItems = menu.purchasedItems(plugin, player, ctx, owned);
            case MAIN -> dynItems = marketItems(menu, player, ctx);
            case REGION_SEARCH -> dynItems = regionSearchItems(menu, player, ctx);
            default -> dynItems = menu.flagItems(plugin, player, ctx, menu.dynamicFlags(), false, owned);
        }
        int maxPages = menu.maxPages(dynItems.size());
        int safePage = Math.max(0, Math.min(maxPages - 1, page));
        applyBackTarget(player, ctx);
        Map<Integer, MenuItem> slotMap = new HashMap<>();
        Inventory inv = menu.build(plugin, player, ctx, safePage, maxPages, dynItems, slotMap);
        player.openInventory(inv);
        open.put(player.getUniqueId(), new OpenMenu(player, inv, menu, ctx, safePage, maxPages, role, slotMap, kind));
        return true;
    }

    /** Кнопка «Вернуться…» в верхнем левом углу: подставляем в контекст
     *  {back-target} — название меню, в которое ведёт @back (из истории). */
    private void applyBackTarget(Player p, Map<String, String> ctx) {
        String label = "";
        Deque<NavState> stack = history.get(p.getUniqueId());
        if (stack != null && !stack.isEmpty()) {
            NavState top = stack.peekFirst();
            if (top != null && top.menuName != null) {
                label = plugin.lang().get("menu.back-label."
                        + top.menuName.toLowerCase(java.util.Locale.ROOT));
                if (label == null || label.isEmpty()) {
                    label = top.menuName;
                }
            }
        }
        if (label.isEmpty()) {
            label = plugin.lang().get("menu.back-label.none");
        }
        ctx.put("back-target", label);
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
        pendingAdd.clear();
        pendingSearch.clear();
        pendingDur.clear();
        pendingPeriod.clear();
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
        // Защита от «дюпа» кнопок при лагах: при включённом guard клик
        // отменяется, если TPS ниже min-tps или пинг игрока выше max-ping.
        Config.GuardOptions guard = plugin.config().guard();
        if (guard.enabled && !guardOk(p, guard)) {
            plugin.lang().send(p, "guard.blocked");
            return;
        }
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
        // Клик по кнопке, НЕ связанной с вводом, отменяет незавершённый
        // ввод (поиск/ник/срок), чтобы случайный чат не глотался промптом.
        boolean inputCmd = false;
        for (String c : cmds) {
            String cl = c.toLowerCase(java.util.Locale.ROOT);
            if (cl.startsWith("@add:") || cl.startsWith("@market-search")
                    || cl.startsWith("@region-search") || cl.startsWith("@flag-search")
                    || cl.startsWith("@market:dur:")) {
                inputCmd = true;
                break;
            }
        }
        if (!inputCmd) {
            UUID cid = p.getUniqueId();
            pendingAdd.remove(cid);
            pendingSearch.remove(cid);
            pendingDur.remove(cid);
            pendingPeriod.remove(cid);
        }

        // Кнопка флага: ПКМ = сменить группу флага (без переключения значения),
        // ЛКМ = сменить значение для текущей группы.
        if (item.isDynamic() && item.flag() != null && !item.flag().isEmpty()
                && (e.isRightClick() || e.getClick() == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT)) {
            cycleFlagGroup(p, om, item);
            return;
        }
        // Меню рынка: динамические кнопки предложений — действие по типу клика:
        // ЛКМ купить/арендовать (или телепорт для своего), ПКМ — отмена
        // (через подтверждение во вкладке «Мои объявления»), Шифт+ЛКМ — срок
        // объявления, Шифт+ПКМ — срок аренды (только для своих листингов).
        if (om.kind == Kind.MARKET) {
            String mbody = marketOfferCmd(item);
            if (mbody != null) {
                marketItemClick(p, om, item, mbody, e);
                return;
            }
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
            if (c.equalsIgnoreCase("@market-tab")) {
                toggleMarketTab(p, om);
                continue;
            }
            if (c.equalsIgnoreCase("@market-mine")) {
                openMarketMine(p);
                continue;
            }
            if (c.startsWith("@mkt-cancel:")) {
                confirmCancel(p, c.substring("@mkt-cancel:".length()).trim());
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
            if (c.equalsIgnoreCase("@region-search")) {
                startSearchPrompt(p, om, "region");
                continue;
            }
            if (c.startsWith("@rinfo:")) {
                openFoundRegion(p, c.substring("@rinfo:".length()).trim());
                continue;
            }
            if (c.equalsIgnoreCase("@region-info")) {
                openInfoCurrent(p, om);
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
        // меню, ждём 30 сек и убираем промпт, чтобы не глотать следующий чат.
        if (pendingAdd.containsKey(id)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> pendingAdd.remove(id), 600L);
        }
        // поисковый промпт живёт 30 сек после закрытия меню, потом перестаёт
        // глотать чат (если игрок не ввёл запрос — просто отменяется),
        // но при вводе до истечения срока меню откроется с результатом.
        if (pendingSearch.containsKey(id)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> pendingSearch.remove(id), 600L);
        }
        if (pendingDur.containsKey(id)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> pendingDur.remove(id), 600L);
        }
        if (pendingPeriod.containsKey(id)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> pendingPeriod.remove(id), 600L);
        }
    }

    /** Телепорт в регион на безопасную точку (только админ и владелец). */
    private void teleportToRegion(Player p, Map<String, String> ctx, String role) {
        if (!p.hasPermission("qqregions.admin") && !"owner".equalsIgnoreCase(role)) {
            plugin.lang().send(p, "menu.teleport-owner-only");
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
            p.teleport(safeLanding(world, region));
        } catch (Throwable t) {
            plugin.lang().send(p, "menu.teleport-fail");
        }
    }

    /** Безопасная точка приземления игрока в регионе: на твёрдом блоке,
     *  НЕ в воздухе над пустотой, НЕ внутри блока и НЕ в лаве/воде.
     *  Порядок поиска:
     *   1. сухие колонны внутри региона, ближе к центру;
     *   2. если вся территория — лава/вода/пустота, ближайшая безопасная
     *      колонна МИРА вокруг центра (игрок встанет РЯДОМ, на берегу);
     *   3. иначе — над верхней точкой региона (не в блоке). */
    private org.bukkit.Location safeLanding(org.bukkit.World w, ProtectedRegion r) {
        com.sk89q.worldedit.math.BlockVector3 min = r.getMinimumPoint();
        com.sk89q.worldedit.math.BlockVector3 max = r.getMaximumPoint();
        int cx = (min.getX() + max.getX()) / 2;
        int cz = (min.getZ() + max.getZ()) / 2;
        int bottom = Math.max(min.getY(), w.getMinHeight());
        int top = Math.min(max.getY(), w.getMaxHeight() - 3);
        int reach = Math.min(12, Math.max(Math.abs(max.getX() - min.getX()), Math.abs(max.getZ() - min.getZ())));
        org.bukkit.Location best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int x = cx - reach; x <= cx + reach; x++) {
            if (x < min.getX() || x > max.getX()) {
                continue;
            }
            for (int z = cz - reach; z <= cz + reach; z++) {
                if (z < min.getZ() || z > max.getZ()) {
                    continue;
                }
                int d = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                if (d > bestDist) {
                    continue;
                }
                int y = safeColumnY(w, x, z, bottom, top);
                if (y < 0) {
                    continue;
                }
                bestDist = d;
                best = new org.bukkit.Location(w, x + 0.5, y + 1, z + 0.5);
                if (d == 0) {
                    return best;
                }
            }
        }
        if (best != null) {
            return best;
        }
        // лава/вода/пустота на всей территории: ищем берег/остров вокруг.
        for (int rad = 1; rad <= 64; rad++) {
            for (int x = cx - rad; x <= cx + rad; x++) {
                for (int z = cz - rad; z <= cz + rad; z++) {
                    if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) != rad) {
                        continue;
                    }
                    int y = safeWorldColumnY(w, x, z);
                    if (y >= 0) {
                        return new org.bukkit.Location(w, x + 0.5, y + 1, z + 0.5);
                    }
                }
            }
        }
        return new org.bukkit.Location(w, cx + 0.5, Math.min(w.getMaxHeight() - 1, top + 2), cz + 0.5);
    }

    /** Верх безопасного ПОДЛОЖНОГО блока колонны (y пола) в диапазоне
     *  [bottom..top] или -1: пол твёрдый, над ним два блока воздуха
     *  (нет лавы/воды и нет затопления головы). */
    private int safeColumnY(org.bukkit.World w, int x, int z, int bottom, int top) {
        for (int y = top; y >= bottom; y--) {
            org.bukkit.Material floor = w.getBlockAt(x, y, z).getType();
            if (!floor.isSolid() || floor.isLiquid()) {
                continue;
            }
            org.bukkit.Material above1 = w.getBlockAt(x, y + 1, z).getType();
            if (above1.isSolid() || above1.isLiquid()) {
                continue;
            }
            org.bukkit.Material above2 = w.getBlockAt(x, y + 2, z).getType();
            if (above2.isSolid() || above2.isLiquid()) {
                continue;
            }
            return y;
        }
        return -1;
    }

    /** Верх безопасной поверхности в колонне МИРА (для поиска «берега» рядом
     *  с лавой/водой/пустотой) или -1. */
    private int safeWorldColumnY(org.bukkit.World w, int x, int z) {
        org.bukkit.block.Block hb = w.getHighestBlockAt(x, z);
        if (hb.getY() < w.getMinHeight()) {
            return -1;
        }
        org.bukkit.Material t = hb.getType();
        if (!t.isSolid() || t.isLiquid()) {
            return -1;
        }
        org.bukkit.Material above = w.getBlockAt(x, hb.getY() + 1, z).getType();
        if (above.isSolid() || above.isLiquid()) {
            return -1;
        }
        return hb.getY();
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
        plugin.lang().send(p, "menu.flag-set",
                "flag", flagName,
                "flag-name", plugin.replace().flagName(flagName),
                "value", value);
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
        plugin.lang().send(p, ok ? "menu.group-set" : "menu.group-set-fail",
                "flag", flag.getName(), "group", label);
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
     *  ОДНА кнопка на предложение: название = регион, лор — выставивший
     *  (кто получит монеты), тип объявления, цена, объём, координаты, время
     *  объявления, срок аренды (только для аренды), автовозврат (только для
     *  выставившего). Во вкладке «Мои объявления» (ctx["_mine"]) — только свои
     *  листинги. Поиск (ctx["_marketsearch"]) — по названию, сортировка
     *  (ctx["_sort"]) — name | price | default. */
    private List<MenuItem> marketItems(Menu menu, Player viewer, Map<String, String> ctx) {
        List<MenuItem> out = new ArrayList<>();
        MenuItem tpl = new MenuItem("STONE", 1, null, "", null, null, "");
        boolean mineView = truthy(ctx.get("_mine"));
        boolean tpOk = plugin.config().market().teleportEnabled;
        String queryCtx = ctx.get("_marketsearch");
        Pattern qpat = queryCtx == null ? null : Menu.searchPattern(queryCtx);
        String sort = ctx.getOrDefault("_sort", "default").toLowerCase(java.util.Locale.ROOT);
        dev.qqregions.util.TimeFmt tf = new dev.qqregions.util.TimeFmt(plugin);
        List<dev.qqregions.market.Offer> cols = new ArrayList<>();
        for (dev.qqregions.market.Offer o : plugin.market().offers()) {
            if (o.status != dev.qqregions.market.Offer.Status.PENDING
                    && o.status != dev.qqregions.market.Offer.Status.ACTIVE) {
                continue;
            }
            if (mineView && !plugin.market().ownsOffer(o, viewer.getUniqueId())) {
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
            boolean mine = plugin.market().ownsOffer(o, viewer.getUniqueId());
            String lister = plugin.market().nameOf(o.owner != null ? o.owner : o.seller);
            Map<String, String> pc = new HashMap<>(ctx);
            pc.put("market-type", sale
                    ? plugin.lang().get("menu.market-type-sale")
                    : plugin.lang().get("menu.market-type-rent"));
            pc.put("market-region", o.region);
            pc.put("market-world", o.world);
            pc.put("market-price", plugin.market().economy().format(o.price));
            pc.put("market-lister", lister);
            pc.put("market-period", sale ? ""
                    : tf.format(o.periodMillis));
            // Обратный отсчёт: сколько осталось до снятия объявления с рынка.
            // Публичное объявление снимается в listUntil (срок объявления,
            // market.rent.list-duration-minutes), приватное — в pendingUntil
            // (market.offer-timeout-minutes). У идущей аренды показываем
            // остаток СРОКА аренды (until). Раньше было «время объявления»
            // ВВЕРХ от создания — оно росло и сбивало с толку, теперь
            // остаток убывает к нулю.
            long deadline;
            if (o.isActiveRental()) {
                deadline = o.until;
            } else if (o.status == dev.qqregions.market.Offer.Status.PENDING && o.pendingUntil > 0) {
                deadline = o.pendingUntil;
            } else {
                deadline = o.listUntil > 0 ? o.listUntil : 0;
            }
            String age = deadline > 0
                    ? tf.format(Math.max(0, deadline - System.currentTimeMillis()))
                    : plugin.lang().get("menu.time-empty");
            pc.put("market-age", age);
            pc.put("market-autorent", o.autoRent
                    ? plugin.lang().get("menu.lore-market-autorent-on")
                    : plugin.lang().get("menu.lore-market-autorent-off"));

            org.bukkit.World w = Bukkit.getWorld(o.world);
            ProtectedRegion r = w == null ? null : plugin.wg().byName(w, o.region);
            pc.put("market-volume", r == null ? plugin.lang().get("menu.time-empty")
                    : String.valueOf(r.volume()));
            pc.put("market-loc", centerOf(r));

            String name = tpl.process(plugin, viewer, pc,
                    plugin.lang().get("menu.lore-market-type-region"));
            List<String> lore = new ArrayList<>();
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.lore-market-lister")));
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.lore-market-price")));
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.lore-market-volume")));
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.lore-market-location")));
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get(
                    o.isActiveRental() ? "menu.lore-market-rent-left" : "menu.lore-market-age")));
            if (!sale && o.periodMillis > 0) {
                lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.lore-market-period")));
            }
            if (mine && !sale) {
                lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.lore-market-autorent")));
            }
            if (mineView) {
                if (!sale) {
                    lore.add(tpl.process(plugin, viewer, pc,
                            plugin.lang().get("menu.lore-market-mine-hint-autorent")));
                } else if (tpOk) {
                    lore.add(plugin.lang().get("menu.lore-market-mine"));
                }
                lore.add(tpl.process(plugin, viewer, pc,
                        plugin.lang().get("menu.lore-market-mine-hint-cancel")));
                if (!sale) {
                    lore.add(tpl.process(plugin, viewer, pc,
                            plugin.lang().get("menu.lore-market-mine-hint-listdur")));
                    lore.add(tpl.process(plugin, viewer, pc,
                            plugin.lang().get("menu.lore-market-mine-hint-period")));
                }
            } else if (mine) {
                lore.add(tpOk ? plugin.lang().get("menu.lore-market-mine")
                        : plugin.lang().get("menu.lore-market-mine-no-tp"));
            } else {
                lore.add(plugin.lang().get("menu.lore-market-click"));
            }
            String cmd;
            if (mineView) {
                cmd = "@market:own:" + o.id;
            } else if (mine) {
                cmd = "@market:tp:" + o.id;
            } else {
                cmd = "@market:" + (sale ? "buy" : "rent") + ":" + o.id;
            }
            out.add(new MenuItem(sale ? "GOLD_INGOT" : "EMERALD", 1, null, name, lore,
                    List.of(cmd), ""));
        }
        return out;
    }

    /** Псевдокоманда "@market:<...>:<id>" кнопки предложения (или null).
     *  Статические кнопки (@market-search/@market-tab/@sort) без id не считаются. */
    private String marketOfferCmd(MenuItem item) {
        if (item == null || item.commands() == null) {
            return null;
        }
        for (String c : item.commands()) {
            String cl = c.trim();
            if (!cl.startsWith("@market:")) {
                continue;
            }
            String body = cl.substring("@market:".length()).trim();
            if (!body.contains(":")) {
                continue;
            }
            return body;
        }
        return null;
    }

    /** Действие по клику на кнопку предложения рынка:
     *  ЛКМ — купить/арендовать (телепорт для своего во «Все»);
     *  ПКМ — отмена с подтверждением (вкладка «Мои объявления»);
     *  Шифт+ЛКМ — срок объявления; Шифт+ПКМ — срок аренды. */
    private void marketItemClick(Player p, OpenMenu om, MenuItem item, String mbody, InventoryClickEvent e) {
        int idx = mbody.lastIndexOf(':');
        if (idx < 0) {
            return;
        }
        String id = mbody.substring(idx + 1);
        dev.qqregions.market.Offer o = plugin.market().byId(id);
        if (o == null) {
            plugin.lang().send(p, "menu.offer-not-found");
            return;
        }
        boolean mine = plugin.market().ownsOffer(o, p.getUniqueId());
        boolean mineView = truthy(om.ctx.get("_mine"));
        org.bukkit.event.inventory.ClickType ct = e.getClick();
        if (ct == org.bukkit.event.inventory.ClickType.SHIFT_LEFT) {
            if (!mineView || !mine) {
                plugin.lang().send(p, "menu.own-only");
            } else if (o.kind == dev.qqregions.market.Offer.Kind.SALE) {
                plugin.lang().send(p, "market.not-rent");
            } else {
                startDurPrompt(p, o);
            }
        } else if (ct == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT) {
            if (!mineView || !mine) {
                plugin.lang().send(p, "menu.own-only");
            } else if (o.kind == dev.qqregions.market.Offer.Kind.SALE) {
                plugin.lang().send(p, "market.not-rent");
            } else {
                startPeriodPrompt(p, o);
            }
        } else if (e.isRightClick()) {
            if (mineView && mine) {
                openConfirmCancel(p, o);
            }
        } else if (mineView && mine) {
            if (o.kind == dev.qqregions.market.Offer.Kind.SALE) {
                teleportToOffer(p, o);
            } else {
                marketAction(p, "autorent:" + id);
            }
        } else if (mine) {
            teleportToOffer(p, o);
        } else {
            marketAction(p, (o.kind == dev.qqregions.market.Offer.Kind.SALE ? "buy:" : "rent:") + id);
        }
    }

    /** Открыть рынок сразу во вкладке «Мои объявления» (кнопка в главном меню). */
    private void openMarketMine(Player p) {
        Map<String, String> ctx = new HashMap<>();
        ctx.put("_mine", "yes");
        open(p, "market", ctx, 0, null, true);
    }

    /** Переключить вкладку рынка «Мои объявления / Все объявления». */
    private void toggleMarketTab(Player p, OpenMenu om) {
        boolean on = truthy(om.ctx.get("_mine"));
        om.ctx.put("_mine", on ? "" : "yes");
        p.sendMessage(plugin.lang().comp(on
                ? "menu.market-tab-switch-all"
                : "menu.market-tab-switch-mine"));
        render(p, om.menu, om.ctx, 0, om.role, om.kind);
    }

    /** Отмена собственного объявления из меню подтверждения. */
    private void confirmCancel(Player p, String id) {
        dev.qqregions.market.Offer o = plugin.market().byId(id);
        if (o == null) {
            plugin.lang().send(p, "menu.offer-not-found");
            return;
        }
        String res = plugin.market().cancel(o, p);
        if ("ok".equals(res)) {
            plugin.lang().send(p, "menu.offer-cancelled", "region", o.region);
            goBack(p);
        } else {
            plugin.lang().send(p, "menu.offer-action-fail",
                    "action", plugin.lang().get("menu.actions.cancel"), "reason", marketReason(res));
        }
    }

    /** Меню подтверждения отмены объявления. */
    private void openConfirmCancel(Player p, dev.qqregions.market.Offer o) {
        Map<String, String> ctx = new HashMap<>();
        ctx.put("world", o.world);
        ctx.put("region", o.region);
        ctx.put("player", p.getName());
        ctx.put("role", "other");
        ctx.put("market-id", o.id.toString());
        ctx.put("market-region", o.region);
        ctx.put("market-world", o.world);
        ctx.put("market-type", o.kind == dev.qqregions.market.Offer.Kind.SALE
                ? plugin.lang().get("menu.market-type-sale")
                : plugin.lang().get("menu.market-type-rent"));
        open(p, "marketconfirm", ctx, 0, null, true);
    }

    /** Телепорт к региону объявления («посмотреть в живую»).
     *  Отключён config.yml market.teleport-enabled: false. */
    private void teleportToOffer(Player p, dev.qqregions.market.Offer o) {
        if (!plugin.config().market().teleportEnabled) {
            plugin.lang().send(p, "menu.market-teleport-disabled");
            return;
        }
        org.bukkit.World w = Bukkit.getWorld(o.world);
        ProtectedRegion r = w == null ? null : plugin.wg().byName(w, o.region);
        if (w == null || r == null) {
            plugin.lang().send(p, "menu.region-not-found");
            return;
        }
        try {
            p.teleport(safeLanding(w, r));
            plugin.lang().send(p, "menu.market-teleported", "region", o.region);
        } catch (Throwable t) {
            plugin.lang().send(p, "menu.teleport-fail");
        }
    }

    /** Центр региона «X Y Z» (или "—", если регион недоступен). */
    private static String centerOf(ProtectedRegion r) {
        if (r == null) {
            return "—";
        }
        try {
            com.sk89q.worldedit.math.BlockVector3 min = r.getMinimumPoint();
            com.sk89q.worldedit.math.BlockVector3 max = r.getMaximumPoint();
            int x = (min.getX() + max.getX()) / 2;
            int y = (min.getY() + max.getY()) / 2;
            int z = (min.getZ() + max.getZ()) / 2;
            return x + " " + y + " " + z;
        } catch (Throwable t) {
            return "—";
        }
    }

    /** Обработчик @market:<action>:<id> из кнопок меню рынка. */
    private void marketAction(Player p, String spec) {
        String[] parts = spec.split(":", 2);
        if (parts.length < 2) {
            return;
        }
        String action = parts[0].trim().toLowerCase(java.util.Locale.ROOT);
        dev.qqregions.market.Offer o = plugin.market().byId(parts[1].trim());
        if (o == null) {
            plugin.lang().send(p, "menu.offer-not-found");
            return;
        }
        boolean mine = plugin.market().ownsOffer(o, p.getUniqueId());
        String res;
        switch (action) {
            case "buy": {
                org.bukkit.World w = org.bukkit.Bukkit.getWorld(o.world);
                ProtectedRegion r = w == null ? null : plugin.wg().byName(w, o.region);
                if (o.isPublicListing() && w != null && r != null) {
                    res = plugin.market().buy(p, w, r);
                } else {
                    // приватное предложение: принимаем напрямую (адресат — кликер)
                    res = plugin.market().accept(o, p);
                }
                if ("ok".equals(res)) {
                    plugin.lang().send(p, "menu.offer-accepted", "region", o.region);
                } else {
                    plugin.lang().send(p, "menu.offer-action-fail",
                            "action", plugin.lang().get("menu.actions.buy"), "reason", marketReason(res));
                }
                break;
            }
            case "rent": {
                org.bukkit.World w = org.bukkit.Bukkit.getWorld(o.world);
                ProtectedRegion r = w == null ? null : plugin.wg().byName(w, o.region);
                if (o.isPublicListing() && w != null && r != null) {
                    res = plugin.market().tenant(p, w, r);
                } else {
                    res = plugin.market().accept(o, p);
                }
                if ("ok".equals(res)) {
                    plugin.lang().send(p, "menu.offer-accepted", "region", o.region);
                } else {
                    plugin.lang().send(p, "menu.offer-action-fail",
                            "action", plugin.lang().get("menu.actions.rent"), "reason", marketReason(res));
                }
                break;
            }
            case "autorent": {
                res = plugin.market().setAutoRent(o, p, !o.autoRent);
                if ("ok".equals(res)) {
                    plugin.lang().send(p, o.autoRent ? "menu.autorent-on" : "menu.autorent-off",
                            "region", o.region);
                } else {
                    plugin.lang().send(p, "menu.offer-action-fail",
                            "action", plugin.lang().get("menu.actions.autorent"), "reason", marketReason(res));
                }
                break;
            }
            case "dur":
                startDurPrompt(p, o);
                break;
            case "tp":
                teleportToOffer(p, o);
                break;
            case "period":
                startPeriodPrompt(p, o);
                break;
            case "accept":
                res = plugin.market().accept(o, p);
                if ("ok".equals(res)) {
                    plugin.lang().send(p, "menu.offer-accepted", "region", o.region);
                } else {
                    plugin.lang().send(p, "menu.offer-action-fail",
                            "action", plugin.lang().get("menu.actions.accept"), "reason", marketReason(res));
                }
                break;
            case "decline":
                res = plugin.market().decline(o, p);
                if ("ok".equals(res)) {
                    plugin.lang().send(p, "menu.offer-declined", "region", o.region);
                } else {
                    plugin.lang().send(p, "menu.offer-action-fail",
                            "action", plugin.lang().get("menu.actions.decline"), "reason", marketReason(res));
                }
                break;
            case "cancel":
                res = plugin.market().cancel(o, p);
                if ("ok".equals(res)) {
                    plugin.lang().send(p, "menu.offer-cancelled", "region", o.region);
                } else {
                    plugin.lang().send(p, "menu.offer-action-fail",
                            "action", plugin.lang().get("menu.actions.cancel"), "reason", marketReason(res));
                }
                break;
            default:
                return;
        }
        if (open.containsKey(p.getUniqueId())) {
            OpenMenu live = open.get(p.getUniqueId());
            render(p, live.menu, live.ctx, live.page, live.role, live.kind);
        }
    }

    /** Ввод срока объявления аренды (минуты) через чат. */
    private void startDurPrompt(Player p, dev.qqregions.market.Offer o) {
        if (!plugin.market().ownsOffer(o, p.getUniqueId())) {
            plugin.lang().send(p, "menu.offer-action-fail",
                    "action", plugin.lang().get("menu.actions.dur"), "reason", marketReason("not-you"));
            return;
        }
        pendingDur.put(p.getUniqueId(), o);
        p.sendMessage(plugin.lang().comp("menu.dialog-dur-chat"));
    }

    private void onDurResult(UUID id, dev.qqregions.market.Offer o, String raw) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player p = plugin.getServer().getPlayer(id);
            if (p == null || !p.isOnline()) {
                return;
            }
            String v = raw == null ? "" : raw.trim().replaceAll("[^0-9]", "");
            long minutes = -1;
            try {
                minutes = Long.parseLong(v);
            } catch (NumberFormatException ignored) {
                // нет — ниже сообщение
            }
            if (minutes <= 0) {
                plugin.lang().send(p, "market.bad-duration");
                return;
            }
            String res = plugin.market().setListDuration(o, p, minutes);
            if ("ok".equals(res)) {
                plugin.lang().send(p, "market.listdur-set", "region", o.region, "minutes", fmt(minutes));
            } else {
                plugin.lang().send(p, "menu.offer-action-fail",
                        "action", plugin.lang().get("menu.actions.dur"), "reason", marketReason(res));
            }
        });
    }

    /** Ввод срока АРЕНДЫ (период арендатора, 1m..1y) через чат. */
    private void startPeriodPrompt(Player p, dev.qqregions.market.Offer o) {
        if (!plugin.market().ownsOffer(o, p.getUniqueId())) {
            plugin.lang().send(p, "menu.own-only");
            return;
        }
        if (o.kind != dev.qqregions.market.Offer.Kind.RENT) {
            plugin.lang().send(p, "market.not-rent");
            return;
        }
        pendingPeriod.put(p.getUniqueId(), o);
        p.sendMessage(plugin.lang().comp("menu.market-period-chat"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPeriodChat(AsyncPlayerChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        dev.qqregions.market.Offer o = pendingPeriod.get(id);
        if (o == null) {
            return;
        }
        e.setCancelled(true);
        pendingPeriod.remove(id);
        onPeriodResult(id, o, e.getMessage());
    }

    private void onPeriodResult(UUID id, dev.qqregions.market.Offer o, String raw) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player p = plugin.getServer().getPlayer(id);
            if (p == null || !p.isOnline()) {
                return;
            }
            long minutes = parseRentMinutes(raw);
            if (minutes <= 0) {
                plugin.lang().send(p, "market.bad-duration");
                return;
            }
            String res = plugin.market().setRentPeriod(o, p, minutes);
            if ("ok".equals(res)) {
                plugin.lang().send(p, "market.period-set", "region", o.region,
                        "time", new dev.qqregions.util.TimeFmt(plugin).format(o.periodMillis));
            } else {
                plugin.lang().send(p, "menu.offer-action-fail",
                        "action", plugin.lang().get("menu.actions.period"), "reason", marketReason(res));
            }
        });
    }

    /** Разбор срока: число = минуты, либо комбинации вида 1y/1mo/1w/1d/1h/1m
     *  (например "6mo 1d 15h 10m"). @return минуты или -1. */
    private static long parseRentMinutes(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.isEmpty()) {
            return -1;
        }
        if (v.matches("\\d+")) {
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        long total = 0;
        int last = 0;
        boolean any = false;
        java.util.regex.Matcher m = Pattern.compile("(\\d+)\\s*(y|mo|w|d|h|m)").matcher(v);
        while (m.find()) {
            any = true;
            long n;
            try {
                n = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
            long mult;
            switch (m.group(2)) {
                case "y" -> mult = 365L * 1440L;
                case "mo" -> mult = 30L * 1440L;
                case "w" -> mult = 7L * 1440L;
                case "d" -> mult = 1440L;
                case "h" -> mult = 60L;
                default -> mult = 1L;
            }
            total += n * mult;
            last = m.end();
        }
        if (!any || last != v.length()) {
            return -1;
        }
        return total;
    }

    /** Закрыть живое состояние меню (инвентарь уже закрылся из-за чата). */
    private void closeOpen(Player p) {
        OpenMenu cur = open.remove(p.getUniqueId());
        if (cur != null) {
            history.remove(p.getUniqueId());
        }
        if (p.getOpenInventory() != null) {
            p.closeInventory();
        }
    }

    /** Код причины из MarketManager -> готовый текст из lang.yml (market.*). */
    private String marketReason(String code) {
        String s = plugin.lang().get("market." + code).trim();
        return s.isEmpty() ? code : s;
    }

    /** Флаг в контексте меню: "yes"/"true"/"1" считается включённым. */
    private static boolean truthy(String s) {
        return s != null
                && (s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("true") || "1".equals(s));
    }

    /** Дружелюбное отображение количества минут. */
    private static String fmt(long minutes) {
        if (minutes >= 1440) {
            long days = minutes / 1440;
            long h = (minutes % 1440) / 60;
            return h > 0 ? days + "д " + h + "ч" : days + "д";
        }
        if (minutes >= 60) {
            long h = minutes / 60;
            long m = minutes % 60;
            return m > 0 ? h + "ч " + m + "м" : h + "ч";
        }
        return minutes + "м";
    }

    /** Обработчик @raid:<action> из кнопок меню (запуск рейда). */
    private void raidAction(Player p, String spec, Map<String, String> ctx) {
        String action = spec.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"start".equals(action)) {
            return;
        }
        if (!p.hasPermission("qqregions.admin") && !p.hasPermission("qqregions.raid")) {
            plugin.lang().send(p, "general.no-permission");
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
            // res — уже готовый перевод (RaidManager fmt из lang), только показать.
            p.sendMessage(dev.qqregions.util.Msg.color(res));
        } else {
            plugin.lang().send(p, "raid.ok",
                    "region", r != null ? r.getId() : (ctx.get("region") == null ? "" : ctx.get("region")));
        }
    }

    /** Кнопка @highlight: показать подсветку границ текущего региона. */
    private void highlightRegion(Player p, Map<String, String> ctx) {
        org.bukkit.World w = worldFrom(ctx);
        ProtectedRegion r = w == null ? null : plugin.wg().byName(w, ctx.get("region"));
        if (w == null || r == null) {
            plugin.lang().send(p, "menu.region-not-found");
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
            plugin.lang().send(p, "shop.none-owned");
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
        Menu.DynamicFlags dyn = menu.dynamicFlags();
        for (com.sk89q.worldguard.protection.flags.Flag<?> flag : plugin.wg().allFlags()) {
            String id = flag.getName();
            String key = id == null ? "" : id.toLowerCase(java.util.Locale.ROOT);
            if (key.isEmpty() || whitelist.contains(key)
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
                String translated = plugin.replace().flagName(id);
                String plain = dev.qqregions.util.Msg.toLegacy(dev.qqregions.util.Msg.color(translated));
                if (!pat.matcher(id).find() && !pat.matcher(translated).find()
                        && !pat.matcher(plain).find()) {
                    continue;
                }
            }
            Map<String, String> pc = new HashMap<>(ctx);
            pc.put("flag-name", plugin.replace().flagName(id));
            pc.put("price", plugin.market().economy().format(price));
            String name = tpl.process(plugin, player, pc, "&f{flag-name}");
            List<String> lore = new ArrayList<>();
            lore.add(tpl.process(plugin, player, pc, plugin.lang().get("menu.lore-price")));
            lore.add(plugin.lang().get("menu.lore-flag-shop-hint"));
            lore.add(plugin.lang().get("menu.lore-buy"));
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
            lore.add(tpl.process(plugin, player, pc, plugin.lang().get("menu.lore-pack-area")));
            lore.add(tpl.process(plugin, player, pc, plugin.lang().get("menu.lore-price")));
            lore.add(plugin.lang().get("menu.lore-buy"));
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
            lore.add(tpl.process(plugin, player, pc, plugin.lang().get("menu.lore-pack-region")));
            lore.add(tpl.process(plugin, player, pc, plugin.lang().get("menu.lore-pack-repeatable")));
            lore.add(plugin.lang().get("menu.lore-buy"));
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
                    plugin.lang().send(p, "shop.flag-bought",
                            "flag-name", plugin.replace().flagName(id),
                            "price", plugin.market().economy().format(plugin.shop().priceOf(id)));
                } else {
                    plugin.lang().send(p, "shop.pack-bought",
                            "pack-name", packName(kind, id),
                            "price", plugin.market().economy().format(packPrice(kind, id)));
                }
            }
            case "already" -> plugin.lang().send(p, "shop.already",
                    "flag-name", plugin.replace().flagName(id));
            case "no-money" -> plugin.lang().send(p, "shop.no-money");
            case "not-found" -> plugin.lang().send(p, "shop.not-found");
            case "no-economy" -> plugin.lang().send(p, "shop.no-economy");
            default -> plugin.lang().send(p, "shop.error");
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
            plugin.lang().send(p, "menu.participants-owner-only");
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
        plugin.lang().send(p, "menu.player-removed");
        render(p, om.menu, om.ctx, om.page, om.role, om.kind);
    }

    /** Начать ввод запроса в чат: фильтр по флагам/рынку или поиск региона по имени
     *  (kind: flag|market|region). Меню при этом НЕ закрывается — игрок пишет в чат
     *  поверх инвентаря, результат применяется к живому контексту. */
    private void startSearchPrompt(Player p, OpenMenu om, String kind) {
        pendingSearch.put(p.getUniqueId(), new SearchPrompt(om, kind));
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
        SearchPrompt pr = pendingSearch.get(id);
        if (pr == null) {
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
        plugin.getServer().getScheduler().runTask(plugin, () -> applySearch(pid, pr, q));
    }

    private void applySearch(UUID id, SearchPrompt pr, String query) {
        Player p = plugin.getServer().getPlayer(id);
        if (p == null || !p.isOnline()) {
            return;
        }
        if ("region".equalsIgnoreCase(pr.kind)) {
            applyRegionSearch(p, pr, query);
            return;
        }
        boolean market = "market".equalsIgnoreCase(pr.kind);
        String key = market ? "_marketsearch" : "_flagsearch";
        p.sendMessage(plugin.lang().comp("market.search-set", "query", query));
        OpenMenu live = open.get(id);
        if (live != null) {
            // меню ещё открыто — кладём фильтр в ЖИВОЙ контекст и перерисовываем
            live.ctx.put(key, query);
            render(p, live.menu, live.ctx, live.page, live.role, live.kind);
        } else {
            // инвентарь закрыли во время ввода — переоткрываем с фильтром
            pr.om.ctx.put(key, query);
            open(p, market ? "market" : "flags", pr.om.ctx, 0, pr.om.role, false);
        }
    }

    /** Поиск региона по имени (из главного меню «Поиск региона»).
     *  Точно одно совпадение → сразу info-меню региона (как /region info).
     *  Несколько → открывается меню-результат menus/regionsearch.yml со списком
     *  совпавших регионов (страницы, клик = info). 0 → сообщение и возврат. */
    private void applyRegionSearch(Player p, SearchPrompt pr, String query) {
        List<String> matches = findRegions(query);
        if (matches.isEmpty()) {
            plugin.lang().send(p, "menu.region-search-notfound", "query", query);
            reopenPrev(p, pr, query);
            return;
        }
        if (matches.size() == 1) {
            org.bukkit.World fw = null;
            ProtectedRegion fr = null;
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                ProtectedRegion r = plugin.wg().byName(w, matches.get(0));
                if (r != null) {
                    fw = w;
                    fr = r;
                    break;
                }
            }
            if (fr == null) {
                plugin.lang().send(p, "menu.region-search-notfound", "query", query);
                reopenPrev(p, pr, query);
                return;
            }
            plugin.lang().send(p, "menu.region-search-found", "region", matches.get(0));
            if (!openInfo(p, fw, fr)) {
                plugin.lang().send(p, "info.menu-disabled");
            }
            return;
        }
        p.sendMessage(plugin.lang().comp("menu.region-search-many", "query", query,
                "regions", String.valueOf(matches.size())));
        Map<String, String> ctx = new HashMap<>();
        ctx.put("world", p.getWorld().getName());
        ctx.put("region", "");
        ctx.put("player", p.getName());
        ctx.put("role", "other");
        ctx.put("_regionsearch", query);
        ctx.put("query", query);
        open(p, "regionsearch", ctx, 0, null, true);
    }

    /** Все регионы (по всем мирам), имя которых содержит запрос; без banned/__global__. */
    private List<String> findRegions(String query) {
        List<String> out = new ArrayList<>();
        String q = query.toLowerCase(java.util.Locale.ROOT);
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (ProtectedRegion r : plugin.wg().all(w)) {
                String name = r.getId().toLowerCase(java.util.Locale.ROOT);
                if (excludeFromSearch(name)) {
                    continue;
                }
                if (name.contains(q) && !out.contains(r.getId())) {
                    out.add(r.getId());
                }
            }
        }
        return out;
    }

    /** Вернуться к меню, из которого запускался поиск (если оно ещё живо). */
    private void reopenPrev(Player p, SearchPrompt pr, String query) {
        OpenMenu live = open.get(p.getUniqueId());
        if (live != null) {
            render(p, live.menu, live.ctx, live.page, live.role, live.kind);
        } else if (pr.om != null) {
            open(p, pr.om.menu.file(), pr.om.ctx, 0, pr.om.role, false);
        }
    }

    /** Кнопки совпавших регионов для меню regionsearch. */
    private List<MenuItem> regionSearchItems(Menu menu, Player viewer, Map<String, String> ctx) {
        List<MenuItem> out = new ArrayList<>();
        String q = ctx.get("_regionsearch");
        if (q == null || q.isEmpty()) {
            return out;
        }
        for (String name : findRegions(q)) {
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                ProtectedRegion r = plugin.wg().byName(w, name);
                if (r == null) {
                    continue;
                }
                Map<String, String> pc = new HashMap<>(ctx);
                pc.put("region", r.getId());
                pc.put("world", w.getName());
                out.add(new MenuItem("COMPASS", 1, null,
                        "&e" + name, List.of(
                                "&7Мир: &f" + w.getName(),
                                "&7Тип: &f" + r.getType().getName(),
                                "&7Клик — информация о регионе"),
                        List.of("@rinfo:" + w.getName() + ":" + r.getId()), ""));
                break;
            }
        }
        return out;
    }

    /** Клик по найденному региону из меню regionsearch: открывает info с ролью игрока. */
    private void openFoundRegion(Player p, String spec) {
        int i = spec.indexOf(':');
        int j = i < 0 ? -1 : spec.indexOf(':', i + 1);
        if (i < 0 || j < 0) {
            return;
        }
        String worldName = spec.substring(i + 1, j);
        String name = spec.substring(j + 1);
        org.bukkit.World w = Bukkit.getWorld(worldName);
        ProtectedRegion r = w == null ? null : plugin.wg().byName(w, name);
        if (w == null || r == null) {
            plugin.lang().send(p, "menu.region-search-notfound", "query", name);
            return;
        }
        if (!openInfo(p, w, r)) {
            plugin.lang().send(p, "info.menu-disabled");
        }
    }

    private boolean excludeFromSearch(String name) {
        return plugin.config().isBannedRegion(name) || name.startsWith("__global__");
    }

    /** Ввод ника игрока в чат для добавления владельца/участника. */
    private void startPrompt(Player p, OpenMenu om, String kind) {
        if (!p.hasPermission("qqregions.admin") && !"owner".equalsIgnoreCase(om.role)) {
            plugin.lang().send(p, "menu.participants-owner-only");
            return;
        }
        if (!"owner".equalsIgnoreCase(kind) && !"member".equalsIgnoreCase(kind)) {
            return;
        }
        String k = kind.toLowerCase(java.util.Locale.ROOT);
        boolean ownerRole = "owner".equalsIgnoreCase(k);
        pendingAdd.put(p.getUniqueId(), new AddPrompt(om, k));
        p.sendMessage(plugin.lang().comp(ownerRole ? "menu.add-chat-prompt-owner" : "menu.add-chat-prompt-member"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDurChat(AsyncPlayerChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        dev.qqregions.market.Offer o = pendingDur.get(id);
        if (o == null) {
            return;
        }
        e.setCancelled(true);
        pendingDur.remove(id);
        onDurResult(id, o, e.getMessage());
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
            plugin.lang().send(p, "menu.add-cancelled");
            return;
        }
        org.bukkit.World world = worldFrom(pr.om.ctx);
        ProtectedRegion region = world == null ? null : plugin.wg().byName(world, pr.om.ctx.get("region"));
        if (world == null || region == null) {
            plugin.lang().send(p, "menu.region-not-found");
            return;
        }
        plugin.wg().addPlayerByName(world, region, name, "owner".equalsIgnoreCase(pr.kind));
        plugin.lang().send(p, "owner".equalsIgnoreCase(pr.kind)
                ? "menu.player-added-owner" : "menu.player-added-member", "player", name);
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

    /** Контекст поискового промпта: копия OpenMenu + вид поиска
     *  ("market"/"flag"). Позволяет применить поиск, даже если меню
     *  закрылось за время ввода, и переоткрыть его с результатом. */
    private static class SearchPrompt {
        final OpenMenu om;
        final String kind;

        SearchPrompt(OpenMenu om, String kind) {
            this.om = om;
            this.kind = kind;
        }
    }

    /** Точка истории для кнопки @back (откуда пришли, чтобы вернуться). */
    private record NavState(String menuName, Map<String, String> ctx, String role) {
    }
}