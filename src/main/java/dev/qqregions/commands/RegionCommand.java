package dev.qqregions.commands;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.qqregions.QQRegions;
import dev.qqregions.config.Lang;
import dev.qqregions.config.SelectionTemplate;
import dev.qqregions.selection.Selection;
import dev.qqregions.wg.RegionException;
import net.kyori.adventure.text.Component;
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
            "visible", "view", "sell", "rent", "buy", "tenant", "market");

    private final QQRegions plugin;
    private final SelectCommand selectCommand;

    public RegionCommand(QQRegions plugin) {
        this.plugin = plugin;
        this.selectCommand = new SelectCommand(plugin);
    }

    public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equalsIgnoreCase("?")) {
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
            case "visible":
            case "view":
                doVisible(sender, args);
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
        sender.sendMessage(l.comp("help.header", "version", plugin.getDescription().getVersion()));
        for (Object o : l.getList("help.commands")) {
            if (o instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) o;
                sender.sendMessage(l.comp("help.line",
                        "cmd", "/" + label + " " + m.get("usage"),
                        "desc", String.valueOf(m.get("desc"))));
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
            lang(p, "general.usage", "usage", label + " create <название>");
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
            lang(p, "select.over-limit", "current", fmt(sel.volume()), "max", fmt(t.getMaxBlocks()));
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
        String norm = plugin.config().normalizeName(name);
        try {
            plugin.wg().create(sel, norm, p);
            lang(p, "create.ok", "region", norm, "world", sel.getWorld().getName(), "blocks", fmt(sel.volume()));
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
        if (!plugin.wg().owns(region, p)) {
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
            lang(p, "general.usage", "usage", plugin.config().commandName() + " " + (add ? "add" : "remove") + " [member|owner] <ник> [регион]");
            return;
        }

        ProtectedRegion region = resolveRegion(p, regionName);
        if (region == null) {
            lang(p, "info.none");
            return;
        }
        if (!plugin.wg().owns(region, p)) {
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

    // ---------- visible / view ----------

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
        // /region visible type <particles|blocks|territory> — тип подсветки по умолчанию
        if (args.length >= 2 && args[1].equalsIgnoreCase("type")) {
            if (args.length < 3) {
                // Без аргумента — показать текущий тип и доступные.
                lang(p, "visible.type-current", "type", plugin.highlight().typeOf(p),
                        "types", String.join(", ", HIGHLIGHT_TYPES));
                return;
            }
            String type = args[2].toLowerCase(Locale.ROOT);
            if (!isViewType(type)) {
                lang(p, "visible.type-invalid", "type", args[2]);
                return;
            }
            plugin.highlight().setDefaultType(p, type);
            lang(p, "visible.type-set", "type", type);
            return;
        }
        // /region visible off — скрыть все подсветки
        if (args.length >= 2 && args[1].equalsIgnoreCase("off")) {
            plugin.highlight().hideAll(p);
            lang(p, "visible.hidden-all");
            return;
        }
        ProtectedRegion region = resolveRegion(p, args.length >= 2 ? args[1] : null);
        if (region == null) {
            lang(p, "visible.none");
            return;
        }
        String type = plugin.highlight().typeOf(p);
        if (args.length >= 3) {
            String t = args[2].toLowerCase(Locale.ROOT);
            if (!isViewType(t)) {
                lang(p, "visible.type-invalid", "type", args[2]);
                return;
            }
            type = t;
        }
        boolean shown = plugin.highlight().toggle(p, p.getWorld(), region, type);
        if (shown) {
            lang(p, "visible.shown",
                    "region", region.getId(),
                    "type", type,
                    "seconds", String.valueOf(plugin.config().highlight().showSeconds));
        } else {
            lang(p, "visible.hidden", "region", region.getId());
        }
    }

    private static final List<String> HIGHLIGHT_TYPES = List.of("particles", "blocks", "territory");

private static boolean isViewType(String type) {
        String t = type.toLowerCase(Locale.ROOT);
        return t.equals("particles") || t.equals("blocks") || t.equals("territory");
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
                            "type", o.kind == dev.qqregions.market.Offer.Kind.SALE ? "продажа" : "аренда",
                            "region", o.region,
                            "world", o.world,
                            "status", o.status.name().toLowerCase(java.util.Locale.ROOT),
                            "price", plugin.market().economy().format(o.price),
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
                if ("ok".equals(res)) {
                    lang(p, "market.accepted-" + sub, "region", o.region);
                } else {
                    lang(p, "market.error-" + res, "region", o.region);
                }
            } else if (action.equals("decline")) {
                res = plugin.market().decline(o, p);
                lang(p, "ok".equals(res) ? "market.declined" : "market.error-" + res, "region", o.region);
            } else if (action.equals("cancel")) {
                res = plugin.market().cancel(o, p);
                lang(p, "ok".equals(res) ? "market.cancelled" : "market.error-" + res, "region", o.region);
            }
            return;
        }

        // найдём регион (по аргументу или текущий под игроком)
        String regionName = null;
        if (sub.equals("sell") || sub.equals("rent")) {
            if (args.length < 3) {
                lang(p, "general.usage",
                        "usage", label + " " + sub + " <ник> <сумма>"
                                + (sub.equals("rent") ? " <время>" : "") + " [регион]");
                return;
            }
            regionName = args.length > (sub.equals("rent") ? 4 : 3)
                    ? args[sub.equals("rent") ? 4 : 3] : null;
        } else {
            regionName = args.length > 1 ? args[1] : null;
        }
        ProtectedRegion region = resolveRegion(p, regionName);
        if (region == null) {
            lang(p, "market.no-region");
            return;
        }
        if (!sub.equals("buy") && !sub.equals("tenant") && !plugin.wg().owns(region, p)
                && !adminPerm(p, "qqregions.admin")) {
            lang(p, "market.not-owner", "region", region.getId());
            return;
        }
        double price;
        long periodMillis;
        switch (sub) {
            case "sell": {
                price = parsePrice(args[2]);
                if (price <= 0) {
                    lang(p, "market.bad-price");
                    return;
                }
                boolean ok = plugin.market().createSale(p, args[1], price, p.getWorld(), region, false);
                if (ok) {
                    lang(p, "market.sale-offer-made", "target", args[1],
                            "region", region.getId(), "price", plugin.market().economy().format(price));
                } else {
                    lang(p, "market.already-offer", "region", region.getId());
                }
                break;
            }
            case "rent": {
                if (args.length < 4) {
                    lang(p, "general.usage", "usage", label + " rent <ник> <сумма> <время> [регион]");
                    return;
                }
                price = parsePrice(args[2]);
                periodMillis = parsePeriod(args[3]);
                if (price <= 0 || periodMillis <= 0) {
                    lang(p, "market.bad-args");
                    return;
                }
                boolean ok = plugin.market().createRent(p, args[1], price, periodMillis,
                        p.getWorld(), region, false);
                if (ok) {
                    lang(p, "market.rent-offer-made", "target", args[1],
                            "region", region.getId(),
                            "price", plugin.market().economy().format(price));
                } else {
                    lang(p, "market.already-offer", "region", region.getId());
                }
                break;
            }
            case "buy": {
                boolean ok = plugin.market().createSale(p, firstOwnerName(region), 0,
                        p.getWorld(), region, true);
                if (ok) {
                    lang(p, "market.buy-request-made", "region", region.getId());
                } else {
                    lang(p, "market.already-offer", "region", region.getId());
                }
                break;
            }
            case "tenant": {
                long dur = 1000L * 60L * 60L * 24L * 7L; // 1 неделя по умолчанию
                boolean ok = plugin.market().createRent(p, firstOwnerName(region),
                        0, dur, p.getWorld(), region, true);
                if (ok) {
                    lang(p, "market.tenant-request-made", "region", region.getId());
                } else {
                    lang(p, "market.already-offer", "region", region.getId());
                }
                break;
            }
            default:
                break;
        }
    }

    private static String firstOwnerName(ProtectedRegion region) {
        for (java.util.UUID u : region.getOwners().getUniqueIds()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            return op.getName() != null ? op.getName() : u.toString();
        }
        return "";
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
        Component c = plugin.lang().compPrefixed(key, kv);
        if (sender instanceof Player) {
            sender.sendMessage(c);
        } else {
            sender.sendMessage(c);
        }
    }

    static String fmt(long v) {
        return String.format("%,d", v).replace(',', ' ');
    }

    // ---------- tab-подсказки ----------

    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        List<String> out = new ArrayList<>(16);
        if (!(sender instanceof Player p)) {
            out.addAll(filtered(SUBCOMMANDS, args, args.length - 1));
            return out;
        }
        if (args.length <= 1) {
            out.addAll(filtered(SUBCOMMANDS, args, args.length == 0 ? 0 : args.length - 1));
            return out;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("select")) {
            return selectCommand.tab(p, alias, Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length == 2) {
            switch (sub) {
                case "delete":
                case "flags":
                    out.addAll(filtered(plugin.wg().ownedNames(p.getWorld(), p), args, 1));
                    return out;
                case "info":
                    out.addAll(filtered(plugin.wg().visibleNames(p.getWorld(), p), args, 1));
                    return out;
                case "add":
                case "remove":
                    out.addAll(filtered(List.of("member", "owner"), args, 1));
                    return out;
                default:
                    return out;
            }
        }
        if (args.length == 3 && (sub.equals("add") || sub.equals("remove"))) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                out.add(online.getName());
            }
        }
        if (sub.equals("visible") || sub.equals("view")) {
            // /region visible [название|off|type] [particles|blocks|territory]
            if (args.length == 2) {
                List<String> opts = new ArrayList<>(List.of("off", "type"));
                opts.addAll(plugin.wg().visibleNames(p.getWorld(), p));
                out.addAll(filtered(opts, args, 1));
                return out;
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("type")) {
                out.addAll(filtered(HIGHLIGHT_TYPES, args, 2));
                return out;
            }
            return filtered(out, args, args.length - 1);
        }
        if (sub.equals("sell") || sub.equals("rent") || sub.equals("buy")
                || sub.equals("tenant") || sub.equals("market")) {
            // /region sell|rent|buy|tenant [акция] [регион]
            List<String> opts;
            if (sub.equals("sell") || sub.equals("rent")) {
                opts = new ArrayList<>(List.of("accept", "decline", "cancel", "list"));
            } else if (sub.equals("buy") || sub.equals("tenant")) {
                opts = new ArrayList<>(List.of("accept", "decline", "list"));
            } else {
                opts = new ArrayList<>(List.of("open", "list"));
            }
            if (args.length == 2) {
                out.addAll(filtered(opts, args, 1));
                return out;
            }
            if (args.length == 3) {
                out.addAll(filtered(plugin.wg().visibleNames(p.getWorld(), p), args, 2));
                return out;
            }
            return filtered(out, args, args.length - 1);
        }
        return filtered(out, args, args.length - 1);
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