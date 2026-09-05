package dev.qqregions.commands;

import com.sk89q.worldedit.math.BlockVector3;
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
            "select", "create", "delete", "info", "add", "remove", "flags", "reload", "help");

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
                if (requirePlayer(sender)) {
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
        boolean owner = plugin.wg().owns(region, p);

        p.sendMessage(plugin.lang().comp("info.title", "region", region.getId()));
        p.sendMessage(plugin.lang().comp("info.world", "world", p.getWorld().getName()));
        p.sendMessage(plugin.lang().comp("info.type", "type", regionType(region)));
        p.sendMessage(plugin.lang().comp("info.owners", "owners", plugin.wg().owners(region)));
        p.sendMessage(plugin.lang().comp("info.members", "members", plugin.wg().members(region)));
        long area = regionArea(region);
        p.sendMessage(plugin.lang().comp("info.area", "area", fmt(area), "volume", fmt(region.volume())));
        long priority = safePriority(region);
        p.sendMessage(plugin.lang().comp("info.priority", "priority", String.valueOf(priority)));

        if (owner) {
            List<String> flagLines = flagLines(region);
            p.sendMessage(plugin.lang().comp("info.flags-title"));
            if (flagLines.isEmpty()) {
                p.sendMessage(plugin.lang().comp("info.flags-none"));
            } else {
                for (String line : flagLines) {
                    p.sendMessage(dev.qqregions.util.Msg.color(line));
                }
            }
        }
    }

    private List<String> flagLines(ProtectedRegion region) {
        List<String> out = new ArrayList<>();
        for (var flag : plugin.wg().allFlags()) {
            Object v = region.getFlag(flag);
            if (v == null) {
                continue;
            }
            String value = String.valueOf(v);
            out.add(plugin.lang().fmt("info.flags-line", "flag", flag.getName(), "value", value));
        }
        return out;
    }

    private long safePriority(ProtectedRegion region) {
        try {
            return region.getPriority();
        } catch (Throwable t) {
            return 0;
        }
    }

    private String regionType(ProtectedRegion region) {
        return region.getType().getName();
    }

    private long regionArea(ProtectedRegion region) {
        try {
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            return (long) (max.x() - min.x() + 1) * (max.z() - min.z() + 1);
        } catch (Throwable t) {
            return 0;
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
        // Меню флагов открывается только владельцу (или админу).
        if (!plugin.wg().owns(region, p)) {
            lang(p, "flags.not-owner");
            return;
        }
        boolean opened = plugin.menus().openFlags(p, p.getWorld(), region);
        if (!opened) {
            lang(p, "flags.menu-disabled");
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
                    List<String> names = new ArrayList<>();
                    for (com.sk89q.worldguard.protection.regions.ProtectedRegion r : plugin.wg().all(p.getWorld())) {
                        if (!plugin.config().isBannedRegion(r.getId())) {
                            names.add(r.getId());
                        }
                    }
                    out.addAll(filtered(names, args, 1));
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