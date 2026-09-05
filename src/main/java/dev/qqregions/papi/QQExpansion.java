package dev.qqregions.papi;

import com.sk89q.worldedit.math.BlockVector3;
import dev.qqregions.QQRegions;
import dev.qqregions.config.SelectionTemplate;
import dev.qqregions.selection.Selection;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Собственное расширение PlaceholderAPI: %qqregions_*%
 */
public class QQExpansion extends PlaceholderExpansion {

    private final QQRegions plugin;

    public QQExpansion(QQRegions plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "qqregions";
    }

    @Override
    public String getAuthor() {
        return "AllF1RE";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offline, String params) {
        if (params == null) {
            return "";
        }
        switch (params) {
            case "selection_active":
                return isOnline(offline) ? yesNo(plugin.selections().has((Player) offline)) : "no";
            case "selection_blocks":
                return String.valueOf(blocks(offline));
            case "selection_max_blocks":
                return String.valueOf(maxBlocks(offline));
            case "selection_min_blocks":
                return String.valueOf(minBlocks(offline));
            case "selection_chunks":
                return String.valueOf(chunks(offline));
            case "selection_percent":
                long max = maxBlocks(offline);
                long cur = blocks(offline);
                if (max <= 0) {
                    return "100";
                }
                return String.valueOf(Math.min(100L, cur * 100 / max));
            case "selection_over_limit":
                if (!(offline instanceof Player p) || !plugin.selections().has(p)) {
                    return "no";
                }
                return yesNo(plugin.selections().overLimit(p, plugin.selections().get(p)));
            case "selection_below_min":
                if (!(offline instanceof Player p) || !plugin.selections().has(p)) {
                    return "no";
                }
                return yesNo(plugin.selections().belowMin(p, plugin.selections().get(p)));
            case "selection_conflict":
                if (!(offline instanceof Player p) || !plugin.selections().has(p)) {
                    return "no";
                }
                return yesNo(!plugin.wg().intersecting(plugin.selections().get(p)).isEmpty());
            case "region_current":
                if (!(offline instanceof Player p)) {
                    return "";
                }
                var region = plugin.wg().current(p);
                return region == null ? "" : region.getId();
            default:
                return null;
        }
    }

    private static boolean isOnline(OfflinePlayer p) {
        return p instanceof Player && p.isOnline();
    }

    private long blocks(OfflinePlayer offline) {
        if (offline instanceof Player p && plugin.selections().has(p)) {
            return plugin.selections().get(p).volume();
        }
        return 0;
    }

    private long maxBlocks(OfflinePlayer offline) {
        if (offline instanceof Player p) {
            SelectionTemplate t = plugin.selections().template(p);
            return plugin.selections().isBypassed(p) ? Long.MAX_VALUE : t.getMaxBlocks();
        }
        return 0;
    }

    private long minBlocks(OfflinePlayer offline) {
        if (offline instanceof Player p) {
            return plugin.selections().template(p).getMinBlocks();
        }
        return 0;
    }

    private long chunks(OfflinePlayer offline) {
        if (offline instanceof Player p) {
            return plugin.selections().template(p).getChunks();
        }
        return 0;
    }

    private static String yesNo(boolean v) {
        return v ? "yes" : "no";
    }
}