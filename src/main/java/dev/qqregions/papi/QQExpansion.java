package dev.qqregions.papi;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.qqregions.QQRegions;
import dev.qqregions.config.SelectionTemplate;
import dev.qqregions.selection.Selection;
import dev.qqregions.selection.SelectStatus;
import dev.qqregions.wg.Wg;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

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
                "%qqregions_region_price_<мир>:<территория>%",
                "%qqregions_region_for_sale_<мир>:<территория>%",
                "%qqregions_region_for_rent_<мир>:<территория>%",
                "%qqregions_region_owner_<мир>:<территория>%",
                "%qqregions_region_rent_time_<мир>:<территория>%",
                "%qqregions_selection_pos1_x%",
                "%qqregions_selection_pos1_y%",
                "%qqregions_selection_pos1_z%",
                "%qqregions_selection_pos2_x%",
                "%qqregions_selection_pos2_y%",
                "%qqregions_selection_pos2_z%",
                "%qqregions_region_flags%",
                "%qqregions_region_flag_<флаг>%",
                "%qqregions_region_owners%",
                "%qqregions_region_members%",
                "%qqregions_region_owner_is%",
                "%qqregions_region_member_is%",
                "%qqregions_region_role%",
                "%qqregions_player_owned_regions%",
                "%qqregions_player_owned_count%",
                "%qqregions_player_membered_regions%",
                "%qqregions_player_membered_count%",
                "%qqregions_nearby_region%",
                "%qqregions_nearby_region_distance%",
                "%qqregions_nearby_region_count%",
                "%qqregions_nearby_region_count_<радиус>%"
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
            case "selection_pos1_x":
                return selCoord(offline, 1, 0);
            case "selection_pos1_y":
                return selCoord(offline, 1, 1);
            case "selection_pos1_z":
                return selCoord(offline, 1, 2);
            case "selection_pos2_x":
                return selCoord(offline, 2, 0);
            case "selection_pos2_y":
                return selCoord(offline, 2, 1);
            case "selection_pos2_z":
                return selCoord(offline, 2, 2);
            case "region_current":
                if (!(offline instanceof Player p)) {
                    return "";
                }
                var region = plugin.wg().current(p);
                return region == null ? "" : region.getId();
            case "region_flags":
                return regionFlags(offline);
            case "region_owners":
                return currentOwnerNames(offline);
            case "region_members":
                return currentMemberNames(offline);
            case "region_owner_is":
                return currentRoleIs(offline, true);
            case "region_member_is":
                return currentRoleIs(offline, false);
            case "region_role":
                return currentRole(offline);
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
            case "player_owned_regions":
                return String.join(plugin.config().ownedSeparator(), ownedOrMembered(offline, true));
            case "player_owned_count":
                return String.valueOf(ownedOrMembered(offline, true).size());
            case "player_membered_regions":
                return String.join(plugin.config().memberedSeparator(), ownedOrMembered(offline, false));
            case "player_membered_count":
                return String.valueOf(ownedOrMembered(offline, false).size());
            case "nearby_region":
                return nearestRegion(offline);
            case "nearby_region_distance":
                return nearestDistance(offline);
            case "nearby_region_count":
                return String.valueOf(countNearby(offline, 100));
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
        if (params.startsWith("nearby_region_count_")) {
            try {
                int rad = Integer.parseInt(params.substring("nearby_region_count_".length()));
                return String.valueOf(countNearby(offline, Math.max(0, rad)));
            } catch (NumberFormatException e) {
                return "0";
            }
        }
        if (params.startsWith("region_flag_")) {
            return regionFlagCur(offline, params.substring("region_flag_".length()));
        }
        return null;
    }

    /** Координата точки выделения (1|2) по оси (0=x, 1=y, 2=z); "" если выделения нет. */
    private String selCoord(OfflinePlayer offline, int point, int axis) {
        if (!(offline instanceof Player p) || !plugin.selections().has(p)) {
            return "";
        }
        BlockVector3 v = plugin.selections().get(p).getPos(point);
        switch (axis) {
            case 0:
                return String.valueOf(v.getBlockX());
            case 1:
                return String.valueOf(v.getBlockY());
            default:
                return String.valueOf(v.getBlockZ());
        }
    }

    /** Текущий регион игрока (null, если игрок вне регионов или офлайн). */
    private ProtectedRegion cur(OfflinePlayer offline) {
        return offline instanceof Player p ? plugin.wg().current(p) : null;
    }

    /** Установленные флаги текущего региона: "флаг:значение, ..." ("" вне региона). */
    private String regionFlags(OfflinePlayer offline) {
        ProtectedRegion r = cur(offline);
        if (r == null) {
            return "";
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (var e : r.getFlags().entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            parts.add(e.getKey().getName() + ":"
                    + e.getValue().toString().toLowerCase(java.util.Locale.ROOT));
        }
        parts.sort(String::compareToIgnoreCase);
        return String.join(", ", parts);
    }

    /** Значение конкретного флага текущего региона: %qqregions_region_flag_<флаг>%. */
    private String regionFlagCur(OfflinePlayer offline, String flagName) {
        if (flagName == null || flagName.isEmpty()) {
            return "";
        }
        ProtectedRegion r = cur(offline);
        if (r == null) {
            return "";
        }
        Flag<?> f = plugin.wg().flag(flagName);
        if (f == null) {
            return "";
        }
        return plugin.wg().flagValue(offline instanceof Player p ? p.getWorld() : null, r, f);
    }

    private String currentOwnerNames(OfflinePlayer offline) {
        ProtectedRegion r = cur(offline);
        return r == null ? "" : joinPeople(r, true);
    }

    private String currentMemberNames(OfflinePlayer offline) {
        ProtectedRegion r = cur(offline);
        return r == null ? "" : joinPeople(r, false);
    }

    /** Владельцы или участники региона через разделитель из config.yml. */
    private String joinPeople(ProtectedRegion r, boolean owners) {
        String sep = owners ? plugin.config().ownersSeparator() : plugin.config().membersSeparator();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Wg.Participant part : plugin.wg().participants(r)) {
            if (part.owner() == owners) {
                names.add(part.name());
            }
        }
        return String.join(sep, names);
    }

    /** yes/no: игрок владелец (или участник) текущего региона. */
    private String currentRoleIs(OfflinePlayer offline, boolean wantOwner) {
        if (!(offline instanceof Player p)) {
            return "no";
        }
        ProtectedRegion r = plugin.wg().current(p);
        if (r == null) {
            return "no";
        }
        Wg.RegionRole role = plugin.wg().role(r, p);
        return yesNo(wantOwner ? role == Wg.RegionRole.OWNER : role == Wg.RegionRole.MEMBER);
    }

    /** Роль игрока в текущем регионе: OWNER | MEMBER | NONE ("" вне региона). */
    private String currentRole(OfflinePlayer offline) {
        if (!(offline instanceof Player p)) {
            return "";
        }
        ProtectedRegion r = plugin.wg().current(p);
        return r == null ? "" : plugin.wg().role(r, p).name();
    }

    /** Регионы, где игрок владелец (или участник). Онлайн — в текущем мире,
     *  офлайн — по всем загруженным мирам. У администратора владение = всё. */
    private java.util.List<String> ownedOrMembered(OfflinePlayer offline, boolean wantOwned) {
        java.util.List<String> out = new java.util.ArrayList<>();
        UUID u = offline.getUniqueId();
        Wg wg = plugin.wg();
        if (offline instanceof Player p) {
            for (ProtectedRegion r : wg.all(p.getWorld())) {
                if (plugin.config().isBannedRegion(r.getId())) {
                    continue;
                }
                Wg.RegionRole role = wg.role(r, p);
                if (wantOwned ? role == Wg.RegionRole.OWNER : role == Wg.RegionRole.MEMBER) {
                    out.add(r.getId());
                }
            }
        } else {
            for (org.bukkit.World w : plugin.getServer().getWorlds()) {
                for (ProtectedRegion r : wg.all(w)) {
                    if (plugin.config().isBannedRegion(r.getId())) {
                        continue;
                    }
                    boolean owner = wg.isOwner(r, u);
                    if (wantOwned) {
                        if (owner) {
                            out.add(r.getId());
                        }
                    } else if (wg.isMember(r, u) && !owner) {
                        out.add(r.getId());
                    }
                }
            }
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    private ProtectedRegion nearestOf(OfflinePlayer offline) {
        if (!(offline instanceof Player p)) {
            return null;
        }
        ProtectedRegion best = null;
        double bestD = Double.MAX_VALUE;
        org.bukkit.Location loc = p.getLocation();
        for (ProtectedRegion r : plugin.wg().all(p.getWorld())) {
            if (plugin.config().isBannedRegion(r.getId())) {
                continue;
            }
            double d = dist2d(loc, r);
            if (d < bestD) {
                bestD = d;
                best = r;
            }
        }
        return best;
    }

    private String nearestRegion(OfflinePlayer offline) {
        ProtectedRegion n = nearestOf(offline);
        return n == null ? "" : n.getId();
    }

    private String nearestDistance(OfflinePlayer offline) {
        if (!(offline instanceof Player p)) {
            return "";
        }
        ProtectedRegion n = nearestOf(offline);
        return n == null ? "" : String.valueOf(Math.round(dist2d(p.getLocation(), n)));
    }

    /** Регионов в мире игрока в радиусе radius блоков (2D, 0 — только занимаемые). */
    private int countNearby(OfflinePlayer offline, int radius) {
        if (!(offline instanceof Player p)) {
            return 0;
        }
        org.bukkit.Location loc = p.getLocation();
        int c = 0;
        for (ProtectedRegion r : plugin.wg().all(p.getWorld())) {
            if (plugin.config().isBannedRegion(r.getId())) {
                continue;
            }
            if (dist2d(loc, r) <= radius) {
                c++;
            }
        }
        return c;
    }

    /** Горизонтальное расстояние от точки до AABB региона (0, если точка внутри). */
    private static double dist2d(org.bukkit.Location loc, ProtectedRegion r) {
        BlockVector3 mn = r.getMinimumPoint();
        BlockVector3 mx = r.getMaximumPoint();
        double px = loc.getX();
        double pz = loc.getZ();
        double dx = clamp(px, mn.getBlockX(), mx.getBlockX()) - px;
        double dz = clamp(pz, mn.getBlockZ(), mx.getBlockZ()) - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double clamp(double v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
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

    /** "мир:территория" -> активный оффер (или по типу kind). */
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