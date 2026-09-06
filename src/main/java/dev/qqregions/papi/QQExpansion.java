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
            case "eco_balance":
                return economy() ? plugin.market().economy().format(plugin.market().economy().balance(offline.getUniqueId())) : "";
            case "eco_balance_raw":
                return economy() ? String.valueOf(plugin.market().economy().balance(offline.getUniqueId())) : "";
            case "market_listings":
                return String.valueOf(plugin.market().offers().stream()
                        .filter(o -> o.status == dev.qqregions.market.Offer.Status.PENDING
                                || o.status == dev.qqregions.market.Offer.Status.ACTIVE)
                        .count());
            default:
                break;
        }
        if (params.startsWith("eco_has_")) {
            try {
                double amt = Double.parseDouble(params.substring("eco_has_".length()));
                return economy() ? yesNo(plugin.market().economy().has(offline.getUniqueId(), amt)) : "no";
            } catch (NumberFormatException e) {
                return "no";
            }
        }
        if (params.startsWith("region_price_")) {
            return priceOf(params.substring("region_price_".length()));
        }
        if (params.startsWith("region_for_sale_")) {
            return yesNo(null != activeOn(params.substring("region_for_sale_".length()), true));
        }
        if (params.startsWith("region_for_rent_")) {
            return yesNo(null != activeOn(params.substring("region_for_rent_".length()), false));
        }
        if (params.startsWith("region_owner_")) {
            return ownerOf(params.substring("region_owner_".length()));
        }
        return null;
    }

    private boolean economy() {
        return plugin.market().enabled();
    }

    private String priceOf(String key) {
        dev.qqregions.market.Offer o = activeOn(key, null);
        if (o == null) {
            return "0";
        }
        return plugin.market().economy().format(o.price);
    }

    private String ownerOf(String key) {
        dev.qqregions.market.Offer o = activeOn(key, null);
        if (o != null) {
            return plugin.market().nameOf(o.owner != null ? o.owner : o.seller);
        }
        String[] parts = key.split(":", 2);
        if (parts.length < 2) {
            return "";
        }
        org.bukkit.World w = org.bukkit.Bukkit.getWorld(parts[0]);
        com.sk89q.worldguard.protection.regions.ProtectedRegion r = w == null ? null : plugin.wg().byName(w, parts[1]);
        return w == null || r == null ? "" : plugin.wg().owners(r);
    }

    /** "мир:регион" -> активный оффер (или по типу kind). */
    private dev.qqregions.market.Offer activeOn(String key, Boolean saleWant) {
        String[] parts = key.split(":", 2);
        if (parts.length < 2) {
            return null;
        }
        org.bukkit.World w = org.bukkit.Bukkit.getWorld(parts[0]);
        com.sk89q.worldguard.protection.regions.ProtectedRegion r = w == null ? null : plugin.wg().byName(w, parts[1]);
        if (w == null || r == null) {
            return null;
        }
        dev.qqregions.market.Offer o = plugin.market().activeOn(w, r);
        if (o == null || (saleWant != null && (o.kind == dev.qqregions.market.Offer.Kind.SALE) != saleWant)) {
            return null;
        }
        return o;
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