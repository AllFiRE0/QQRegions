package dev.qqregions.commands;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.qqregions.QQRegions;
import dev.qqregions.config.Lang;
import dev.qqregions.config.SelectionTemplate;
import dev.qqregions.selection.Selection;
import dev.qqregions.wg.RegionException;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Главный диспетчер команды /region и всех её подкоманд.
 */
public class RegionCommand {

    private static final List<String> SUBCOMMANDS = List.of(
            "select", "create", "delete", "info", "add", "remove", "flags", "reload", "help",
            "visible", "view", "raid", "sell", "rent", "buy", "tenant", "market");

    private final QQRegions plugin;
    private final SelectCommand selectCommand;

    public RegionCommand(QQRegions plugin) {
        this.plugin = plugin;
        this.selectCommand = new SelectCommand(plugin);
    }

    public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            // /region без аргументов — главное меню территорий (меню, а не справка).
            if (sender instanceof Player p) {
                if (plugin.menus().openMain(p)) {
                    return true;
                }
            }
            help(sender, label);
            return true;
        }
        if (args[0].equalsIgnoreCase("help") || args[0].equalsIgnoreCase("?")) {
            help(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload":
                doReload(sender);
                return true;
            case "select":
                if (!requirePlayer(sender)) {
                    selectCommand.run((Player) sender, label, Arrays.copyOfRange(args, 1, args.length));
                }
                return true;
            case "create":
                doCreate(sender, label, args);
                return true;
            case "delete":
                doDelete(sender, args);
                return true;
            case "info":
                doInfo(sender, args);
                return true;
            case "add":
                doAddOrRemove(sender, args, true);
                return true;
            case "remove":
                doAddOrRemove(sender, args, false);
                return true;
            case "flags":
                doFlags(sender, args);
                return true;
            case "view":
                doView(sender, args);
                return true;
            case "visible":
                doVisible(sender, args);
                return true;
            case "raid":
                doRaid(sender, args);
                return true;
            case "sell":
            case "rent":
            case "buy":
            case "tenant":
            case "market":
                doMarket(sender, label, args);
                return true;
            default:
                lang(sender, "general.unknown-subcommand", "alias", label);
                return true;
        }
    }

    // ---------- помощь ----------

    private void help(CommandSender sender, String label) {
        Lang l = plugin.lang();
        if (sender instanceof Player p) {
            if (plugin.menus().openHelp(p)) {
                return;
            }
        }
        l.sendMsg(sender, "help.header", "version", plugin.getDescription().getVersion());
        for (Object o : l.getList("help.commands")) {
            if (o instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) o;
                l.sendMsg(sender, "help.line",
                        "cmd", "/" + label + " " + m.get("usage"),
                        "desc", String.valueOf(m.get("desc")));
            }
        }
    }

    // ---------- reload ----------

    private void doReload(CommandSender sender) {
        if (sender instanceof Player && !adminPerm((Player) sender, "qqregions.reload")) {
            lang(sender, "general.no-permission");
            return;
        }
        try {
            plugin.config().reload();
            plugin.lang().reload();
            plugin.replace().reload();
            plugin.commands().register();
            plugin.menus().reload();
            plugin.market().reload();
            plugin.raid().reload();
            plugin.shop().reload();
            plugin.marketHolos().refresh();
            lang(sender, "general.reloaded");
            lang(sender, "general.reloaded-summary", "aliases", String.join(", ", plugin.config().aliases()));
        } catch (Throwable t) {
            lang(sender, "general.reload-failed", "error", t.getMessage());
        }
    }

    // ---------- create ----------

    private void doCreate(CommandSender sender, String label, String[] args) {
        if (requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (!adminPerm(p, "qqregions.create")) {
            lang(p, "general.no-permission");
            return;
        }
        if (worldDisabled(p)) {
            return;
        }
        if (args.length < 2) {
            lang(p, "general.usage", "usage", label + " " + plugin.lang().get("usage.create"));
            return;
        }
        String name = args[1];
        Selection sel = plugin.selections().get(p);
        if (sel == null) {
            lang(p, "create.none", "alias", label);
            return;
        }
        SelectionTemplate t = plugin.selections().template(p);
        if (plugin.selections().overLimit(p, sel)) {
            lang(p, "select.over-limit", "current", fmt(sel.volume()),
                    "max", fmt(plugin.selections().effectiveMaxBlocks(p)));
            return;
        }
        if (plugin.selections().belowMin(p, sel)) {
            lang(p, "select.below-min", "current", fmt(sel.volume()), "min", fmt(t.getMinBlocks()));
            return;
        }
        if (!plugin.config().namePattern().matcher(name).matches()) {
            lang(p, "create.invalid-name", "regex", plugin.config().namePattern().pattern());
            return;
        }
        if (plugin.config().isBannedRegion(name) && !plugin.selections().isBypassed(p)) {
            lang(p, "create.banned", "region", name);
            return;
        }
        int maxRegions = plugin.config().maxRegions();
        if (maxRegions > 0 && !plugin.selections().isBypassed(p)) {
            int owned = plugin.wg().ownedCount(p.getWorld(), p);
            int extra = plugin.shop().extraRegions(p.getUniqueId());
            if (owned >= maxRegions + extra) {
                lang(p, "create.region-limit", "max", fmt(maxRegions),
                        "extra", fmt(extra), "current", fmt(owned));
                return;
            }
        }
        String norm = plugin.config().normalizeName(name);
        try {
            plugin.wg().create(sel, norm, p);
            lang(p, "create.ok", "region", norm, "world", sel.getWorld().getName(), "blocks", fmt(sel.volume()));
            if (plugin.selections().session(p) != null) {
                // автозакрытие интерактивного select при создании командой
                plugin.selections().endSession(p);
            }
        } catch (RegionException e) {
            lang(p, e.getKey(), e.getKv());
        }
    }

    // ---------- delete ----------

    private void doDelete(CommandSender sender, String[] args) {
        if (requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (!adminPerm(p, "qqregions.delete")) {
            lang(p, "general.no-permission");
            return;
        }
        if (worldDisabled(p)) {
            return;
        }
        ProtectedRegion region = resolveRegion(p, args.length > 1 ? args[1] : null);
        if (region == null) {
            lang(p, "delete.none");
            return;
        }
        if (!plugin.wg().owns(region, p) && !adminPerm(p, "qqregions.admin")) {
            lang(p, "delete.not-owner");
            return;
        }
        if (plugin.config().isBannedRegion(region.getId()) && !plugin.selections().isBypassed(p)) {
            lang(p, "create.banned", "region", region.getId());
            return;
        }
        try {
            plugin.wg().delete(p.getWorld(), region.getId());
            lang(p, "delete.ok", "region", region.getId());
        } catch (RegionException e) {
            lang(p, e.getKey(), e.getKv());
        }
    }

    // ---------- info ----------

    private void doInfo(CommandSender sender, String[] args) {
        if (requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (!adminPerm(p, "qqregions.info")) {
            lang(p, "general.no-permission");
            return;
        }
        ProtectedRegion region = resolveRegion(p, args.length > 1 ? args[1] : null);
        if (region == null) {
            lang(p, "info.none");
            return;
        }
        if (!plugin.menus().openInfo(p, p.getWorld(), region)) {
            lang(p, "info.menu-disabled");
        }
    }

    // ---------- add / remove ----------

    private void doAddOrRemove(CommandSender sender, String[] args, boolean add) {
        if (requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (!adminPerm(p, "qqregions.manage")) {
            lang(p, "general.no-permission");
            return;
        }
        if (worldDisabled(p)) {
            return;
        }
        // разбор:  add [member|owner] <ник> [регион]   или  add <ник> [регион]
        boolean owner = false;
        String nick;
        String regionName = null;
        if (args.length >= 3 && (args[1].equalsIgnoreCase("member") || args[1].equalsIgnoreCase("owner"))) {
            owner = args[1].equalsIgnoreCase("owner");
            nick = args[2];
            if (args.length >= 4) {
                regionName = args[3];
            }
        } else if (args.length >= 2) {
            nick = args[1];
            if (args.length >= 3) {
                regionName = args[2];
            }
        } else {
            lang(p, "general.usage", "usage", plugin.config().commandName() + " " + plugin.lang().get("usage." + (add ? "add" : "remove")));
            return;
        }

        ProtectedRegion region = resolveRegion(p, regionName);
        if (region == null) {
            lang(p, "info.none");
            return;
        }
        if (!plugin.wg().owns(region, p) && !adminPerm(p, "qqregions.admin")) {
            lang(p, add ? "add.not-allowed" : "remove.not-allowed", "region", region.getId());
            return;
        }
        if (plugin.config().isBannedRegion(region.getId()) && !plugin.selections().isBypassed(p)) {
            lang(p, "create.banned", "region", region.getId());
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(nick);
        if (target.getName() == null) {
            lang(p, "general.player-not-found", "player", nick);
            return;
        }
        java.util.UUID uuid = target.getUniqueId();

        boolean isOwner = plugin.wg().isOwner(region, uuid);
        boolean isMember = plugin.wg().isMember(region, uuid);

        if (add) {
            if (owner ? isOwner : isMember) {
                lang(p, "add.already", "target", nick, "role", plugin.lang().get("members." + (owner ? "owner" : "member")));
                return;
            }
            plugin.wg().addPlayer(p.getWorld(), region, uuid, owner);
            lang(p, owner ? "add.ok-owner" : "add.ok-member", "target", nick, "region", region.getId());
        } else {
            if (owner ? !isOwner : !isMember) {
                lang(p, "remove.not-in", "target", nick, "role", plugin.lang().get("members." + (owner ? "owner" : "member")));
                return;
            }
            if (owner && !plugin.selections().isBypassed(p) && lastOwner(region)) {
                lang(p, "remove.last-owner");
                return;
            }
            plugin.wg().removePlayer(p.getWorld(), region, uuid, owner);
            lang(p, owner ? "remove.ok-owner" : "remove.ok-member", "target", nick, "region", region.getId());
        }
    }

    private boolean lastOwner(ProtectedRegion region) {
        try {
            return region.getOwners().getUniqueIds().size() <= 1;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- flags ----------

    private void doFlags(CommandSender sender, String[] args) {
        if (requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (!adminPerm(p, "qqregions.flags")) {
            lang(p, "general.no-permission");
            return;
        }
        if (worldDisabled(p)) {
            return;
        }
        ProtectedRegion region = resolveRegion(p, args.length > 1 ? args[1] : null);
        if (region == null) {
            lang(p, "info.none");
            return;
        }
        // Меню флагов: доступ по праву qqregions.flags.<флаг> / роли; сам
        // показ и клики фильтруются внутри меню (см. dynamic-flags).
        boolean opened = plugin.menus().openFlags(p, p.getWorld(), region);
        if (!opened) {
            lang(p, "flags.menu-disabled");
        }
    }

    // ---------- view (временный показ) ----------

    private void doView(CommandSender sender, String[] args) {
        if (requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (!adminPerm(p, "qqregions.visible")) {
            lang(p, "general.no-permission");
            return;
        }
        if (worldDisabled(p)) {
            return;
        }
        if (!plugin.config().highlight().enabled) {
            lang(p, "visible.disabled");
            return;
        }
        // /region view [регион] [particles|blocks|territory]
        String regionName = args.length >= 2 && !isViewType(args[1]) ? args[1] : null;
        String type = plugin.highlight().typeOf(p);
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (isViewType(a)) {
                type = a;
            } else if (regionName == null) {
                regionName = a;
            }
        }
        ProtectedRegion region = resolveRegion(p, regionName);
        if (region == null) {
            lang(p, "visible.none");
            return;
        }
        plugin.highlight().show(p, p.getWorld(), region, type);
        lang(p, "visible.view-shown",
                "region", region.getId(), "type", type,
                "seconds", String.valueOf(plugin.config().highlight().showSeconds));
    }

    // ---------- visible (показать/скрыть или задать флаг) ----------

    private void doVisible(CommandSender sender, String[] args) {
        if (requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (!adminPerm(p, "qqregions.visible")) {
            lang(p, "general.no-permission");
            return;
        }
        if (!plugin.config().highlight().enabled) {
            lang(p, "visible.disabled");
            return;
        }
        // /region visible true|false [территория] — только владелец территории
        // (или админ) включает/выключает флаг territory-visible — подсветку
        // границ для всех. Сменить флаг чужой территории нельзя.
        if (args.length < 2 || !isVisibleValue(args[1])) {
            lang(p, "general.usage", "usage", "visible " + plugin.lang().get("usage.visible"));
            return;
        }
        String value = args[1].toLowerCase(Locale.ROOT);
        String regionName = args.length >= 3 ? args[2] : null;
        ProtectedRegion region = resolveRegion(p, regionName);
        if (region == null) {
            lang(p, "visible.none");
            return;
        }
        if (!plugin.wg().owns(region, p) && !adminPerm(p, "qqregions.admin")) {
            lang(p, "visible.not-owner", "region", region.getId());
            return;
        }
        // true|false → allow|deny
        String state = value.equals("true") ? "allow" : value.equals("false") ? "deny" : value;
        boolean ok = plugin.wg().setFlagValue(p.getWorld(), region, plugin.wg().territoryVisibleFlag(), state);
        if (ok) {
            lang(p, "visible.flag-set", "region", region.getId(), "flag", "territory-visible", "value", state);
        } else {
            lang(p, "visible.flag-fail", "region", region.getId());
        }
    }

    // ---------- raid ----------

    private void doRaid(CommandSender sender, String[] args) {
        if (requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (!adminPerm(p, "qqregions.raid")) {
            lang(p, "general.no-permission");
            return;
        }
        if (worldDisabled(p)) {
            return;
        }
        if (!plugin.config().raid().enabled) {
            lang(p, "raid.disabled");
            return;
        }
        ProtectedRegion region = resolveRegion(p, args.length > 1 ? args[1] : null);
        if (region == null) {
            lang(p, "raid.none");
            return;
        }
        String res = plugin.raid().start(p, region);
        if (res == null) {
            lang(p, "raid.ok", "region", region.getId());
        } else {
            lang(p, "raid.fail", "region", region.getId(), "reason", res);
        }
    }

    private static final List<String> HIGHLIGHT_TYPES = List.of("particles", "blocks", "territory");

    private static boolean isViewType(String type) {
        String t = type.toLowerCase(Locale.ROOT);
        return t.equals("particles") || t.equals("blocks") || t.equals("territory");
    }

    private static boolean isVisibleValue(String s) {
        String t = s.toLowerCase(Locale.ROOT);
        return t.equals("true") || t.equals("allow") || t.equals("false") || t.equals("deny");
    }

    // ---------- рынок (sell / rent / buy / tenant / market) ----------

    /** Диспетчер рыночных подкоманд. */
    private void doMarket(CommandSender sender, String label, String[] args) {
        if (requirePlayer(sender)) {
            return;
        }
        Player p = (Player) sender;
        if (!adminPerm(p, "qqregions.market")) {
            lang(p, "general.no-permission");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("market")) {
            if (args.length >= 2) {
                String act = args[1].toLowerCase(Locale.ROOT);
                if (!plugin.market().enabled()) {
                    lang(p, plugin.config().market().enabled ? "market.economy-off" : "market.disabled");
                    return;
                }
                if (act.equals("flags")) {
                    if (plugin.shop().enabled()) {
                        if (!plugin.menus().openFlagShop(p)) {
                            lang(p, "flags.menu-disabled");
                        }
                    } else if (!plugin.menus().openMyFlags(p)) {
                        lang(p, "flags.menu-disabled");
                    }
                    return;
                }
                if (act.equals("blocks")) {
                    if (!plugin.shop().enabled()) {
                        lang(p, "shop.menu-disabled");
                        return;
                    }
                    if (!plugin.menus().openBlockShop(p)) {
                        lang(p, "shop.menu-disabled");
                    }
                    return;
                }
                if (act.equals("myflags")) {
                    plugin.menus().openMyFlags(p);
                    return;
                }
            }
            // /region market — меню рынка
            boolean opened = plugin.menus().openMarket(p);
            if (!opened) {
                lang(p, "market.menu-disabled");
            }
            return;
        }
        if (!plugin.market().enabled()) {
            lang(p, plugin.config().market().enabled ? "market.economy-off" : "market.disabled");
            return;
        }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (action.equals("accept") || action.equals("decline")
                || action.equals("cancel") || action.equals("list")) {
            String offerId = args.length > 2 ? args[2] : null;
            if (action.equals("list")) {
                for (dev.qqregions.market.Offer o : plugin.market().mine(p.getUniqueId())) {
                    String who = o.kind == dev.qqregions.market.Offer.Kind.SALE
                            ? plugin.market().nameOf(o.buyer)
                            : plugin.market().nameOf(o.tenant);
                    lang(p, "market.list-line",
                            "type", plugin.lang().get(o.kind == dev.qqregions.market.Offer.Kind.SALE
                                    ? "menu.market-type-sale" : "menu.market-type-rent"),
                            "region", o.region,
                            "world", o.world,
                            "status", o.status.name().toLowerCase(java.util.Locale.ROOT),
                            "price", plugin.market().economy().formatAmount(o.price),
                            "price-symbol", plugin.market().economy().symbol(),
                            "who", who,
                            "id", o.id.toString().substring(0, 8));
                }
                return;
            }
            dev.qqregions.market.Offer o = plugin.market().byId(offerId);
            if (o == null) {
                lang(p, "market.not-found");
                return;
            }
            String res;
            if (action.equals("accept")) {
                res = plugin.market().accept(o, p);
                lang(p, "ok".equals(res) ? "market.accepted-" + sub : marketErr(res),
                        "region", o.region);
            } else if (action.equals("decline")) {
                res = plugin.market().decline(o, p);
                lang(p, "ok".equals(res) ? "market.declined" : marketErr(res), "region", o.region);
            } else if (action.equals("cancel")) {
                res = plugin.market().cancel(o, p);
                lang(p, "ok".equals(res) ? "market.cancelled" : marketErr(res), "region", o.region);
            }
            return;
        }
        // настройки владельца (дополнение к кнопкам меню рынка)
        if (sub.equals("rent") && args.length >= 2 && args[1].equalsIgnoreCase("dur")) {
            doRentDur(p, label, args);
            return;
        }

        // ---- sell [ник] <сумма> [регион] | rent [ник] <сумма> <время> [регион] ----
        if (sub.equals("sell") || sub.equals("rent")) {
            boolean rent = sub.equals("rent");
            if (args.length < 2) {
                lang(p, "general.usage", "usage", label + " " + plugin.lang().get("usage." + sub));
                return;
            }
            String nick = null;
            String priceArg;
            String timeArg = null;
            int regionIdx;
            if (isNumber(args[1])) {
                // публичное объявление без ника: sell <сумма> [регион], rent <сумма> <время> [регион]
                priceArg = args[1];
                regionIdx = 2;
                if (rent) {
                    if (args.length < 3) {
                        lang(p, "general.usage", "usage", label + " " + plugin.lang().get("usage.rent-public"));
                        return;
                    }
                    timeArg = args[2];
                    regionIdx = 3;
                }
            } else {
                nick = args[1];
                if (args.length < (rent ? 4 : 3)) {
                    lang(p, "general.usage", "usage", label + " " + plugin.lang().get("usage." + (rent
                            ? "rent-target"
                            : "sell-target")));
                    return;
                }
                priceArg = args[2];
                if (rent) {
                    timeArg = args[3];
                    regionIdx = 4;
                } else {
                    regionIdx = 3;
                }
            }
            String regionName = args.length > regionIdx ? args[regionIdx] : null;
            ProtectedRegion region = resolveRegion(p, regionName);
            if (region == null) {
                if (!rent && regionName != null && parsePeriod(regionName) > 0) {
                    lang(p, "market.sell-no-duration",
                            "sell-usage", label + " sell <сумма> [территория]",
                            "rent-usage", label + " rent <сумма> <время> [территория]");
                    return;
                }
                lang(p, "market.no-region");
                return;
            }
            if (!plugin.wg().owns(region, p) && !adminPerm(p, "qqregions.admin")) {
                lang(p, "market.not-owner", "region", region.getId());
                return;
            }
            double price = parsePrice(priceArg);
            if (price <= 0) {
                lang(p, "market.bad-price");
                return;
            }
            String res;
            if (rent) {
                long periodMillis = parsePeriod(timeArg);
                if (periodMillis <= 0) {
                    lang(p, "market.bad-args");
                    return;
                }
                res = plugin.market().createRent(p, nick, price, periodMillis,
                        p.getWorld(), region);
                if ("ok".equals(res)) {
                    lang(p, nick == null ? "market.rent-listed" : "market.rent-offer-made",
                            "target", nick == null ? "" : nick,
                            "region", region.getId(),
                            "price", plugin.market().economy().formatAmount(price),
                            "price-symbol", plugin.market().economy().symbol());
                } else {
                    lang(p, marketErr(res), "region", region.getId());
                }
            } else {
                res = plugin.market().createSale(p, nick, price, p.getWorld(), region);
                if ("ok".equals(res)) {
                    lang(p, nick == null ? "market.sale-listed" : "market.sale-offer-made",
                            "target", nick == null ? "" : nick,
                            "region", region.getId(),
                            "price", plugin.market().economy().formatAmount(price),
                            "price-symbol", plugin.market().economy().symbol());
                } else {
                    lang(p, marketErr(res), "region", region.getId());
                }
            }
            return;
        }
        // ---- buy [регион] — мгновенная покупка публичного объявления ----
        if (sub.equals("buy")) {
            String regionName = args.length > 1 ? args[1] : null;
            ProtectedRegion region = resolveRegion(p, regionName);
            if (region == null) {
                lang(p, "market.no-region");
                return;
            }
            String res = plugin.market().buy(p, p.getWorld(), region);
            lang(p, "ok".equals(res) ? "market.buy-ok" : marketErr(res), "region", region.getId());
            return;
        }
        // ---- tenant [регион] — мгновенная аренда публичного объявления ----
        if (sub.equals("tenant")) {
            String regionName = args.length > 1 ? args[1] : null;
            ProtectedRegion region = resolveRegion(p, regionName);
            if (region == null) {
                lang(p, "market.no-region");
                return;
            }
            String res = plugin.market().tenant(p, p.getWorld(), region);
            lang(p, "ok".equals(res) ? "market.tenant-ok" : marketErr(res), "region", region.getId());
        }
    }

    /** /region rent dur <минут> [территория] — срок объявления аренды (для владельца). */
    private void doRentDur(Player p, String label, String[] args) {
        if (args.length < 3) {
            lang(p, "general.usage", "usage", label + " rent dur <минут> [территория]");
            return;
        }
        long minutes = parseLong(args[2]);
        if (minutes <= 0) {
            lang(p, "market.bad-duration");
            return;
        }
        String regionName = args.length > 3 ? args[3] : null;
        ProtectedRegion region = resolveRegion(p, regionName);
        if (region == null) {
            lang(p, "market.no-region");
            return;
        }
        dev.qqregions.market.Offer o = plugin.market().activeOn(p.getWorld(), region);
        if (o == null) {
            lang(p, "market.no-offer");
            return;
        }
        String res = plugin.market().setListDuration(o, p, minutes);
        lang(p, "ok".equals(res) ? "market.listdur-set" : marketErr(res),
                "region", region.getId(), "minutes", fmtDur(minutes));
    }

    /** Код ошибки MarketManager -> ключ перевода. */
    private static String marketErr(String res) {
        return switch (res) {
            case "already" -> "market.already-offer";
            case "no-market" -> "market.disabled";
            case "no-target" -> "market.error-no-target";
            case "bad-duration" -> "market.bad-duration";
            case "not-rent" -> "market.not-rent";
            default -> "market.error-" + res;
        };
    }

    private static boolean isNumber(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        int seps = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '.' || ch == ',') {
                if (++seps > 1) {
                    return false;
                }
            } else if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double parsePrice(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Время аренды: 1d=сутки, 1w=неделя, 1m=месяц, 1y=год, 30 = 30 минут, 2h=часы. */
    private static long parsePeriod(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        String t = s.trim().toLowerCase(Locale.ROOT);
        try {
            if (t.endsWith("d")) {
                return Long.parseLong(t.substring(0, t.length() - 1)) * 24L * 3600_000L;
            }
            if (t.endsWith("w")) {
                return Long.parseLong(t.substring(0, t.length() - 1)) * 7L * 24L * 3600_000L;
            }
            if (t.endsWith("m")) {
                return Long.parseLong(t.substring(0, t.length() - 1)) * 30L * 24L * 3600_000L;
            }
            if (t.endsWith("y")) {
                return Long.parseLong(t.substring(0, t.length() - 1)) * 365L * 24L * 3600_000L;
            }
            if (t.endsWith("h")) {
                return Long.parseLong(t.substring(0, t.length() - 1)) * 3600_000L;
            }
            return Long.parseLong(t) * 60_000L;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------- вспомогательное ----------

    private ProtectedRegion resolveRegion(Player p, String name) {
        if (name != null) {
            return plugin.wg().byName(p.getWorld(), plugin.config().normalizeName(name));
        }
        return plugin.wg().current(p);
    }

    private boolean requirePlayer(CommandSender sender) {
        boolean notPlayer = !(sender instanceof Player);
        if (notPlayer) {
            lang(sender, "general.only-player");
        }
        return notPlayer;
    }

    private boolean adminPerm(Player p, String perm) {
        return p.hasPermission("qqregions.admin") || p.hasPermission(perm);
    }

    private boolean worldDisabled(Player p) {
        if (plugin.config().isWorldDisabled(p.getWorld())
                && !p.hasPermission("qqregions.admin")
                && !p.hasPermission("qqregions.bypass.disabled-worlds")) {
            lang(p, "general.disabled-world");
            return true;
        }
        return false;
    }

    private void lang(CommandSender sender, String key, String... kv) {
        plugin.lang().send(sender, key, kv);
    }

    static String fmt(long v) {
        return String.format("%,d", v).replace(',', ' ');
    }

    /** Дружелюбное отображение количества минут. */
    private static String fmtDur(long minutes) {
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

    // ---------- tab-подсказки ----------

    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length <= 1) {
            return filtered(SUBCOMMANDS, args, args.length == 0 ? 0 : args.length - 1);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("select")) {
            return selectCommand.tab((Player) sender, alias, Arrays.copyOfRange(args, 1, args.length));
        }
        if (!(sender instanceof Player p)) {
            return List.of();
        }
        List<String> regions = plugin.wg().visibleNames(p.getWorld(), p);
        List<String> players = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            players.add(online.getName());
        }
        switch (sub) {
            case "delete":
            case "flags":
                return args.length == 2 ? filtered(plugin.wg().ownedNames(p.getWorld(), p), args, 1) : List.of();
            case "info":
            case "raid":
                return args.length == 2 ? filtered(regions, args, 1) : List.of();
            case "add":
            case "remove": {
                // add [member|owner] <ник> [регион]  |  add <ник> [регион]
                if (args.length == 2) {
                    List<String> opts = new ArrayList<>(List.of("member", "owner"));
                    opts.addAll(players);
                    return filtered(opts, args, 1);
                }
                if (args.length == 3) {
                    boolean role = args[1].equalsIgnoreCase("member") || args[1].equalsIgnoreCase("owner");
                    return filtered(role ? players : regions, args, 2);
                }
                return args.length == 4 ? filtered(regions, args, 3) : List.of();
            }
            case "view": {
                // view [регион] [particles|blocks|territory]
                if (args.length == 2) {
                    return filtered(regions, args, 1);
                }
                return args.length == 3 ? filtered(HIGHLIGHT_TYPES, args, 2) : List.of();
            }
            case "visible": {
                // visible true|allow|false|deny [регион]
                if (args.length == 2) {
                    return filtered(List.of("true", "allow", "false", "deny"), args, 1);
                }
                return args.length == 3 ? filtered(regions, args, 2) : List.of();
            }
            case "sell":
            case "rent": {
                boolean rent = sub.equals("rent");
                List<String> offers = List.of("accept", "decline", "cancel", "list");
                boolean action = args[1].equalsIgnoreCase("accept") || args[1].equalsIgnoreCase("decline")
                        || args[1].equalsIgnoreCase("cancel") || args[1].equalsIgnoreCase("list");
                boolean durAction = rent && args[1].equalsIgnoreCase("dur");
                // /region sell [ник|сумма] [сумма] [регион]
                // /region rent  [ник|сумма] [сумма] <время> [регион]
                // /region rent  dur <минут> [регион]
                if (args.length == 2) {
                    List<String> opts = new ArrayList<>(List.of("100", "250", "500", "1000", "5000"));
                    opts.addAll(players);
                    opts.addAll(offers);
                    if (rent) {
                        opts.add("dur");
                    }
                    return filtered(opts, args, 1);
                }
                if (action) {
                    return List.of();
                }
                if (durAction) {
                    if (args.length == 3) {
                        return filtered(List.of("60", "1440", "4320", "10080"), args, 2);
                    }
                    return args.length == 4 ? filtered(regions, args, 3) : List.of();
                }
                boolean nickMode = !isNumber(args[1]);
                if (args.length == 3) {
                    if (nickMode) {
                        return filtered(List.of("100", "250", "500", "1000", "5000"), args, 2);
                    }
                    return rent
                            ? filtered(List.of("1d", "7d", "30d", "90d"), args, 2)
                            : filtered(regions, args, 2);
                }
                if (args.length == 4) {
                    return nickMode
                            ? (rent ? filtered(List.of("1d", "7d", "30d", "90d"), args, 3)
                                    : filtered(regions, args, 3))
                            : filtered(regions, args, 3);
                }
                return rent && nickMode && args.length == 5 ? filtered(regions, args, 4) : List.of();
            }
            case "buy":
            case "tenant": {
                if (args.length == 2) {
                    List<String> opts = new ArrayList<>(regions);
                    opts.addAll(List.of("accept", "decline", "list"));
                    return filtered(opts, args, 1);
                }
                return List.of();
            }
            case "market":
                return args.length == 2 ? filtered(List.of("open", "list", "flags", "blocks", "myflags"), args, 1) : List.of();
            default:
                return List.of();
        }
    }

    private static List<String> filtered(List<String> in, String[] args, int index) {
        String prefix = index < args.length ? args[index].toLowerCase(Locale.ROOT) : "";
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            if (s.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(s);
            }
        }
        return out;
    }
}