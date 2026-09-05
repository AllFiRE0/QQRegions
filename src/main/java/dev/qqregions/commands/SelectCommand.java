package dev.qqregions.commands;

import com.sk89q.worldedit.math.BlockVector3;
import dev.qqregions.QQRegions;
import dev.qqregions.config.SelectionTemplate;
import dev.qqregions.selection.ExpandDirection;
import dev.qqregions.selection.Selection;
import dev.qqregions.selection.SelectionManager;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Подкоманда /region select ... (pos, point, max, chunk, expand, outset)
 * и запуск интерактивного режима (select без аргументов).
 */
public class SelectCommand {

    private static final List<String> SUBS = List.of("pos", "point", "max", "chunk", "expand", "outset");
    private static final List<String> SIDES = List.of("north", "south", "east", "west", "up", "down");
    private static final List<String> AXES = List.of("h", "horizontal", "v", "vertical");

    private final QQRegions plugin;

    public SelectCommand(QQRegions plugin) {
        this.plugin = plugin;
    }

    public void run(Player p, String label, String[] args) {
        if (!p.hasPermission("qqregions.admin") && !p.hasPermission("qqregions.select")) {
            send(p, "general.no-permission");
            return;
        }
        if (worldDisabled(p)) {
            return;
        }
        if (args.length == 0) {
            startInteractive(p);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "pos":
                pos(p, args);
                break;
            case "point":
                point(p, args);
                break;
            case "max":
                max(p);
                break;
            case "chunk":
                chunk(p, args);
                break;
            case "expand":
                expand(p, args);
                break;
            case "outset":
                outset(p, args);
                break;
            default:
                send(p, "general.unknown-subcommand", "alias", label);
                break;
        }
    }

    // ---------- pos 1 / pos 2 ----------

    private void pos(Player p, String[] args) {
        if (args.length < 2 || (!args[1].equals("1") && !args[1].equals("2"))) {
            send(p, "general.usage", "usage", "select pos <1|2>");
            return;
        }
        int which = Integer.parseInt(args[1]);
        SelectionManager mgr = plugin.selections();
        Selection sel = mgr.getOrCreate(p, p.getWorld());
        BlockVector3 pos = BlockVector3.at(p.getLocation().getBlockX(),
                p.getLocation().getBlockY(), p.getLocation().getBlockZ());
        sel.setPos(which, pos);
        mgr.set(p, sel);
        send(p, "select.pos-set",
                "point", plugin.lang().fmt("select.point-" + which),
                "x", String.valueOf(pos.getBlockX()),
                "y", String.valueOf(pos.getBlockY()),
                "z", String.valueOf(pos.getBlockZ()));
    }

    // ---------- point 1 / point 2 (как //hpos в WorldEdit) ----------

    private void point(Player p, String[] args) {
        if (args.length < 2 || (!args[1].equals("1") && !args[1].equals("2"))) {
            send(p, "general.usage", "usage", "select point <1|2>");
            return;
        }
        int which = Integer.parseInt(args[1]);
        Block target = p.getTargetBlockExact(300);
        BlockVector3 pos = target != null
                ? BlockVector3.at(target.getX(), target.getY(), target.getZ())
                : BlockVector3.at(p.getLocation().getBlockX(),
                        p.getLocation().getBlockY(), p.getLocation().getBlockZ());
        SelectionManager mgr = plugin.selections();
        Selection sel = mgr.getOrCreate(p, p.getWorld());
        sel.setPos(which, pos);
        mgr.set(p, sel);
        send(p, "select.pos-set",
                "point", plugin.lang().fmt("select.point-" + which),
                "x", String.valueOf(pos.getBlockX()),
                "y", String.valueOf(pos.getBlockY()),
                "z", String.valueOf(pos.getBlockZ()));
    }

    // ---------- max ----------

    private void max(Player p) {
        SelectionManager mgr = plugin.selections();
        SelectionTemplate t = mgr.template(p);
        long max = t.getMaxBlocks();
        if (mgr.isBypassed(p)) {
            max = 100_000_000L;
        }
        long L = (long) Math.cbrt((double) max);
        while (L > 1 && L * L * L > max) {
            L--;
        }
        int side = (int) Math.max(1, L);
        int half = side / 2;
        int cx = p.getLocation().getBlockX();
        int cy = p.getLocation().getBlockY();
        int cz = p.getLocation().getBlockZ();
        Selection sel = new Selection(p.getWorld(),
                BlockVector3.at(cx - half, cy - half, cz - half),
                BlockVector3.at(cx - half + side - 1, cy - half + side - 1, cz - half + side - 1));
        sel = mgr.clampToWorld(sel);
        if (mgr.overLimit(p, sel)) {
            send(p, "select.over-limit", "current", RegionCommand.fmt(sel.volume()), "max", RegionCommand.fmt(max));
            return;
        }
        mgr.set(p, sel);
        warnMin(p, sel);
        send(p, "select.max-built", "blocks", RegionCommand.fmt(sel.volume()));
    }

    // ---------- chunk [количество] ----------

    private void chunk(Player p, String[] args) {
        SelectionManager mgr = plugin.selections();
        SelectionTemplate t = mgr.template(p);
        int allowed = t.getChunks();
        int side;
        if (args.length >= 2) {
            int given;
            try {
                given = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                send(p, "select.invalid-amount", "amount", args[1]);
                return;
            }
            if (given <= 0) {
                send(p, "select.invalid-amount", "amount", args[1]);
                return;
            }
            if (given > allowed && !mgr.isBypassed(p)) {
                send(p, "select.chunk-over", "chunks", String.valueOf(allowed));
                return;
            }
            side = given;
        } else {
            side = (int) Math.sqrt(allowed);
            if (side < 1) {
                side = 1;
            }
        }
        long maxBlocks = t.getMaxBlocks();
        if (mgr.isBypassed(p)) {
            maxBlocks = Long.MAX_VALUE;
        }
        int cx = p.getLocation().getBlockX() >> 4;
        int cz = p.getLocation().getBlockZ() >> 4;
        int wide = side * 16;
        int minX = (cx - side / 2) * 16;
        int minZ = (cz - side / 2) * 16;

        int area = wide * wide;
        int band = maxBlocks == Long.MAX_VALUE ? 384 : (int) Math.max(1, Math.min(384, maxBlocks / area));
        int cy = p.getLocation().getBlockY();
        int minY = cy - band / 2;

        Location loc = p.getLocation();
        Selection sel = new Selection(p.getWorld(),
                BlockVector3.at(minX, minY, minZ),
                BlockVector3.at(minX + wide - 1, minY + band - 1, minZ + wide - 1));
        sel = mgr.clampToWorld(sel);
        if (mgr.overLimit(p, sel)) {
            // даже при band=1 не влезает — упираемся в max-blocks
            send(p, "select.over-limit", "current", RegionCommand.fmt(sel.volume()),
                    "max", RegionCommand.fmt(t.getMaxBlocks()));
            return;
        }
        mgr.set(p, sel);
        warnMin(p, sel);
        send(p, "select.chunk-built",
                "chunks", String.valueOf(side * side),
                "blocks", RegionCommand.fmt(sel.volume()));
    }

    // ---------- expand ----------

    private void expand(Player p, String[] args) {
        ExpandDirection direction;
        String amountStr;
        if (args.length >= 3) {
            direction = ExpandDirection.fromString(args[1]);
            if (direction == null) {
                send(p, "select.invalid-side", "side", args[1]);
                return;
            }
            amountStr = args[2];
        } else if (args.length >= 2) {
            amountStr = args[1];
            direction = facing(p.getLocation().getYaw());
        } else {
            send(p, "general.usage", "usage", "select expand [сторона] <количество>");
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(amountStr);
        } catch (NumberFormatException e) {
            send(p, "select.invalid-amount", "amount", amountStr);
            return;
        }
        apply(p, sel -> sel.withExpanded(direction, amount), "select.expanded");
    }

    // ---------- outset ----------

    private void outset(Player p, String[] args) {
        if (args.length < 2) {
            send(p, "general.usage", "usage", "select outset <количество> [h|v|horizontal|vertical]");
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            send(p, "select.invalid-amount", "amount", args[1]);
            return;
        }
        boolean horizontal = true;
        boolean vertical = true;
        if (args.length >= 3) {
            String axis = args[2].toLowerCase(Locale.ROOT);
            switch (axis) {
                case "h":
                case "horizontal":
                    vertical = false;
                    break;
                case "v":
                case "vertical":
                    horizontal = false;
                    break;
                default:
                    send(p, "select.invalid-side", "side", args[2]);
                    return;
            }
        }
        final boolean fh = horizontal;
        final boolean fv = vertical;
        apply(p, sel -> sel.withOutset(amount, fh, fv), "select.outset");
    }

    // ---------- применение изменений с лимитами ----------

    private interface Op {
        Selection apply(Selection sel);
    }

    private void apply(Player p, Op op, String okKey) {
        SelectionManager mgr = plugin.selections();
        Selection sel = mgr.get(p);
        if (sel == null) {
            send(p, "select.none");
            return;
        }
        Selection next = mgr.clampToWorld(op.apply(sel));
        if (mgr.overLimit(p, next)) {
            SelectionTemplate t = mgr.template(p);
            send(p, "select.over-limit", "current", RegionCommand.fmt(next.volume()),
                    "max", RegionCommand.fmt(t.getMaxBlocks()));
            return;
        }
        mgr.set(p, next);
        warnMin(p, next);
        send(p, okKey, "blocks", RegionCommand.fmt(next.volume()));
    }

    private void warnMin(Player p, Selection sel) {
        SelectionManager mgr = plugin.selections();
        if (mgr.belowMin(p, sel)) {
            SelectionTemplate t = mgr.template(p);
            send(p, "select.below-min", "current", RegionCommand.fmt(sel.volume()),
                    "min", RegionCommand.fmt(t.getMinBlocks()));
        }
    }

    private void startInteractive(Player p) {
        SelectionManager mgr = plugin.selections();
        if (!mgr.startSession(p)) {
            send(p, "select.interactive-on");
            return;
        }
    }

    // ---------- таб-подсказки ----------

    public List<String> tab(Player p, String alias, String[] args) {
        if (args.length <= 1) {
            List<String> out = new ArrayList<>(SUBS);
            List<String> prefixed = new ArrayList<>();
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            for (String s : out) {
                if (s.startsWith(prefix)) {
                    prefixed.add(s);
                }
            }
            return prefixed;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("pos") || sub.equals("point")) {
            return args.length == 2 ? List.of("1", "2") : List.of();
        }
        if (sub.equals("expand")) {
            if (args.length == 2) {
                List<String> out = new ArrayList<>(SIDES);
                out.addAll(List.of("1", "5", "10", "25", "50", "100"));
                return startsWith(out, args[1]);
            }
            if (args.length == 3) {
                return List.of("1", "5", "10", "25", "50", "100", "-1", "-5");
            }
        }
        if (sub.equals("outset")) {
            if (args.length == 2) {
                return List.of("1", "5", "10", "25", "50", "100", "-1", "-5");
            }
            if (args.length == 3) {
                return startsWith(AXES, args[2]);
            }
        }
        if (sub.equals("chunk")) {
            if (args.length == 2) {
                SelectionTemplate t = plugin.selections().template(p);
                return List.of(String.valueOf(t.getChunks()), "1", "2", "3");
            }
        }
        return List.of();
    }

    private static List<String> startsWith(List<String> in, String prefix) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (s.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(s);
            }
        }
        return out;
    }

    /** Направление по взгляду игрока (для expand без стороны). */
    private static ExpandDirection facing(float yaw) {
        float d = yaw % 360f;
        if (d < 0) {
            d += 360f;
        }
        if (d >= 315 || d < 45) {
            return ExpandDirection.SOUTH;
        }
        if (d < 135) {
            return ExpandDirection.WEST;
        }
        if (d < 225) {
            return ExpandDirection.NORTH;
        }
        return ExpandDirection.EAST;
    }

    private boolean worldDisabled(Player p) {
        if (plugin.config().isWorldDisabled(p.getWorld())
                && !p.hasPermission("qqregions.admin")
                && !p.hasPermission("qqregions.bypass.disabled-worlds")) {
            send(p, "general.disabled-world");
            return true;
        }
        return false;
    }

    private void send(Player p, String key, String... kv) {
        p.sendMessage(plugin.lang().compPrefixed(key, kv));
    }
}
