package dev.qqregions.wg;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.ProtectedRegion;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.FlagContext;
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
import java.util.List;
import java.util.UUID;

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

    public void create(Selection selection, String name, Player owner) throws RegionException {
        RegionManager rm = manager(selection.getWorld());
        if (rm == null) {
            throw new RegionException("create.fail", "error", "RegionManager недоступен");
        }
        if (rm.hasRegion(name)) {
            throw new RegionException("create.already-exists", "region", name);
        }
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(name, selection.min(), selection.max());
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

    /**
     * Текущее значение флага для региона (с учётом дефолта мира) в виде строки.
     * Для групп используйте заполнитель WorldGuard "%worldguard_region_has_flag_<флаг>:<группа>%".
     */
    public String flagValue(World world, ProtectedRegion region, Flag<?> flag) {
        Object value = region == null ? null : region.getFlag(flag);
        return value == null ? "" : value.toString().toLowerCase(java.util.Locale.ROOT);
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
            if (r.intersects(probe)) {
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
}