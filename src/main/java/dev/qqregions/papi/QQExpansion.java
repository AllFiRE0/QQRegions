package dev.qqregions.papi;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.qqregions.QQRegions;
import dev.qqregions.config.SelectionTemplate;
import dev.qqregions.selection.Selection;
import dev.qqregions.selection.SelectStatus;
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

    /** Список заполнителей для подсказок PlaceholderAPI
     *  (/papi parse <игрок> %qqregions_ ...). Без него PAPI предлагает
     *  только %qqregions_% и ничего после «_». */
    @Override
    public java.util.List<String> getPlaceholders() {
        return java.util.List.of(
                "%qqregions_selection_active%",
                "%qqregions_selection_blocks%",
                "%qqregions_selection_max_blocks%",
                "%qqregions_selection_min_blocks%",
                "%qqregions_selection_chunks%",
                "%qqregions_selection_percent%",
                "%qqregions_selection_over_limit%",
                "%qqregions_selection_below_min%",
                "%qqregions_selection_conflict%",
                "%qqregions_selection_height_top%",
                "%qqregions_selection_height_bottom%",
                "%qqregions_selection_conflict_regions%",
                "%qqregions_selection_conflict_count%",
                "%qqregions_region_current%",
                "%qqregions_eco_balance%",
                "%qqregions_eco_balance_symbol%",
                "%qqregions_eco_balance_raw%",
                "%qqregions_eco_has_<сумма>%",
                "%qqregions_market_listings%",
                "%qqregions_raid_active%",
                "%qqregions_raid_state%",
                "%qqregions_raid_region%",
                "%qqregions_raid_world%",
                "%qqregions_raid_clan%",
                "%qqregions_raid_thief%",
                "%qqregions_raid_players%",
                "%qqregions_raid_remaining%",
                "%qqregions_raid_time%",
                "%qqregions_raid_cooldown%",
                "%qqregions_region_price_<мир>:<регион>%",
                "%qqregions_region_for_sale_<мир>:<регион>%",
                "%qqregions_region_for_rent_<мир>:<регион>%",
                "%qqregions_region_owner_<мир>:<регион>%",
                "%qqregions_region_rent_time_<мир>:<регион>%"
        );
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
            case "selection_height_top":
                return heightTop(offline);
            case "selection_height_bottom":
                return heightBottom(offline);
            case "selection_conflict_regions":
                return conflictRegions(offline);
            case "selection_conflict_count":
                return String.valueOf(foreignCount(offline));
            case "region_current":
                if (!(offline instanceof Player p)) {
                    return "";
                }
                var region = plugin.wg().current(p);
                return region == null ? "" : region.getId();
            case "eco_balance":
                return economy() ? plugin.market().economy().formatAmount(plugin.market().economy().balance(offline.getUniqueId())) : "";
            case "eco_balance_symbol":
                return economy() ? plugin.market().economy().symbol() : "";
            case "eco_balance_raw":
                return economy() ? String.valueOf(plugin.market().economy().balance(offline.getUniqueId())) : "";
            case "market_listings":
                return String.valueOf(plugin.market().offers().stream()
                        .filter(o -> o.status == dev.qqregions.market.Offer.Status.PENDING
                                || o.status == dev.qqregions.market.Offer.Status.ACTIVE)
                        .count());
            case "raid_active":
                return yesNo(plugin.raid().active());
            case "raid_state":
                return plugin.raid().stateName();
            case "raid_region":
                return plugin.raid().regionName();
            case "raid_world":
                return plugin.raid().worldName();
            case "raid_clan":
                return plugin.raid().clanName();
            case "raid_thief":
                return plugin.raid().thiefName();
            case "raid_players":
            case "raid_count":
                return String.valueOf(plugin.raid().attackerCount());
            case "raid_total":
                return String.valueOf(plugin.raid().attackerCount());
            case "raid_remaining":
            case "raid_time":
                int rem = plugin.raid().remainingSeconds();
                return rem < 0 ? "0" : String.valueOf(rem);
            case "raid_cooldown":
                return String.valueOf(plugin.raid().cooldownSeconds());
            default:
                break;
        }
        if (params.startsWith("raid_")) {
            String rest = params.substring("raid_".length());
            return raidParam(offline, rest);
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
        if (params.startsWith("region_rent_time_")) {
            dev.qqregions.market.Offer o = activeOn(params.substring("region_rent_time_".length()), false);
            if (o == null) {
                return "";
            }
            String t = o.periodMillis <= 0 ? plugin.lang().get("menu.time-empty")
                    : new dev.qqregions.util.TimeFmt(plugin).format(o.periodMillis);
            return org.bukkit.ChatColor.stripColor(org.bukkit.ChatColor.translateAlternateColorCodes('&', t));
        }
        return null;
    }

    private boolean economy() {
        return plugin.market().enabled();
    }

    /** Параметризованные raid-заполнители: world:region -> состояние рейда. */
    private String raidParam(OfflinePlayer offline, String rest) {
        // пока не различаем мир/регион — отдаём глобальное состояние
        if (!plugin.raid().active()) {
            return rest.equalsIgnoreCase("active") ? "no" : "";
        }
        switch (rest.toLowerCase(java.util.Locale.ROOT)) {
            case "active":
                return "yes";
            case "region":
                return plugin.raid().regionName();
            case "clan":
                return plugin.raid().clanName();
            case "thief":
                return plugin.raid().thiefName();
            case "time":
            case "remaining":
                int rem = plugin.raid().remainingSeconds();
                return rem < 0 ? "0" : String.valueOf(rem);
            case "count":
            case "players":
            case "total":
                return String.valueOf(plugin.raid().attackerCount());
            default:
                return "";
        }
    }

    private String priceOf(String key) {
        dev.qqregions.market.Offer o = activeOn(key, null);
        if (o == null) {
            return "0";
        }
        return plugin.market().economy().formatAmount(o.price);
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

    /** Блоков от игрока до верхней границы выделения (0 если нет выделения). */
    private String heightTop(OfflinePlayer offline) {
        if (!(offline instanceof Player p) || !plugin.selections().has(p)) {
            return "0";
        }
        return String.valueOf(SelectStatus.heightTop(plugin.selections().get(p), p));
    }

    /** Блоков от игрока до нижней границы выделения (0 если нет выделения). */
    private String heightBottom(OfflinePlayer offline) {
        if (!(offline instanceof Player p) || !plugin.selections().has(p)) {
            return "0";
        }
        return String.valueOf(SelectStatus.heightBottom(plugin.selections().get(p), p));
    }

    /** Чужие регионы, пересекающие выделение, через запятую ("" если нет). */
    private String conflictRegions(OfflinePlayer offline) {
        if (!(offline instanceof Player p) || !plugin.selections().has(p)) {
            return "";
        }
        java.util.List<ProtectedRegion> foreign =
                SelectStatus.foreignIntersecting(plugin, plugin.selections().get(p), p);
        if (foreign.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ProtectedRegion r : foreign) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(r.getId());
        }
        return sb.toString();
    }

    private int foreignCount(OfflinePlayer offline) {
        if (!(offline instanceof Player p) || !plugin.selections().has(p)) {
            return 0;
        }
        return SelectStatus.foreignIntersecting(plugin, plugin.selections().get(p), p).size();
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