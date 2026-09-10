package dev.qqregions.gui;

import com.sk89q.worldguard.protection.flags.BooleanFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.qqregions.QQRegions;
import dev.qqregions.config.Config;
import dev.qqregions.raid.JustTeamsHook;
import dev.qqregions.wg.RegionException;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

    /** Режимы сортировки предложений рынка (переключаются @sort):
     *  название A-Z/Z-A, цена по возрастанию/убыванию, по умолчанию. */
    private static final List<String> MARKET_SORT = List.of("name-az", "name-za",
            "price-asc", "price-desc", "default");

    /** Режимы сортировки выбора территории (переключаются @rpsort):
     *  близкие/дальние/по A-Z/Z-A/по людям +-, по площади +-. */
    private static final List<String> REGION_SORT = List.of("near", "far", "az", "za",
            "members-asc", "members-desc", "area-asc", "area-desc");

    /** Режимы сортировки поиска игроков (переключаются @ps-sort): A-Z/Z-A,
     *  по балансу (богатые сверху), по числу регионов +-, по дистанции +-. */
    private static final List<String> PLAYER_SORT = List.of("az", "za", "balance",
            "regions-asc", "regions-desc", "dist-far", "dist-near");

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
                "menus/regionsearch.yml", "menus/marketconfirm.yml",
                "menus/confirmdelete.yml", "menus/playersearch.yml", "menus/regionpicker.yml",
                "menus/playerconfirm.yml"
        };
        for (String r : menuResources) {
            dev.qqregions.util.Yml.upgrade(plugin, r, new File(dir, r.substring(r.indexOf('/') + 1)));
        }
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".yml"));
        int defaultUpdate = plugin.config().menuUpdateTicks();
        if (files != null) {
            for (File f : files) {
                List<Menu> parsed = Menu.parseFile(plugin, f, defaultUpdate);
                parsed.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
                menus.put(f.getName().replaceFirst("\\.yml$", ""), parsed);
            }
        }
        registerOpenCommands();
        // Перезагрузка (/region reload) отменяет незавершённые чат-промпты:
        // иначе «введи ник/поиск/срок» продолжает глотать чат после релоада.
        pendingAdd.clear();
        pendingSearch.clear();
        pendingDur.clear();
        pendingPeriod.clear();
    }

    /** Снять все ожидающие чат-промпты игрока (ввод ника/поиск/срок). Вызывается
     *  при уроне, смерти, выходе/кике, смене мира и перезагрузке — иначе
     *  «забытый» промпт продолжает глотать следующий чат игрока. */
    public void cancelPlayerPrompts(Player p) {
        UUID id = p.getUniqueId();
        pendingAdd.remove(id);
        pendingSearch.remove(id);
        pendingDur.remove(id);
        pendingPeriod.remove(id);
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) {
            cancelPlayerPrompts(p);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        cancelPlayerPrompts(e.getEntity());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        cancelPlayerPrompts(e.getPlayer());
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent e) {
        cancelPlayerPrompts(e.getPlayer());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent e) {
        cancelPlayerPrompts(e.getPlayer());
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
        ctx.put("my-regions", String.valueOf(plugin.wg().ownedCount(world, player)));
        int maxR = plugin.config().maxRegions();
        int extraR = plugin.shop().extraRegions(player.getUniqueId());
        boolean bypass = plugin.selections().isBypassed(player);
        ctx.put("max-regions", maxR <= 0 || bypass ? "∞" : String.valueOf(maxR + extraR));
        ctx.put("max-blocks", bypass
                ? "∞" : String.valueOf(plugin.selections().effectiveMaxBlocks(player)));
        fillRaidCtx(player, region, ctx);
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
        FLAGS, PLAYERS, MARKET, FLAG_SHOP, BLOCK_SHOP, MY_FLAGS, MAIN, INFO, REGION_SEARCH,
        PLAYER_SEARCH, REGION_PICKER
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
            case "info" -> Kind.INFO;
            case "regionsearch" -> Kind.REGION_SEARCH;
            case "playersearch" -> Kind.PLAYER_SEARCH;
            case "regionpicker" -> Kind.REGION_PICKER;
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
            // Главное меню открывается через openMain, а не open("main", ...):
            // openMain заполняет контекст ({my-region-lore} и др.), иначе в
            // кнопке «Моя территория» остаётся сырой {my-region-lore}.
            openMain(p);
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
        fillRaidCtx(null, region, ctx);
    }

    /** Глобальные заполнители, доступные в ЛЮБОМ меню (единая система):
     *  валюта рынка {market-price-symbol}, баланс смотрящего {balance}/
     *  {balance-symbol} и данные клана смотрящего {raid-clan}, {raid-balance},
     *  {raid-balance-symbol}. Ключи, которые меню/кнопки заполнили раньше
     *  (например рейд-кнопка info-меню через fillRaidCtx), НЕ перезаписываются
     *  (putIfAbsent). */
    private void enrichBaseContext(Player player, Map<String, String> ctx) {
        if (player == null) {
            return;
        }
        ctx.putIfAbsent("market-price-symbol", currencySymbol());
        ctx.putIfAbsent("balance", playerBalance(player.getUniqueId()));
        ctx.putIfAbsent("balance-symbol", currencySymbol());
        JustTeamsHook teams = plugin.raid().teams();
        if (teams == null || !teams.enabled()) {
            return;
        }
        JustTeamsHook.TeamRef team = teams.team(player.getUniqueId());
        if (team == null) {
            return;
        }
        ctx.putIfAbsent("raid-clan", team.name());
        double bal = teams.balance(team);
        if (bal >= 0) {
            ctx.putIfAbsent("raid-balance", plugin.raid().raidMoney(bal));
            ctx.putIfAbsent("raid-balance-symbol", plugin.raid().raidSymbol());
        }
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
        // «Моя территория»: если стоим в регионе — подсказка об управлении,
        // иначе — предложение выбрать регион (меню выбора территории).
        ProtectedRegion here = plugin.wg().current(player);
        ctx.put("my-region-lore", here != null
                ? plugin.lang().get("menu.main-myregion-here")
                : plugin.lang().get("menu.main-myregion-pick"));
        Menu best = pick(player, "main", null);
        if (best == null) {
            return false;
        }
        List<MenuItem> dynItems = List.of();
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
        OpenMenu om = new OpenMenu(player, inv, best, ctx, 0, maxPages, null, slotMap, Kind.MAIN);
        om.lastRenderAt = System.currentTimeMillis();
        open.put(player.getUniqueId(), om);
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
        // Анти-автокликер (menu-update.debounce-after-click): перерисовка УЖЕ
        // открытого меню (тот же шаблон + та же страница) после клика молча
        // откладывается, пока не пройдёт update_interval тиков с последней
        // реальной перерисовки. Навигация (другое меню/страница), открытие и
        // меню с выключенным автообновлением перерисовываются сразу.
        OpenMenu cur = open.get(player.getUniqueId());
        if (cur != null && cur.menu == menu && cur.page == page && cur.role == role
                && plugin.config().menuDebounce()) {
            int interval = menu.updateInterval();
            if (interval > 0 && cur.lastRenderAt > 0
                    && System.currentTimeMillis() - cur.lastRenderAt < interval * 50L) {
                return true;                    // отложено: перерисует tick()
            }
        }
        enrichBaseContext(player, ctx);
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
            ctx.put("market-sort", marketSortLabel(ctx.getOrDefault("_sort", "default")));
            ctx.put("market-sort-list", sortPickList("market-sort", ctx.getOrDefault("_sort", "default")));
        }
        // Главное меню: если открыто по сохранённому контексту без
        // {my-region-lore} (например @back с пустой историей и т.п.) — считаем
        // заново, чтобы в кнопке «Моя территория» не осталось сырого заполнителя.
        if (kind == Kind.MAIN && ctx.get("my-region-lore") == null) {
            ProtectedRegion here = plugin.wg().current(player);
            ctx.put("my-region-lore", here != null
                    ? plugin.lang().get("menu.main-myregion-here")
                    : plugin.lang().get("menu.main-myregion-pick"));
        }
        List<MenuItem> dynItems;
        Set<String> owned = plugin.shop().ownedFlags(player.getUniqueId());
        switch (kind) {
            case PLAYERS -> dynItems = playerItems(menu, ctx);
            case MARKET -> dynItems = marketItems(menu, player, ctx);
            case FLAG_SHOP -> dynItems = flagShopItems(menu, player, ctx);
            case BLOCK_SHOP -> dynItems = blockShopItems(menu, player, ctx);
            case MY_FLAGS -> dynItems = menu.purchasedItems(plugin, player, ctx, owned);
            case MAIN -> dynItems = List.of();
            case INFO -> dynItems = List.of();
            case REGION_SEARCH -> dynItems = regionSearchItems(menu, player, ctx);
            case PLAYER_SEARCH -> {
                ctx.putIfAbsent("_pdsort", "az");
                ctx.put("ps-sort-list", sortPickList("ps-sort", ctx.get("_pdsort")));
                dynItems = playerSearchItems(menu, player, ctx);
            }
            case REGION_PICKER -> {
                ctx.putIfAbsent("_rpsort", "near");
                ctx.put("rp-sort", rpSortLabel(ctx.get("_rpsort")));
                ctx.put("rp-sort-list", sortPickList("rp-sort", ctx.get("_rpsort")));
                dynItems = regionPickerItems(menu, player, ctx);
            }
            default -> dynItems = menu.flagItems(plugin, player, ctx, menu.dynamicFlags(), false, owned);
        }
        int maxPages = menu.maxPages(dynItems.size());
        int safePage = Math.max(0, Math.min(maxPages - 1, page));
        applyBackTarget(player, ctx);
        Map<Integer, MenuItem> slotMap = new HashMap<>();
        Inventory inv = menu.build(plugin, player, ctx, safePage, maxPages, dynItems, slotMap);
        // Кнопка «Вернуться в главное меню» в инфо-меню (слот 0). Вставляется
        // кодом, чтобы не перезаписывать кастомизированные файлы info.yml:
        // если в файле уже есть кнопка на слоте 0 — она остаётся как есть.
        if (kind == Kind.INFO && inv.getItem(0) == null) {
            MenuItem back = new MenuItem("ARROW", 1, 0,
                    plugin.lang().get("menu.info-back-name"),
                    List.of(plugin.lang().get("menu.info-back-lore")),
                    List.of("@menu:main"), "");
            inv.setItem(0, back.build(plugin, player, ctx));
            slotMap.put(0, back);
        }
        player.openInventory(inv);
        OpenMenu om = new OpenMenu(player, inv, menu, ctx, safePage, maxPages, role, slotMap, kind);
        om.lastRenderAt = System.currentTimeMillis();
        open.put(player.getUniqueId(), om);
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
        // дофетч скинов для голов (SkullResolver): один запрос за раз, с паузой,
        // работает и без открытых меню — кэш тёплый к моменту открытия
        plugin.skulls().poll();
        if (open.isEmpty()) {
            return;
        }
        for (OpenMenu om : List.copyOf(open.values())) {
            if (!om.player.isOnline()) {
                continue;
            }
            int interval = om.menu.updateInterval();
            if (interval <= 0) {
                continue;                    // автообновление выключено
            }
            om.ticks += 5;
            if (om.ticks >= interval) {
                om.ticks = 0;
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
        // Молча сбрасываем таймер автообновления: после клика по кнопке меню
        // не перерисуется раньше чем через update_interval тиков (анти-автокликер).
        om.ticks = 0;
        if (item.permission() != null && !item.permission().isEmpty()
                && !p.hasPermission("qqregions.admin") && !p.hasPermission(item.permission())) {
            return;
        }
// флаг-кнопка: клик требует право <prefix><флаг> или qqregions.flags.use.<флаг>/
        // qqregions.flags.<флаг> (см. Menu.canSeeFlag) или группу-шаблон flag-groups
        // (см. Menu.canUseFlag). Купленные флаги кликаются без права.
        Menu.DynamicFlags dyn = om.menu.dynamicFlags();
        String flagPermPrefix = dyn == null ? null : dyn.permissionPrefix;
        if (item.isDynamic() && item.flag() != null && !item.flag().isEmpty()
                && !Menu.canUseFlag(plugin, p, flagPermPrefix, item.flag())) {
            String flagKey = item.flag().toLowerCase(java.util.Locale.ROOT);
            if (!plugin.shop().ownedFlags(p.getUniqueId()).contains(flagKey)) {
                return;
            }
        }
        List<String> cmds = item.commands();
        if (cmds == null) {
            return;
        }
        // Поиск игроков: клик по «голове» игрока — своя логика без псевдокоманд
        // (ЛКМ — добавить в выбранную группу, ПКМ — убрать, Шифт+ЛКМ — сменить).
        if (om.kind == Kind.PLAYER_SEARCH) {
            String c0 = cmds.isEmpty() ? null : cmds.get(0);
            if (c0 != null && c0.startsWith("PLRS:")) {
                playerSearchClick(p, om, c0.substring("PLRS:".length()).trim(), e);
                return;
            }
        }
        // Клик по кнопке, НЕ связанной с вводом, отменяет незавершённый
        // ввод (поиск/ник/срок), чтобы случайный чат не глотался промптом.
        boolean inputCmd = false;
        for (String c : cmds) {
            String cl = c.toLowerCase(java.util.Locale.ROOT);
            if (cl.startsWith("@add:") || cl.startsWith("@market-search")
                    || cl.startsWith("@region-search") || cl.startsWith("@flag-search")
                    || cl.startsWith("@player-search")
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
                if ("main".equalsIgnoreCase(target)) {
                    openMain(p);
                } else if ("myflags".equalsIgnoreCase(target)) {
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
            if (c.equalsIgnoreCase("@pc-confirm")) {
                confirmPlayerAction(p, om);
                continue;
            }
            if (c.equalsIgnoreCase("@pc-add")) {
                playerConfirmAction(p, om, e.getClick());
                continue;
            }
            if (c.equalsIgnoreCase("@pc-remove")) {
                playerConfirmRemove(p, om);
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
            if (c.equalsIgnoreCase("@player-search")) {
                startSearchPrompt(p, om, "player");
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
            if (c.equalsIgnoreCase("@region-info-or-pick")) {
                openInfoOrPick(p, om);
                continue;
            }
            if (c.equalsIgnoreCase("@region-delete")) {
                confirmDeleteRegion(p, om);
                continue;
            }
            if (c.equalsIgnoreCase("@select")) {
                // «Создать территорию»: меню закрывается, чтобы игрок сразу
                // оказался в интерактивном режиме выбора (хотбар-кнопки
                // сессии), а не под открытым инвентарём.
                p.closeInventory();
                startInteractiveSelect(p);
                continue;
            }
            if (c.equalsIgnoreCase("@ps-sort")) {
                cyclePsSort(p, om);
                continue;
            }
            if (c.equalsIgnoreCase("@rpsort")) {
                cycleRegionSort(p, om);
                continue;
            }
            MenuAction.run(plugin, p, c);
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

    /** Следующее значение в цикле states (или allow&lt;-&gt;deny для StateFlag).
     *  Флаг «не установлен» (current "") трактуется как последнее состояние
     *  цикла: если в states есть default-токен — как он сам (следующий клик
     *  ставит allow), иначе как последнее состояние (следующий — первое). */
    private String nextState(MenuItem item, String current) {
        List<String> states = item.states();
        if (states == null || states.isEmpty()) {
            return "";
        }
        int idx = -1;
        if (current == null || current.isEmpty()) {
            for (int i = states.size() - 1; i >= 0; i--) {
                if (isUnsetState(states.get(i))) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                idx = states.size() - 1;
            }
        } else {
            for (int i = 0; i < states.size(); i++) {
                if (states.get(i).equalsIgnoreCase(current)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                return states.get(0);
            }
        }
        return states.get((idx + 1) % states.size());
    }

    /** Токены «не установлено» (по умолчанию) в циклах значений флага. */
    private static boolean isUnsetState(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        return t.equalsIgnoreCase("default")
                || t.equals("-")
                || t.equalsIgnoreCase("unset")
                || t.equalsIgnoreCase("none")
                || t.equalsIgnoreCase("clear")
                || t.equalsIgnoreCase("по-умолчанию");
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
            if (!floor.isSolid() || isLiquid(floor)) {
                continue;
            }
            org.bukkit.Material above1 = w.getBlockAt(x, y + 1, z).getType();
            if (above1.isSolid() || isLiquid(above1)) {
                continue;
            }
            org.bukkit.Material above2 = w.getBlockAt(x, y + 2, z).getType();
            if (above2.isSolid() || isLiquid(above2)) {
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
        if (!t.isSolid() || isLiquid(t)) {
            return -1;
        }
        org.bukkit.Material above = w.getBlockAt(x, hb.getY() + 1, z).getType();
        if (above.isSolid() || isLiquid(above)) {
            return -1;
        }
        return hb.getY();
    }

    /** true, если материал — жидкость (вода или лава). */
    private static boolean isLiquid(org.bukkit.Material m) {
        return m == org.bukkit.Material.WATER || m == org.bukkit.Material.LAVA;
    }

    /** Установить флаг региона через API WG: @flag:<имя>:{значение}
     *  (значение allow/deny/true/false или default — снять, как /rg flag -r).
     *  group — группа кнопки (all/members/owners/nonmembers/nonowners),
     *  ставится вместе со значением. */
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
        if (isUnsetState(value)) {
            plugin.wg().unsetFlag(world, region, flag);
            plugin.lang().send(p, "menu.flag-set",
                    "flag", flagName,
                    "flag-name", plugin.replace().flagName(flagName),
                    "value", "",
                    "label", plugin.lang().get("menu.value-not-set"));
            OpenMenu live = open.get(p.getUniqueId());
            if (live != null) {
                render(p, live.menu, live.ctx, live.page, live.role, live.kind);
            }
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
                "value", value,
                "label", plugin.replace().resolve("flag-values", value));
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
        // Как в menu.flag-set: {flag-name} — перевод названия флага, {flag} —
        // сырой id. group-set — экшнбар (actionbar:N! в lang.yml).
        plugin.lang().send(p, ok ? "menu.group-set" : "menu.group-set-fail",
                "flag", flag.getName(),
                "flag-name", plugin.replace().flagName(flag.getName()),
                "group", label);
        OpenMenu live = open.get(p.getUniqueId());
        if (live != null) {
            render(p, live.menu, live.ctx, live.page, live.role, live.kind);
        }
    }

    /** Кнопки списка участников (dynamic-players): головы скинов по UUID.
     *  Описание единое с меню «поиск игроков» (lang-ключи menu.ps-line-*):
     *  имя — цвет ника по роли (&#ccbf8f владелец, &#7db389 участник), лор —
     *  группа, клан (если JustTeams включён), баланс, территории и подсказка
     *  удаления (только владельцу территории). Материал кнопки — из меню
     *  (dynamic-players: material/owner-material/member-material, по умолчанию
     *  PLAYER_HEAD — голова участника по UUID). Команду шаблона
     *  @player-del:{player-id}:{role} задаёт владелец-шаблон, у остальных
     *  ролей commands пустой — только просмотр.
     *  Фильтр: ctx["_filter"] = all|owners|members. */
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
        boolean clanOn = plugin.raid().teams().enabled();
        boolean viewerOwner = "owner".equalsIgnoreCase(ctx.get("role"));
        MenuItem tpl = new MenuItem("STONE", 1, null, "", null, null, "");
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
            pc.put("ps-group", plugin.lang().get("menu."
                    + (part.owner() ? "ps-role-owner" : "ps-role-member")));
            pc.put("ps-balance", playerBalance(part.uuid()));
            pc.put("ps-balance-symbol", currencySymbol());
            pc.put("ps-clan", playerClan(part.uuid()));
            int[] counts = playerRegionCounts(part.uuid());
            pc.put("ps-regions", String.valueOf(counts[0] + counts[1]));
            pc.put("ps-reg-owner", String.valueOf(counts[0]));
            pc.put("ps-reg-member", String.valueOf(counts[1]));
            pc.put("ps-max", playerMaxRegions(part.uuid()));

            String name = tpl.process(plugin, op, pc,
                    (part.owner() ? "&#ccbf8f" : "&#7db389") + label);
            List<String> lore = new ArrayList<>();
            lore.add(tpl.process(plugin, op, pc, plugin.lang().get("menu.ps-line-group")));
            if (clanOn) {
                lore.add(tpl.process(plugin, op, pc, plugin.lang().get("menu.ps-line-clan")));
            }
            lore.add(tpl.process(plugin, op, pc, plugin.lang().get("menu.ps-line-balance")));
            lore.add(tpl.process(plugin, op, pc, plugin.lang().get("menu.ps-line-regions")));
            lore.add(tpl.process(plugin, op, pc, plugin.lang().get("menu.ps-line-max")));
            lore.add(tpl.process(plugin, op, pc, plugin.lang().get("menu.ps-line-owner")));
            lore.add(tpl.process(plugin, op, pc, plugin.lang().get("menu.ps-line-member")));
            if (viewerOwner) {
                lore.add("");
                lore.add(tpl.process(plugin, op, pc, plugin.lang().get("menu.ps-line-delete")));
            }
            List<String> cmds = null;
            if (dp.commands != null && !dp.commands.isEmpty()) {
                cmds = new ArrayList<>();
                for (String c : dp.commands) {
                    cmds.add(tpl.process(plugin, op, pc, c));
                }
            }
            // материал кнопки — из меню (dynamic-players: material / owner-material /
            // member-material), с подстановкой {player-id}/{player} и PAPI;
            // "PLAYER_HEAD" без id = голова ЭТОГО участника (ownerUuid).
            String mat = (part.owner()
                    ? (dp.ownerMaterial != null && !dp.ownerMaterial.isEmpty()
                            ? dp.ownerMaterial : dp.material)
                    : (dp.memberMaterial != null && !dp.memberMaterial.isEmpty()
                            ? dp.memberMaterial : dp.material));
            if (mat == null || mat.isEmpty()) {
                mat = "PLAYER_HEAD";
            }
            String resolved = tpl.process(plugin, op, pc, mat);
            MenuItem mi = new MenuItem(resolved, 1, null, name, lore, cmds, "");
            if (resolved.equalsIgnoreCase("PLAYER_HEAD")) {
                mi.ownerUuid(part.uuid() == null ? null : part.uuid().toString());
            }
            out.add(mi);
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
        if (sort.equals("name-az") || sort.equals("name")) {
            cols.sort((a, b) -> a.region.compareToIgnoreCase(b.region));
        } else if (sort.equals("name-za")) {
            cols.sort((a, b) -> b.region.compareToIgnoreCase(a.region));
        } else if (sort.equals("price-asc") || sort.equals("price")) {
            cols.sort((a, b) -> Double.compare(a.price, b.price));
        } else if (sort.equals("price-desc")) {
            cols.sort((a, b) -> Double.compare(b.price, a.price));
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
            pc.put("market-price", plugin.market().economy().formatAmount(o.price));
            pc.put("market-price-symbol", plugin.market().economy().symbol());
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
        plugin.lang().sendMsg(p, on
                ? "menu.market-tab-switch-all"
                : "menu.market-tab-switch-mine");
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
        // закрываем меню, чтобы игрок видел чат и ввёл срок; после ответа
        // меню снова откроется само (onDurResult)
        p.closeInventory();
        plugin.lang().sendMsg(p, "menu.dialog-dur-chat");
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
                plugin.lang().send(p, "market.listdur-set", "region", o.region, "minutes", plugin.lang().shortTime(minutes));
                // меню закрылось для ввода в чат — открываем снова с результатом
                openMarketMine(p);
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
        // закрываем меню для ввода в чат; после ответа открываем снова
        p.closeInventory();
        plugin.lang().sendMsg(p, "menu.market-period-chat");
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
                // меню закрылось для ввода в чат — открываем снова с результатом
                openMarketMine(p);
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
            plugin.lang().sendRaw(p, res);
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

    // ---------- поиск игроков и выбор территории ----------

    /** Меню поиска игроков (playersearch.yml): все игроки сервера
     *  (онлайн + оффлайн), головы как кнопки. Клик по кнопке — меню
     *  управления игроком (playerconfirm.yml): добавить участника/владельца
     *  или удалить из региона. Открывается ТОЛЬКО через меню и только
     *  владельцу/админу. */
    public boolean openPlayerSearch(Player p, org.bukkit.World w, ProtectedRegion r) {
        if (!p.hasPermission("qqregions.admin") && !plugin.wg().isOwner(r, p.getUniqueId())) {
            plugin.lang().send(p, "menu.participants-owner-only");
            return false;
        }
        Map<String, String> ctx = new HashMap<>();
        ctx.put("region", r.getId());
        ctx.put("world", w.getName());
        ctx.put("player", p.getName());
        ctx.put("role", "owner");
        return open(p, "playersearch", ctx, 0, "owner");
    }

    /** Кнопки поиска игроков: сортировка по _pdsort (az|za|balance|regions±|dist±),
     *  без себя (игрок сам себя не добавляет). Материал кнопки — PLAYER_HEAD
     *  (голова со скином игрока по UUID). На каждой голове: роль в
     *  территории, клан (если JustTeams включён), баланс, количество территорий
     *  (владелец/участник) и подсказка управления. */
    private List<MenuItem> playerSearchItems(Menu menu, Player viewer, Map<String, String> ctx) {
        List<MenuItem> out = new ArrayList<>();
        String worldName = ctx.get("world");
        if (worldName == null) {
            return out;
        }
        org.bukkit.World w = org.bukkit.Bukkit.getWorld(worldName);
        ProtectedRegion region = w == null ? null : plugin.wg().byName(w, ctx.get("region"));
        if (w == null || region == null) {
            return out;
        }
        boolean clanOn = plugin.raid().teams().enabled();
        String self = ctx.get("player");
        List<PlayerRow> rows = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (org.bukkit.entity.Player op : Bukkit.getOnlinePlayers()) {
            if (op.getName() == null || op.getName().equalsIgnoreCase(self)) {
                continue;
            }
            seen.add(op.getUniqueId().toString());
            rows.add(rowOf(viewer, op));
        }
        for (org.bukkit.OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            String n = op.getName();
            if (n == null || op.getUniqueId() == null || n.equalsIgnoreCase(self)) {
                continue;
            }
            if (!seen.add(op.getUniqueId().toString())) {
                continue;
            }
            rows.add(rowOf(viewer, op));
        }
        switch (ctx.getOrDefault("_pdsort", "az")) {
            case "za" -> rows.sort(java.util.Comparator
                    .comparingInt((PlayerRow r) -> r.online() ? 0 : 1)
                    .thenComparing(r -> r.name(), String.CASE_INSENSITIVE_ORDER.reversed()));
            case "balance" -> rows.sort(java.util.Comparator
                    .comparingDouble((PlayerRow r) -> r.balance()).reversed()
                    .thenComparing(r -> r.name(), String.CASE_INSENSITIVE_ORDER));
            case "regions-asc" -> rows.sort(java.util.Comparator
                    .comparingInt((PlayerRow r) -> r.regions())
                    .thenComparing(r -> r.name(), String.CASE_INSENSITIVE_ORDER));
            case "regions-desc" -> rows.sort(java.util.Comparator
                    .comparingInt((PlayerRow r) -> r.regions()).reversed()
                    .thenComparing(r -> r.name(), String.CASE_INSENSITIVE_ORDER));
            case "dist-near" -> rows.sort(java.util.Comparator
                    .comparingInt((PlayerRow r) -> r.online() && r.dist() >= 0 ? 0 : 1)
                    .thenComparingDouble(r -> r.dist() < 0 ? Double.MAX_VALUE : r.dist()));
            case "dist-far" -> rows.sort(java.util.Comparator
                    .comparingInt((PlayerRow r) -> r.online() && r.dist() >= 0 ? 0 : 1)
                    .thenComparing(java.util.Comparator
                            .comparingDouble((PlayerRow r) -> r.dist() < 0 ? -1 : r.dist()).reversed()));
            default -> rows.sort(java.util.Comparator
                    .comparingInt((PlayerRow r) -> r.online() ? 0 : 1)
                    .thenComparing(r -> r.name(), String.CASE_INSENSITIVE_ORDER));
        }
        MenuItem tpl = new MenuItem("STONE", 1, null, "", null, null, "");
        // поиск по нику (ctx["_playersearch"]): regex как у поиска регионов
        String playerQuery = ctx.get("_playersearch");
        Pattern ppat = (playerQuery == null || playerQuery.trim().isEmpty())
                ? null : Menu.searchPattern(playerQuery.trim());
        for (PlayerRow row : rows) {
            if (ppat != null && !ppat.matcher(row.name()).find()) {
                continue;
            }
            Map<String, String> pc = new HashMap<>(ctx);
            pc.put("name", row.name());
            boolean curOwner = plugin.wg().isOwner(region, row.uuid());
            boolean curMember = !curOwner && plugin.wg().isMember(region, row.uuid());
            pc.put("ps-group", plugin.lang().get(curOwner ? "menu.ps-role-owner"
                    : curMember ? "menu.ps-role-member" : "menu.ps-role-none"));
            pc.put("ps-balance", row.balanceLabel());
            pc.put("ps-balance-symbol", currencySymbol());
            pc.put("ps-regions", String.valueOf(row.ownerRegions() + row.memberRegions()));
            pc.put("ps-reg-owner", String.valueOf(row.ownerRegions()));
            pc.put("ps-reg-member", String.valueOf(row.memberRegions()));
            pc.put("ps-max", playerMaxRegions(row.uuid()));
            pc.put("ps-clan", playerClan(row.uuid()));
            List<String> lore = new ArrayList<>();
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.ps-line-group")));
            if (clanOn) {
                lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.ps-line-clan")));
            }
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.ps-line-balance")));
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.ps-line-regions")));
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.ps-line-max")));
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.ps-line-owner")));
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.ps-line-member")));
            lore.add("");
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.ps-line-manage")));
            MenuItem mi = new MenuItem("PLAYER_HEAD", 1, null,
                    tpl.process(plugin, viewer, pc, "&f" + row.name()), lore,
                    List.of("PLRS:" + row.uuid()), "");
            mi.ownerUuid(row.uuid().toString());
            out.add(mi);
        }
        return out;
    }

    /** Строка игрока для поиска: оффлайн позиции подгружаются с диском. */
    private PlayerRow rowOf(Player viewer, org.bukkit.OfflinePlayer op) {
        UUID uuid = op.getUniqueId();
        String name = op.getName() == null ? "" : op.getName();
        double dist = -1;
        if (op.isOnline()) {
            org.bukkit.entity.Player pl = op.getPlayer();
            if (pl != null && pl.getWorld() == viewer.getWorld()) {
                double dx = viewer.getLocation().getX() - pl.getLocation().getX();
                double dy = viewer.getLocation().getY() - pl.getLocation().getY();
                double dz = viewer.getLocation().getZ() - pl.getLocation().getZ();
                dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }
        int[] c = playerRegionCounts(uuid);
        double bal = 0;
        try {
            if (plugin.market().economy().enabled()) {
                bal = plugin.market().economy().balance(uuid);
            }
        } catch (Throwable t) {
            bal = 0;
        }
        return new PlayerRow(uuid, name, op.isOnline(), dist, bal, c[0], c[1],
                playerBalance(uuid), playerMaxRegions(uuid));
    }

    /** Клик по кнопке игрока в меню поиска: открывает меню управления
     *  (playerconfirm.yml) — добавить участника/владельца или удалить. */
    private void playerSearchClick(Player p, OpenMenu om, String spec, InventoryClickEvent e) {
        if (!p.hasPermission("qqregions.admin") && !"owner".equalsIgnoreCase(om.role)) {
            plugin.lang().send(p, "menu.participants-owner-only");
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(spec.trim());
        } catch (IllegalArgumentException ex) {
            return;
        }
        org.bukkit.World world = worldFrom(om.ctx);
        ProtectedRegion region = world == null ? null : plugin.wg().byName(world, om.ctx.get("region"));
        if (world == null || region == null) {
            plugin.lang().send(p, "menu.region-not-found");
            return;
        }
        String nick = org.bukkit.Bukkit.getOfflinePlayer(uuid).getName();
        if (nick == null) {
            nick = uuid.toString().substring(0, 8);
        }
        boolean curOwner = plugin.wg().isOwner(region, uuid);
        openPlayerConfirm(p, world, region, uuid, nick, curOwner ? "remove" : "add",
                curOwner ? "owner" : "member");
    }

    /** Число владельцев региона (для защиты «последнего владельца»). */
    private static int ownerCount(ProtectedRegion region) {
        try {
            return region.getOwners().size();
        } catch (Throwable t) {
            return 1;
        }
    }

    /** Лейбл текущей сортировки поиска игроков для сообщения/кнопки. */
    private String psSortLabel(String sort) {
        String label = plugin.lang().get("menu.ps-sort-" + sort);
        return label == null || label.isEmpty() ? sort : label;
    }

    /** Цикл сортировки поиска игроков (@ps-sort): A-Z→Z-A→баланс→
     *  регионы ±→дистанция ±. Сообщение — в экшнбар (actionbar:N! в lang). */
    private void cyclePsSort(Player p, OpenMenu om) {
        String cur = om.ctx.getOrDefault("_pdsort", "az");
        int idx = PLAYER_SORT.indexOf(cur);
        String next = PLAYER_SORT.get((idx + 1) % PLAYER_SORT.size());
        om.ctx.put("_pdsort", next);
        plugin.lang().send(p, "menu.ps-sort-changed", "sort", psSortLabel(next));
        render(p, om.menu, om.ctx, om.page, om.role, om.kind);
    }

    /** Меню выбора территории (regionpicker.yml): все регионы всех миров,
     *  фильтр-сортировка ctx _rpsort (near|far|az|za|members±|area±). */
    public boolean openRegionPicker(Player p) {
        Map<String, String> ctx = new HashMap<>();
        ctx.put("world", p.getWorld().getName());
        ctx.put("region", "");
        ctx.put("player", p.getName());
        ctx.put("role", "other");
        ctx.put("_rpsort", "near");
        return open(p, "regionpicker", ctx, 0, "other");
    }

    /** «Моя территория» из главного меню: стоим в регионе — сразу info,
     *  иначе — меню выбора территории. */
    private void openInfoOrPick(Player p, OpenMenu om) {
        ProtectedRegion here = plugin.wg().current(p);
        if (here != null) {
            if (openInfo(p, p.getWorld(), here)) {
                return;
            }
            plugin.lang().send(p, "info.menu-disabled");
            OpenMenu live = open.get(p.getUniqueId());
            if (live != null) {
                render(p, live.menu, live.ctx, live.page, live.role, live.kind);
            }
            return;
        }
        openRegionPicker(p);
    }

    /** Кнопка «Создать территорию» (@select): включить интерактивный выбор,
     *  как /region select без аргументов (права qqregions.select не нужны в
     *  меню — кнопка уже под qqregions.use, но проверяем на всякий случай). */
    private void startInteractiveSelect(Player p) {
        if (!p.hasPermission("qqregions.admin") && !p.hasPermission("qqregions.select")) {
            plugin.lang().send(p, "general.no-permission");
            return;
        }
        if (plugin.selections().startSession(p)) {
            return;
        }
        plugin.selections().endSession(p);
        plugin.lang().send(p, "select.interactive-off");
    }

    /** Кнопки меню выбора территории: регион/мир/тип/люди/расстояние,
     *  сортировка по _rpsort, клик = информация о регионе. */
    private List<MenuItem> regionPickerItems(Menu menu, Player viewer, Map<String, String> ctx) {
        List<MenuItem> out = new ArrayList<>();
        List<RegionRow> rows = new ArrayList<>();
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (ProtectedRegion r : plugin.wg().all(w)) {
                String name = r.getId();
                if (excludeFromSearch(name)) {
                    continue;
                }
                int people = plugin.wg().participants(r).size();
                double dist = regionDist(viewer, w, r);
                long area = regionArea(r);
                rows.add(new RegionRow(w.getName(), name, people, dist, area));
            }
        }
        String sort = ctx.getOrDefault("_rpsort", "near");
        switch (sort) {
            case "far" -> rows.sort((a, b) -> Double.compare(b.dist, a.dist));
            case "az" -> rows.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
            case "za" -> rows.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(b.name, a.name));
            case "members", "members-desc" -> rows.sort((a, b) -> Integer.compare(b.people, a.people));
            case "members-asc" -> rows.sort((a, b) -> Integer.compare(a.people, b.people));
            case "area-desc" -> rows.sort((a, b) -> Long.compare(b.area, a.area));
            case "area-asc" -> rows.sort((a, b) -> Long.compare(a.area, b.area));
            default -> rows.sort((a, b) -> Double.compare(a.dist, b.dist));
        }
        MenuItem tpl = new MenuItem("STONE", 1, null, "", null, null, "");
        for (RegionRow row : rows) {
            Map<String, String> pc = new HashMap<>(ctx);
            pc.put("region", row.name);
            pc.put("world", row.world);
            org.bukkit.World w = org.bukkit.Bukkit.getWorld(row.world);
            ProtectedRegion r = w == null ? null : plugin.wg().byName(w, row.name);
            pc.put("rp-world", row.world);
            pc.put("rp-type", r == null ? "?" : r.getType().getName());
            pc.put("rp-people", String.valueOf(row.people));
            pc.put("rp-area", String.valueOf(row.area));
            pc.put("rp-dist", row.dist < 0 ? plugin.lang().get("menu.rp-dist-none")
                    : String.valueOf((int) Math.ceil(row.dist)));
            List<String> lore = new ArrayList<>();
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.rp-world-line")));
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.rp-type-line")));
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.rp-people-line")));
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.rp-area-line")));
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.rp-dist-line")));
            lore.add(tpl.process(plugin, viewer, pc, plugin.lang().get("menu.rp-click")));
            out.add(new MenuItem("COMPASS", 1, null, "&e" + row.name, lore,
                    List.of("@rinfo:" + row.world + ":" + row.name), ""));
        }
        return out;
    }

    /** Расстояние игрока до центра региона (в блоках) или -1. */
    private static double regionDist(Player viewer, org.bukkit.World w, ProtectedRegion r) {
        try {
            com.sk89q.worldedit.math.BlockVector3 min = r.getMinimumPoint();
            com.sk89q.worldedit.math.BlockVector3 max = r.getMaximumPoint();
            double cx = (min.getX() + max.getX()) / 2.0;
            double cz = (min.getZ() + max.getZ()) / 2.0;
            if (viewer.getWorld() == w) {
                double dx = viewer.getLocation().getX() - cx;
                double dz = viewer.getLocation().getZ() - cz;
                return Math.sqrt(dx * dx + dz * dz);
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Лейбл текущей сортировки выбора территории для кнопки-переключателя. */
    private String rpSortLabel(String sort) {
        String label = plugin.lang().get("menu.rp-sort-" + sort);
        return label == null || label.isEmpty() ? sort : label;
    }

    /** Цикл сортировки выбора территории: близко→далеко→A-Z→Z-A→люди±→
     *  площадь±. Сообщение — в экшнбар (actionbar:N! в lang). */
    private void cycleRegionSort(Player p, OpenMenu om) {
        String cur = om.ctx.getOrDefault("_rpsort", "near");
        int idx = REGION_SORT.indexOf(cur);
        String next = REGION_SORT.get((idx + 1) % REGION_SORT.size());
        om.ctx.put("_rpsort", next);
        plugin.lang().send(p, "menu.rp-sort-changed", "sort", rpSortLabel(next));
        render(p, om.menu, om.ctx, om.page, om.role, om.kind);
    }

    /** Цветной список режимов сортировки для кнопки-фильтра: текущий — &a,
     *  остальные — &7, по одному на строку. Ключи lang: menu.<prefix>-<режим>. */
    private String sortPickList(String prefix, String current) {
        List<String> order;
        if (prefix.startsWith("market")) {
            order = MARKET_SORT;
        } else if (prefix.startsWith("rp-")) {
            order = REGION_SORT;
        } else {
            order = PLAYER_SORT;
        }
        StringBuilder sb = new StringBuilder();
        for (String mode : order) {
            String label = plugin.lang().get("menu." + prefix + "-" + mode);
            label = label == null || label.isEmpty() ? mode
                    : dev.qqregions.util.Msg.toLegacy(dev.qqregions.util.Msg.color(label));
            sb.append(mode.equalsIgnoreCase(current) ? "&a- " : "&7- ").append(label).append('\n');
        }
        return sb.length() == 0 ? "" : sb.substring(0, sb.length() - 1);
    }

    /** Кнопка удаления территории с подтверждением: @region-delete. */
    private void confirmDeleteRegion(Player p, OpenMenu om) {
        if (!p.hasPermission("qqregions.admin") && !"owner".equalsIgnoreCase(om.role)) {
            plugin.lang().send(p, "delete.not-owner");
            return;
        }
        org.bukkit.World world = worldFrom(om.ctx);
        ProtectedRegion region = world == null ? null : plugin.wg().byName(world, om.ctx.get("region"));
        if (world == null || region == null) {
            plugin.lang().send(p, "delete.not-found");
            return;
        }
        try {
            plugin.wg().delete(world, region.getId());
            plugin.lang().send(p, "delete.ok", "region", region.getId());
        } catch (RegionException ex) {
            plugin.lang().send(p, "delete.fail", "error", ex.getMessage());
            OpenMenu live = open.get(p.getUniqueId());
            if (live != null) {
                render(p, live.menu, live.ctx, live.page, live.role, live.kind);
            }
            return;
        }
        closeOpen(p);
    }

    /** Контекст рейд-кнопки в инфо-меню: данные клана игрока (JustTeams).
     *  Заполняет {raid-clan} {raid-balance} {raid-online} {raid-total}
     *  {raid-in-region} {raid-needed}. Без хука/клана — "—" (menu.raid-empty). */
    private void fillRaidCtx(Player p, ProtectedRegion region, Map<String, String> ctx) {
        String empty = plugin.lang().get("menu.raid-empty");
        ctx.put("raid-clan", empty);
        ctx.put("raid-balance", empty);
        ctx.put("raid-balance-symbol", "");
        ctx.put("raid-online", empty);
        ctx.put("raid-total", empty);
        ctx.put("raid-in-region", empty);
        Config.RaidOptions opts = plugin.config().raid();
        ctx.put("raid-needed", opts.enabled ? String.valueOf(opts.minAttackers) : empty);
        JustTeamsHook teams = plugin.raid().teams();
        if (teams == null || !teams.enabled()) {
            return;
        }
        Player pl = p;
        if (pl == null && ctx.get("player") != null) {
            pl = Bukkit.getPlayerExact(ctx.get("player"));
        }
        if (pl == null) {
            return;
        }
        JustTeamsHook.TeamRef team = teams.team(pl.getUniqueId());
        if (team == null) {
            return;
        }
        ctx.put("raid-clan", team.name());
        double bal = teams.balance(team);
        if (bal >= 0) {
            ctx.put("raid-balance", plugin.raid().raidMoney(bal));
            ctx.put("raid-balance-symbol", plugin.raid().raidSymbol());
        }
        List<UUID> online = teams.onlineMembers(team);
        ctx.put("raid-online", String.valueOf(online.size()));
        ctx.put("raid-total", String.valueOf(teams.totalMembers(team)));
        int inside = 0;
        org.bukkit.World w = worldFrom(ctx);
        for (UUID u : online) {
            org.bukkit.entity.Player mp = Bukkit.getPlayer(u);
            if (mp == null || !mp.isOnline() || w == null || region == null) {
                continue;
            }
            for (ProtectedRegion cur : plugin.wg().at(w, mp.getLocation())) {
                if (cur.getId().equals(region.getId())) {
                    inside++;
                    break;
                }
            }
        }
        ctx.put("raid-in-region", String.valueOf(inside));
    }

    /** Строка выбора территории в меню выбора. */
    private record RegionRow(String world, String name, int people, double dist, long area) {
    }

    /** Строка игрока для меню поиска игроков. */
    private record PlayerRow(UUID uuid, String name, boolean online, double dist,
                             double balance, int ownerRegions, int memberRegions,
                             String balanceLabel, String maxLabel) {

        public int regions() {
            return ownerRegions + memberRegions;
        }
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
            if (key.isEmpty() || whitelist.contains(key) || shopIgnore.contains(key)) {
                continue;
            }
            boolean isOwned = owned.contains(key);
            if (isOwned) {
                // Купленный флаг: по флагу «показывать купленные красным
                // стеклом» (shop.yml flags.bought-display: RED_GLASS) ставим
                // неактивную кнопку; иначе флаг просто не показываем.
                if (plugin.shop().flagsBoughtRedGlass()) {
                    String flagName = plugin.replace().flagName(id);
                    Map<String, String> pc = new HashMap<>(ctx);
                    pc.put("flag-name", flagName);
                    pc.put("name", flagName);
                    String name = tpl.process(plugin, player, pc,
                            plugin.lang().get("shop.bought-name"));
                    out.add(new MenuItem("RED_STAINED_GLASS_PANE", 1, null, name,
                            List.of(tpl.process(plugin, player, pc,
                                    plugin.lang().get("shop.bought-lore"))), null, ""));
                }
                continue;
            }
            // Гейт магазина: флаг показывается только с правом на него —
            // qqregions.flags.use.<флаг> или группа-шаблон flag-groups
            // (см. Menu.canUseFlag; даётся группам через LuckPerms или игроку
            // по отдельности). Принимаются и legacy qqregions.flags.<флаг>.
            // Админ/оп видят всё. Купленные флаги обработаны выше.
            if (!Menu.canUseFlag(plugin, player, "qqregions.flags.use.", id)) {
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
            pc.put("price", plugin.market().economy().formatAmount(price));
            pc.put("price-symbol", plugin.market().economy().symbol());
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

    /** Кнопки магазина расширений: пакеты площади, пакеты «+регион» и
     *  пользовательские товары (custom-items), отсортированные по priority.
     *  Исчерпавшие лимит (max-purchases) при bought-display: HIDE исчезают
     *  (остальные сдвигаются вперёд), при RED_GLASS остаются красным стеклом. */
    private List<MenuItem> blockShopItems(Menu menu, Player player, Map<String, String> ctx) {
        List<MenuItem> out = new ArrayList<>();
        MenuItem tpl = new MenuItem("STONE", 1, null, "", null, null, "");
        UUID uuid = player.getUniqueId();
        String langArea = plugin.lang().get("menu.lore-pack-area");
        String langRegion = plugin.lang().get("menu.lore-pack-region");
        String langRegionRepeat = plugin.lang().get("menu.lore-pack-repeatable");
        String langPrice = plugin.lang().get("menu.lore-price");
        String langBuy = plugin.lang().get("menu.lore-buy");
        for (dev.qqregions.shop.ShopManager.ShopProduct p : plugin.shop().allProducts()) {
            int count = plugin.shop().purchasedCount(uuid, p.kind(), p.id());
            boolean bought = count > 0;
            boolean depleted = bought && p.maxPurchases() > 0 && count >= p.maxPurchases();
            if (depleted) {
                if ("RED_GLASS".equals(p.boughtDisplay())) {
                    Map<String, String> pc = bakePackCtx(ctx, p);
                    String name = tpl.process(plugin, player, pc,
                            plugin.lang().get("shop.bought-name"));
                    out.add(new MenuItem("RED_STAINED_GLASS_PANE", 1, null, name,
                            List.of(tpl.process(plugin, player, pc,
                                    plugin.lang().get("shop.bought-lore"))), null, ""));
                }
                continue;
            }
            // Товар без цены (нет price: в shop.yml или 0) НЕ продаётся:
            // не выводим кнопку (иначе клик даст shop.not-found).
            if (p.price() <= 0) {
                continue;
            }
            Map<String, String> pc = bakePackCtx(ctx, p);
            String name = tpl.process(plugin, player, pc, "&f{pack-name}");
            List<String> lore = new ArrayList<>();
            switch (p.kind()) {
                case "area" -> {
                    lore.add(tpl.process(plugin, player, pc, langArea));
                    lore.add(tpl.process(plugin, player, pc, langPrice));
                }
                case "region" -> {
                    lore.add(tpl.process(plugin, player, pc, langRegion));
                    if (p.maxPurchases() > 0) {
                        lore.add(tpl.process(plugin, player, pc, langPrice));
                    } else {
                        lore.add(tpl.process(plugin, player, pc, langRegionRepeat));
                    }
                }
                default -> {
                    if (p.lore() != null) {
                        for (String l : p.lore()) {
                            lore.add(tpl.process(plugin, player, pc, l));
                        }
                    }
                    lore.add(tpl.process(plugin, player, pc, langPrice));
                }
            }
            lore.add(langBuy);
            String mat = p.material() == null || p.material().isEmpty() ? "EMERALD" : p.material();
            out.add(new MenuItem(mat, 1, null, name, lore,
                    List.of("@shop-buy:" + p.kind() + ":" + p.id()), ""));
        }
        return out;
    }

    /** Контекст кнопки товара: {pack-name} {pack-amount} {name} {price} {price-symbol}. */
    private Map<String, String> bakePackCtx(Map<String, String> ctx,
                                            dev.qqregions.shop.ShopManager.ShopProduct p) {
        Map<String, String> pc = new HashMap<>(ctx);
        pc.put("pack-name", p.name());
        pc.put("pack-amount", String.valueOf(p.amount()));
        pc.put("name", p.name());
        pc.put("price", plugin.market().economy().formatAmount(p.price()));
        pc.put("price-symbol", plugin.market().economy().symbol());
        return pc;
    }

    /** Обработчик @shop-buy:<flag|area|region|custom>:<id>. */
    private void shopBuy(Player p, String spec) {
        String[] parts = spec.split(":", 2);
        if (parts.length < 2) {
            return;
        }
        String kind = parts[0].trim().toLowerCase(java.util.Locale.ROOT);
        String id = parts[1].trim();
        String res;
        if ("flag".equals(kind)) {
            // Страховка: покупать флаг можно только с правом qqregions.flags.use.<флаг>
            // или группы-шаблона flag-groups (см. Menu.canUseFlag), либо владея
            // им (тогда ответит "already").
            if (!Menu.canUseFlag(plugin, p, "qqregions.flags.use.", id)
                    && !plugin.shop().ownedFlags(p.getUniqueId())
                    .contains(id.toLowerCase(java.util.Locale.ROOT))) {
                return;
            }
            res = plugin.shop().buyFlag(p.getUniqueId(), id);
        } else {
            res = plugin.shop().buyProduct(p.getUniqueId(), kind, id);
        }
        switch (res) {
            case "ok" -> {
                if ("flag".equals(kind)) {
                    plugin.lang().send(p, "shop.flag-bought",
                            "flag-name", plugin.replace().flagName(id),
                            "price", plugin.market().economy().formatAmount(plugin.shop().priceOf(id)),
                            "price-symbol", plugin.market().economy().symbol());
                } else {
                    dev.qqregions.shop.ShopManager.ShopProduct prod = plugin.shop().product(kind, id);
                    if (prod == null) {
                        plugin.lang().send(p, "shop.error");
                    } else {
                        plugin.lang().send(p, "shop.pack-bought",
                                "pack-name", prod.name(),
                                "price", plugin.market().economy().formatAmount(prod.price()),
                                "price-symbol", plugin.market().economy().symbol());
                        runProductActions(p, prod);
                    }
                }
            }
            case "already" -> plugin.lang().send(p, "shop.already",
                    "flag-name", plugin.replace().flagName(id));
            case "limit" -> plugin.lang().send(p, "shop.limit");
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

    /** Команды товара после покупки: при заданных conditions (AND) выполняются
     *  allow-cmds, иначе deny-cmds; без условий — commands (или allow-cmds).
     *  {player} подставляется ником игрока, все действия — через Actions. */
    private void runProductActions(Player p, dev.qqregions.shop.ShopManager.ShopProduct prod) {
        List<String> toRun;
        if (prod.conditions() != null && !prod.conditions().isEmpty()) {
            boolean ok = true;
            for (String cond : prod.conditions()) {
                if (!dev.qqregions.util.Expressions.matches(cond, p)) {
                    ok = false;
                    break;
                }
            }
            toRun = ok ? prod.allowCmds() : prod.denyCmds();
        } else if (prod.commands() != null && !prod.commands().isEmpty()) {
            toRun = prod.commands();
        } else {
            toRun = prod.allowCmds();
        }
        if (toRun == null) {
            return;
        }
        for (String c : toRun) {
            if (c == null) {
                continue;
            }
            String cooked = c.replace("{player}", p.getName()).trim();
            if (!cooked.isEmpty()) {
                MenuAction.run(plugin, p, cooked);
            }
        }
    }

    /** Кнопка входа на псевдокоманды меню игроков.
     *  Вместо мгновенного удаления открывает меню подтверждения
     *  (playerconfirm.yml) с инфо-кнопкой об игроке. */
    private void removeParticipant(Player p, OpenMenu om, String spec) {
        if (!p.hasPermission("qqregions.admin") && !"owner".equalsIgnoreCase(om.role)) {
            plugin.lang().send(p, "menu.participants-owner-only");
            return;
        }
        String[] parts = spec.split(":", 2);
        if (parts.length < 2) {
            return;
        }
        UUID uuid = resolvePlayerUuid(parts[0].trim());
        org.bukkit.World world = worldFrom(om.ctx);
        ProtectedRegion region = world == null ? null : plugin.wg().byName(world, om.ctx.get("region"));
        if (world == null || region == null || uuid == null) {
            return;
        }
        boolean curOwner = plugin.wg().isOwner(region, uuid);
        String nick = participantName(region, uuid, parts[0].trim());
        if (curOwner && ownerCount(region) <= 1) {
            plugin.lang().send(p, "remove.last-owner");
            render(p, om.menu, om.ctx, om.page, om.role, om.kind);
            return;
        }
        openPlayerConfirm(p, world, region, uuid, nick, "remove", curOwner ? "owner" : "member");
    }

    /** Открыть меню подтверждения добавления/удаления игрока (playerconfirm.yml):
     *  «Добавить участника/владельца» (@pc-add) и «Удалить» (@pc-remove).
     *  op — "add"|"remove"; role — текущая роль игрока в территории (для
     *  шаблона и legacy-контекста). */
    private void openPlayerConfirm(Player p, org.bukkit.World world, ProtectedRegion region,
                                   UUID uuid, String nick, String op, String role) {
        if (!p.hasPermission("qqregions.admin") && !plugin.wg().isOwner(region, p.getUniqueId())) {
            plugin.lang().send(p, "menu.participants-owner-only");
            return;
        }
        boolean curOwner = plugin.wg().isOwner(region, uuid);
        boolean curMember = !curOwner && plugin.wg().isMember(region, uuid);
        String roleKey = curOwner ? "owner" : curMember ? "member" : "none";
        int[] c = playerRegionCounts(uuid);
        Map<String, String> ctx = new HashMap<>();
        ctx.put("world", world.getName());
        ctx.put("region", region.getId());
        ctx.put("player", p.getName());
        ctx.put("_pc-op", op);
        ctx.put("_pc-uuid", uuid.toString());
        ctx.put("pc-uuid", uuid.toString());
        ctx.put("_pc-role", role);
        ctx.put("pc-player", nick);
        ctx.put("pc-role", plugin.lang().get("menu.role-" + roleKey));
        ctx.put("pc-action", plugin.lang().get("menu.pc-" + ("add".equalsIgnoreCase(op) ? "add" : "remove")));
        ctx.put("pc-balance", playerBalance(uuid));
        ctx.put("pc-balance-symbol", currencySymbol());
        ctx.put("pc-clan", playerClan(uuid));
        ctx.put("pc-regions", String.valueOf(c[0] + c[1]));
        ctx.put("pc-reg-owner", String.valueOf(c[0]));
        ctx.put("pc-reg-member", String.valueOf(c[1]));
        ctx.put("pc-max", playerMaxRegions(uuid));
        open(p, "playerconfirm", ctx, 0, "owner");
    }

    /** Выполнить операцию из меню игрока (playerconfirm.yml) и вернуться в
     *  предыдущее меню. op — "add"|"remove"; role — целевая роль (для add —
     *  кого добавить, для remove — кого убрать; НЕ текущая роль игрока).
     *  Двойной клик не страшен: после перехода инвентарь подтверждения
     *  больше не в open — клик игнорируется. */
    private void execPlayerAction(Player p, OpenMenu om, String op, String role) {
        String rawUuid = om.ctx.get("_pc-uuid");
        UUID uuid = null;
        if (rawUuid != null) {
            try {
                uuid = UUID.fromString(rawUuid);
            } catch (IllegalArgumentException ex) {
                uuid = null;
            }
        }
        org.bukkit.World world = worldFrom(om.ctx);
        ProtectedRegion region = world == null ? null : plugin.wg().byName(world, om.ctx.get("region"));
        if (world == null || region == null || uuid == null) {
            plugin.lang().send(p, "menu.region-not-found");
            goBack(p);
            return;
        }
        String nick = om.ctx.getOrDefault("pc-player", uuid.toString());
        if ("add".equalsIgnoreCase(op)) {
            boolean owner = "owner".equalsIgnoreCase(role);
            plugin.wg().addPlayer(world, region, uuid, owner);
            plugin.lang().send(p, owner ? "menu.player-added-owner" : "menu.player-added-member",
                    "player", nick);
        } else {
            boolean targetOwner = "owner".equalsIgnoreCase(role);
            boolean curOwner = plugin.wg().isOwner(region, uuid);
            if (targetOwner && curOwner && ownerCount(region) <= 1) {
                plugin.lang().send(p, "remove.last-owner");
                goBack(p);
                return;
            }
            plugin.wg().removePlayer(world, region, uuid, targetOwner);
            plugin.lang().send(p, targetOwner ? "remove.ok-owner" : "remove.ok-member",
                    "target", nick, "region", region.getId());
        }
        goBack(p);
    }

    /** Legacy: выполнить операцию из контекста подтверждения (@pc-confirm). */
    private void confirmPlayerAction(Player p, OpenMenu om) {
        execPlayerAction(p, om, om.ctx.get("_pc-op"), om.ctx.get("_pc-role"));
    }

    /** Кнопка «Добавить игрока» в playerconfirm: ЛКМ — участник, ПКМ —
     *  владелец, Shift+ЛКМ — убрать участника, Shift+ПКМ — убрать владельца
     *  (подтверждение — сам выбор действия в этом меню). */
    private void playerConfirmAction(Player p, OpenMenu om, org.bukkit.event.inventory.ClickType ct) {
        switch (ct) {
            case RIGHT -> execPlayerAction(p, om, "add", "owner");
            case SHIFT_LEFT -> execPlayerAction(p, om, "remove", "member");
            case SHIFT_RIGHT -> execPlayerAction(p, om, "remove", "owner");
            default -> execPlayerAction(p, om, "add", "member");
        }
    }

    /** Legacy-кнопка «Удалить» (@pc-remove, без кнопки в дефолтном playerconfirm):
     *  убрать игрока по ЕГО текущей роли (у владельца — защита последнего). */
    private void playerConfirmRemove(Player p, OpenMenu om) {
        org.bukkit.World world = worldFrom(om.ctx);
        ProtectedRegion region = world == null ? null : plugin.wg().byName(world, om.ctx.get("region"));
        UUID uuid = null;
        String rawUuid = om.ctx.get("_pc-uuid");
        if (rawUuid != null) {
            try {
                uuid = UUID.fromString(rawUuid);
            } catch (IllegalArgumentException ex) {
                uuid = null;
            }
        }
        boolean curOwner = region != null && uuid != null && plugin.wg().isOwner(region, uuid);
        execPlayerAction(p, om, "remove", curOwner ? "owner" : "member");
    }

    /** Сколько регионов у игрока всего по мирам: [0]=владелец, [1]=участник. */
    private int[] playerRegionCounts(UUID uuid) {
        int owners = 0;
        int members = 0;
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (ProtectedRegion r : plugin.wg().all(w)) {
                try {
                    if (r.getOwners().contains(uuid)) {
                        owners++;
                    }
                    if (r.getMembers().contains(uuid)) {
                        members++;
                    }
                } catch (Throwable ignored) {
                    // WG сломано на этом регионе — пропускаем
                }
            }
        }
        return new int[]{owners, members};
    }

    /** Ник игрока в регионе (по Participant) или запасной вариант. */
    private String participantName(ProtectedRegion region, UUID uuid, String fallback) {
        for (dev.qqregions.wg.Wg.Participant pa : plugin.wg().participants(region)) {
            if (uuid.equals(pa.uuid())) {
                return pa.name() != null ? pa.name() : fallback;
            }
        }
        return fallback;
    }

    /** UUID по строке: либо готовый UUID, либо ник игрока (оффлайн). */
    private UUID resolvePlayerUuid(String id) {
        try {
            return UUID.fromString(id.trim());
        } catch (IllegalArgumentException ex) {
            try {
                org.bukkit.OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(id.trim());
                return op == null ? null : op.getUniqueId();
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** Баланс игрока через Vault (или «—», если экономика недоступна). Число БЕЗ символа валюты —
     *  символ отдаётся отдельным заполнителем {…-symbol}. */
    private String playerBalance(UUID uuid) {
        try {
            if (!plugin.market().economy().enabled()) {
                return plugin.lang().get("menu.time-empty");
            }
            double bal = plugin.market().economy().balance(uuid);
            return plugin.market().economy().formatAmount(bal);
        } catch (Throwable t) {
            return plugin.lang().get("menu.time-empty");
        }
    }

    /** Символ валюты для заполнителей {…-symbol} (пусто, если экономика выключена). */
    private String currencySymbol() {
        try {
            if (!plugin.market().economy().enabled()) {
                return "";
            }
            return plugin.market().economy().symbol();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Клан игрока через JustTeams (или «—»). */
    private String playerClan(UUID uuid) {
        try {
            dev.qqregions.raid.JustTeamsHook.TeamRef team = plugin.raid().teams().team(uuid);
            return team == null ? plugin.lang().get("menu.time-empty") : team.name();
        } catch (Throwable t) {
            return plugin.lang().get("menu.time-empty");
        }
    }

    /** Максимум регионов игрока (config max-regions + купленные «+регионы»;
     *  «∞» — без лимита / сразу админ-обход прав). */
    private String playerMaxRegions(UUID uuid) {
        String inf = plugin.lang().get("menu.limit-inf");
        try {
            int maxR = plugin.config().maxRegions();
            if (maxR <= 0) {
                return inf;
            }
            int extra = plugin.shop().extraRegions(uuid);
            org.bukkit.OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(uuid);
            if (op.isOnline() && plugin.selections().isBypassed(op.getPlayer())) {
                return inf;
            }
            return String.valueOf(maxR + extra);
        } catch (Throwable t) {
            return inf;
        }
    }

    /** Начать ввод запроса в чат: фильтр по флагам/рынку или поиск региона по имени
     *  (kind: flag|market|region). Меню при этом закрывается — игрок пишет в чат,
     *  результат применения открывает меню заново (applySearch). */
    private void startSearchPrompt(Player p, OpenMenu om, String kind) {
        pendingSearch.put(p.getUniqueId(), new SearchPrompt(om, kind));
        // закрываем меню: игрок вводит запрос в чат, результат открывает
        // меню заново (applySearch). Без закрытия чат «прилипает» к инвентарю.
        p.closeInventory();
        // Поиск игрока — своя подсказка (menu.player-search-prompt), у
        // рынка/флагов/регионов — общая (market.search-prompt).
        plugin.lang().sendMsg(p, "player".equalsIgnoreCase(kind)
                ? "menu.player-search-prompt" : "market.search-prompt");
        plugin.lang().sendMsg(p, "market.search-cancel");
    }

    /** Цикл сортировки предложений рынка: Название A-Z → Название Z-A →
     *  Цена по возрастанию → Цена по убыванию → По умолчанию. */
    private void cycleSort(Player p, OpenMenu om) {
        String cur = om.ctx.getOrDefault("_sort", "default");
        int idx = MARKET_SORT.indexOf(cur);
        String next = MARKET_SORT.get((idx + 1) % MARKET_SORT.size());
        om.ctx.put("_sort", next);
        plugin.lang().send(p, "market.sort-set", "mode", marketSortLabel(next));
        render(p, om.menu, om.ctx, om.page, om.role, om.kind);
    }

    /** Локализованная подпись режима сортировки рынка (menu.market-sort-*). */
    private String marketSortLabel(String sort) {
        String label = plugin.lang().get("menu.market-sort-" + sort);
        return label == null || label.isEmpty() ? sort : label;
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
            plugin.lang().sendMsg(e.getPlayer(), "market.search-off");
            return;
        }
        if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("отмена")
                || text.equalsIgnoreCase("сброс") || text.equalsIgnoreCase("off")) {
            plugin.lang().sendMsg(e.getPlayer(), "market.search-off");
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
        String kind = pr.kind.toLowerCase(java.util.Locale.ROOT);
        String key = switch (kind) {
            case "market" -> "_marketsearch";
            case "flag", "flags" -> "_flagsearch";
            default -> "_playersearch";
        };
        String menuName = switch (kind) {
            case "market" -> "market";
            case "flag", "flags" -> "flags";
            default -> "playersearch";
        };
        plugin.lang().sendMsg(p, "market.search-set", "query", query);
        OpenMenu live = open.get(id);
        if (live != null) {
            // меню ещё открыто — кладём фильтр в ЖИВОЙ контекст и перерисовываем
            live.ctx.put(key, query);
            render(p, live.menu, live.ctx, live.page, live.role, live.kind);
        } else {
            // инвентарь закрыли во время ввода — переоткрываем с фильтром
            pr.om.ctx.put(key, query);
            open(p, menuName, pr.om.ctx, 0, pr.om.role, false);
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
        plugin.lang().sendMsg(p, "menu.region-search-many", "query", query,
                "regions", String.valueOf(matches.size()));
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
        MenuItem tpl = new MenuItem("STONE", 1, null, "", null, null, "");
        for (String name : findRegions(q)) {
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                ProtectedRegion r = plugin.wg().byName(w, name);
                if (r == null) {
                    continue;
                }
                Map<String, String> pc = new HashMap<>(ctx);
                pc.put("region", r.getId());
                pc.put("world", w.getName());
                pc.put("rp-world", w.getName());
                pc.put("rp-type", r.getType().getName());
                out.add(new MenuItem("COMPASS", 1, null,
                        "&e" + name, List.of(
                                tpl.process(plugin, viewer, pc, plugin.lang().get("menu.rp-world-line")),
                                tpl.process(plugin, viewer, pc, plugin.lang().get("menu.rp-type-line")),
                                plugin.lang().get("menu.rp-click")),
                        List.of("@rinfo:" + w.getName() + ":" + r.getId()), ""));
                break;
            }
        }
        return out;
    }

    /** Клик по найденному региону из меню regionsearch/regionpicker: открывает
     *  info с ролью игрока. Фиксированный формат кнопки: @rinfo:мир:регион. */
    private void openFoundRegion(Player p, String spec) {
        int i = spec.indexOf(':');
        if (i <= 0 || i == spec.length() - 1) {
            return;
        }
        String worldName = spec.substring(0, i);
        String name = spec.substring(i + 1);
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
        // закрываем меню: игрок вводит ник в чат, после ответа меню
        // открывается снова (addPlayerFromChat)
        p.closeInventory();
        plugin.lang().sendMsg(p, ownerRole ? "menu.add-chat-prompt-owner" : "menu.add-chat-prompt-member");
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
        UUID target = resolvePlayerUuid(name);
        if (target == null) {
            plugin.lang().send(p, "menu.player-not-found", "player", name);
            return;
        }
        // Меню закрылось при открытии чата (клиент закрывает инвентарь), поэтому
        // вернуть «Назад» в список игроков: добавляем его в историю вручную.
        Deque<NavState> stack = history.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        stack.push(new NavState("players", new HashMap<>(pr.om.ctx), pr.om.role));
        while (stack.size() > 20) {
            stack.removeLast();
        }
        // Вместо мгновенного добавления — меню подтверждения с инфо об игроке.
        openPlayerConfirm(p, world, region, target, name, "add", pr.kind);
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
        /** Тики до следующего автообновления (сбрасываются кликом по кнопке). */
        int ticks;
        /** Время последней реальной перерисовки (ms) — для дебаунса кликов. */
        long lastRenderAt;

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