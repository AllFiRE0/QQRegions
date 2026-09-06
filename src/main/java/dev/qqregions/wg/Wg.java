package dev.qqregions.wg;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.flags.BooleanFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.FlagContext;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import com.sk89q.worldguard.protection.flags.RegionGroupFlag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.RemovalStrategy;
import com.sk89q.worldguard.protection.managers.storage.StorageException;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import dev.qqregions.QQRegions;
import dev.qqregions.selection.Selection;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Бэкенд WorldGuard: создание/удаление регионов, участники, владельцы,
 * поиск текущего региона, флаги.
 */
public class Wg {

    private final QQRegions plugin;

    public Wg(QQRegions plugin) {
        this.plugin = plugin;
    }

    public RegionManager manager(World world) {
        if (world == null) {
            return null;
        }
        try {
            WorldGuardPlatform platform = WorldGuard.getInstance().getPlatform();
            if (platform == null || platform.getRegionContainer() == null) {
                return null;
            }
            return platform.getRegionContainer().get(BukkitAdapter.adapt(world));
        } catch (Throwable t) {
            plugin.getLogger().warning("WorldGuard API недоступен: " + t.getMessage());
            return null;
        }
    }

    public ProtectedRegion byName(World world, String name) {
        RegionManager rm = manager(world);
        return rm == null ? null : rm.getRegion(name);
    }

    public Collection<ProtectedRegion> all(World world) {
        RegionManager rm = manager(world);
        return rm == null ? List.of() : rm.getRegions().values();
    }

    /** Самый маленький регион, в котором стоит игрок. */
    public ProtectedRegion current(Player player) {
        RegionManager rm = manager(player.getWorld());
        if (rm == null) {
            return null;
        }
        BlockVector3 v = BukkitAdapter.asBlockVector(player.getLocation());
        ApplicableRegionSet set = rm.getApplicableRegions(v);
        ProtectedRegion best = null;
        long bestVolume = Long.MAX_VALUE;
        for (ProtectedRegion r : set.getRegions()) {
            if (r.contains(v)) {
                long vol = r.volume();
                if (vol < bestVolume) {
                    bestVolume = vol;
                    best = r;
                }
            }
        }
        return best;
    }

    public boolean owns(ProtectedRegion region, Player player) {
        if (region == null) {
            return false;
        }
        if (player.hasPermission("qqregions.admin")) {
            return true;
        }
        return contains(region.getOwners(), player);
    }

    public boolean isOwner(ProtectedRegion region, UUID uuid) {
        return region != null && contains(region.getOwners(), uuid);
    }

    public boolean isMember(ProtectedRegion region, UUID uuid) {
        return region != null && (contains(region.getOwners(), uuid) || contains(region.getMembers(), uuid));
    }

    private boolean contains(DefaultDomain set, Player player) {
        try {
            return set.contains(player.getUniqueId());
        } catch (Throwable t) {
            return set.contains(player.getName());
        }
    }

    private boolean contains(DefaultDomain set, UUID uuid) {
        try {
            return set.contains(uuid);
        } catch (Throwable t) {
            return set.contains(uuid.toString());
        }
    }

    /** Список регионов мира, которыми владеет игрок. */
    public List<String> ownedNames(World world, Player player) {
        List<String> out = new ArrayList<>();
        boolean admin = player.hasPermission("qqregions.admin");
        for (ProtectedRegion r : all(world)) {
            boolean owned = admin || contains(r.getOwners(), player);
            if (owned && !plugin.config().isBannedRegion(r.getId())) {
                out.add(r.getId());
            }
        }
        return out;
    }

    /** Роль игрока в регионе: OWNER / MEMBER / NONE (админ всегда OWNER). */
    public RegionRole role(ProtectedRegion region, Player player) {
        if (region == null) {
            return RegionRole.NONE;
        }
        if (player.hasPermission("qqregions.admin")) {
            return RegionRole.OWNER;
        }
        if (contains(region.getOwners(), player)) {
            return RegionRole.OWNER;
        }
        if (contains(region.getMembers(), player)) {
            return RegionRole.MEMBER;
        }
        return RegionRole.NONE;
    }

    /** Регионы, которые игроку дозволено видеть (владелец/участник; админ — все). */
    public List<String> visibleNames(World world, Player player) {
        List<String> out = new ArrayList<>();
        boolean admin = player.hasPermission("qqregions.admin");
        for (ProtectedRegion r : all(world)) {
            if (plugin.config().isBannedRegion(r.getId())) {
                continue;
            }
            if (admin || role(r, player) != RegionRole.NONE) {
                out.add(r.getId());
            }
        }
        return out;
    }

    /** Чарсет ID региона WorldGuard: [0-9A-Za-z_\-,.]+ (кириллица запрещена).
     *  Дублирует проверку ProtectedRegion, чтобы показывать дружелюбную
     *  ошибку, а не ловить IllegalArgumentException. */
    private static final Pattern REGION_ID = Pattern.compile("[0-9A-Za-z_\\-,.]+");

    public void create(Selection selection, String name, Player owner) throws RegionException {
        RegionManager rm = manager(selection.getWorld());
        if (rm == null) {
            throw new RegionException("create.fail", "error", "RegionManager недоступен");
        }
        if (name == null || !REGION_ID.matcher(name).matches()) {
            throw new RegionException("create.invalid-id", "charset", REGION_ID.pattern());
        }
        if (rm.hasRegion(name)) {
            throw new RegionException("create.already-exists", "region", name);
        }
        ProtectedCuboidRegion region;
        try {
            region = new ProtectedCuboidRegion(name, selection.min(), selection.max());
        } catch (IllegalArgumentException e) {
            // Страховка: если WorldGuard ужесточит правила ID/длины.
            throw new RegionException("create.invalid-id", "charset", REGION_ID.pattern());
        }
        region.getOwners().addPlayer(owner.getUniqueId());
        rm.addRegion(region);
        try {
            rm.save();
        } catch (StorageException e) {
            rm.removeRegion(name, RemovalStrategy.REMOVE_CHILDREN);
            throw new RegionException("create.fail", "error", e.getMessage());
        }
    }

    public void delete(World world, String name) throws RegionException {
        RegionManager rm = manager(world);
        if (rm == null || !rm.hasRegion(name)) {
            throw new RegionException("delete.not-found", "region", name);
        }
        rm.removeRegion(name, RemovalStrategy.REMOVE_CHILDREN);
        try {
            rm.save();
        } catch (StorageException e) {
            throw new RegionException("delete.fail", "error", e.getMessage());
        }
    }

    public void addPlayer(World world, ProtectedRegion region, UUID uuid, boolean owner) {
        if (owner) {
            region.getOwners().addPlayer(uuid);
        } else {
            region.getMembers().addPlayer(uuid);
        }
        saveQuiet(world, region);
    }

    public void removePlayer(World world, ProtectedRegion region, UUID uuid, boolean owner) {
        if (owner) {
            region.getOwners().removePlayer(uuid);
        } else {
            region.getMembers().removePlayer(uuid);
        }
        saveQuiet(world, region);
    }

    /** Удалить участника по UUID или имени (владелец/участник). */
    public void removePlayerId(World world, ProtectedRegion region, String id, boolean owner) {
        if (id == null || id.trim().isEmpty()) {
            return;
        }
        String v = id.trim();
        DefaultDomain set = owner ? region.getOwners() : region.getMembers();
        UUID uuid = null;
        try {
            uuid = UUID.fromString(v);
        } catch (IllegalArgumentException ignored) {
        }
        if (uuid != null) {
            set.removePlayer(uuid);
        } else {
            set.removePlayer(v);
        }
        saveQuiet(world, region);
    }

    /** Добавить участника по нику (резолвит UUID через сервер, иначе — имя). */
    public void addPlayerByName(World world, ProtectedRegion region, String name, boolean owner) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String n = name.trim();
        DefaultDomain set = owner ? region.getOwners() : region.getMembers();
        UUID uuid = null;
        try {
            uuid = Bukkit.getOfflinePlayer(n).getUniqueId();
        } catch (Throwable ignored) {
        }
        if (uuid != null) {
            set.addPlayer(uuid);
        } else {
            set.addPlayer(n);
        }
        saveQuiet(world, region);
    }

    private void saveQuiet(World world, ProtectedRegion region) {
        RegionManager rm = manager(world);
        if (rm == null) {
            return;
        }
        try {
            rm.save();
        } catch (StorageException ignored) {
        }
    }

    /** Все зарегистрированные флаги (WG + WGEFP). */
    public Collection<Flag<?>> allFlags() {
        try {
            return WorldGuard.getInstance().getFlagRegistry().getAll();
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** Флаг по имени из реестра (WG + WGEFP + другие плагины). */
    public Flag<?> flag(String name) {
        try {
            return WorldGuard.getInstance().getFlagRegistry().get(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Текущее значение флага для региона (с учётом дефолта мира) в виде строки. */
    public String flagValue(World world, ProtectedRegion region, Flag<?> flag) {
        Object value = region == null ? null : region.getFlag(flag);
        return value == null ? "" : value.toString().toLowerCase(java.util.Locale.ROOT);
    }

    /** Текущая группа (region group) флага. Если группа не задана явно —
     *  считается "all" (приближение для меню; у entry/exit/spawn дефолт
     *  другой, но явно заданной группы нет). */
    public String flagGroup(World world, ProtectedRegion region, Flag<?> flag) {
        if (region == null || flag == null) {
            return "all";
        }
        try {
            RegionGroupFlag groupFlag = flag.getRegionGroupFlag();
            if (groupFlag == null) {
                return "all";
            }
            RegionGroup g = region.getFlag(groupFlag);
            if (g == null || g == RegionGroup.NONE) {
                return "all";
            }
            switch (g) {
                case MEMBERS:
                    return "members";
                case OWNERS:
                    return "owners";
                case NON_MEMBERS:
                    return "nonmembers";
                case NON_OWNERS:
                    return "nonowners";
                default:
                    return g.name().toLowerCase(java.util.Locale.ROOT);
            }
        } catch (Throwable t) {
            return "all";
        }
    }

    /**
     * Значение флага применительно к группе кнопки меню.
     * У флага может быть только ОДНА группа (см. доки WorldGuard), поэтому:
     *  - группа кнопки == текущая группа флага → возвращается значение;
     *  - иначе "" = «не задано для этой группы».
     */
    public String flagValueFor(World world, ProtectedRegion region, Flag<?> flag, String group) {
        String eff = flagGroup(world, region, flag);
        if (!eff.equalsIgnoreCase(group == null ? "all" : group)) {
            return "";
        }
        return flagValue(world, region, flag);
    }

    private RegionGroup parseGroup(String name) {
        if (name == null) {
            return null;
        }
        switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "all":
                return RegionGroup.ALL;
            case "members":
                return RegionGroup.MEMBERS;
            case "owners":
                return RegionGroup.OWNERS;
            case "nonmembers":
                return RegionGroup.NON_MEMBERS;
            case "nonowners":
                return RegionGroup.NON_OWNERS;
            default:
                return null;
        }
    }

    /**
     * Установить значение флага региона напрямую через API WorldGuard.
     * Return true при успехе. Сохраняет регион.
     */
    public boolean setFlagValue(World world, ProtectedRegion region, Flag<?> flag, String rawValue) {
        return setFlagValue(world, region, flag, rawValue, null);
    }

    /**
     * Установить значение флага + группы (region group) региона.
     * group = all|members|owners|nonmembers|nonowners ("" / null — не трогать).
     * Эквивалентно /rg flag -g <группа> <флаг> <значение>.
     */
    public boolean setFlagValue(World world, ProtectedRegion region, Flag<?> flag, String rawValue, String group) {
        if (region == null || world == null || flag == null) {
            return false;
        }
        Object parsed;
        try {
            String s = rawValue == null ? "" : rawValue.trim();
            if (flag instanceof StateFlag) {
                parsed = "deny".equalsIgnoreCase(s) ? StateFlag.State.DENY : StateFlag.State.ALLOW;
            } else if (flag instanceof BooleanFlag) {
                parsed = Boolean.parseBoolean(s);
            } else {
                // строковые/числовые/наборные флаги не поддерживаются из меню
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        if (parsed == null) {
            return false;
        }
        try {
            if (group != null && !group.isEmpty()) {
                RegionGroup rg = parseGroup(group);
                RegionGroupFlag groupFlag = flag.getRegionGroupFlag();
                if (rg != null && groupFlag != null) {
                    region.setFlag((Flag) groupFlag, rg);
                }
            }
            region.setFlag((Flag) flag, parsed);
            RegionManager rm = manager(world);
            if (rm != null) {
                rm.save();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Регионы, с которыми пересекается выделение (для сигнала конфликта). */
    public List<ProtectedRegion> intersecting(Selection selection) {
        List<ProtectedRegion> out = new ArrayList<>();
        RegionManager rm = manager(selection.getWorld());
        if (rm == null) {
            return out;
        }
        ProtectedCuboidRegion probe = new ProtectedCuboidRegion(
                "__qqregions_probe__", selection.min(), selection.max());
        for (ProtectedRegion r : rm.getRegions().values()) {
            if (r.getId().equals(probe.getId()) || plugin.config().isBannedRegion(r.getId())) {
                continue;
            }
            if (!probe.getIntersectingRegions(List.of(r)).isEmpty()) {
                out.add(r);
            }
        }
        return out;
    }

    /** Проверка применимости флага (для меню): используется FlagContext статически. */
    @SuppressWarnings("unused")
    public static boolean flagContextAvailable() {
        try {
            Class.forName(FlagContext.class.getName());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- отображение доменов ----------

    private String names(DefaultDomain set) {
        StringBuilder sb = new StringBuilder();
        try {
            for (UUID uuid : set.getUniqueIds()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                String n = op.getName();
                sb.append(n != null ? n : uuid.toString().substring(0, 8));
            }
        } catch (Throwable ignored) {
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }

    public String owners(ProtectedRegion region) {
        return names(region.getOwners());
    }

    public String members(ProtectedRegion region) {
        return names(region.getMembers());
    }

    /** Список участников для меню: владельцы сначала, потом участники. */
    public List<Participant> participants(ProtectedRegion region) {
        List<Participant> out = new ArrayList<>();
        Set<UUID> seenUuids = new HashSet<>();
        Set<String> seenNames = new HashSet<>();
        if (region != null) {
            addDomain(region.getOwners(), true, out, seenUuids, seenNames);
            addDomain(region.getMembers(), false, out, seenUuids, seenNames);
        }
        return out;
    }

    private void addDomain(DefaultDomain set, boolean owner, List<Participant> out,
                           Set<UUID> seenUuids, Set<String> seenNames) {
        try {
            for (UUID u : set.getUniqueIds()) {
                if (!seenUuids.add(u)) {
                    continue;
                }
                String n = nameOf(u);
                seenNames.add(n.toLowerCase(java.util.Locale.ROOT));
                out.add(new Participant(u, n, owner));
            }
            for (String name : set.getPlayers()) {
                String lc = name.toLowerCase(java.util.Locale.ROOT);
                if (seenNames.contains(lc)) {
                    continue;
                }
                UUID u = null;
                try {
                    u = UUID.fromString(name);
                } catch (IllegalArgumentException ignored) {
                }
                if (u != null) {
                    if (!seenUuids.add(u)) {
                        continue;
                    }
                    out.add(new Participant(u, nameOf(u), owner));
                    seenNames.add(nameOf(u).toLowerCase(java.util.Locale.ROOT));
                } else {
                    out.add(new Participant(null, name, owner));
                    seenNames.add(lc);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private String nameOf(UUID uuid) {
        try {
            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            String n = op.getName();
            return n != null ? n : uuid.toString().substring(0, 8);
        } catch (Throwable t) {
            return uuid == null ? "?" : uuid.toString().substring(0, 8);
        }
    }

    /** Пара «игрок региона» для меню партисипантов. */
    public record Participant(UUID uuid, String name, boolean owner) {
    }

    /** Роль игрока в регионе. */
    public enum RegionRole {
        NONE, MEMBER, OWNER
    }
}