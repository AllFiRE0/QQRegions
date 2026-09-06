package dev.qqregions.highlight;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.qqregions.QQRegions;
import dev.qqregions.config.Config;
import dev.qqregions.util.BoxOutline;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Подсветка границ регионов: команды /region visible и флаг territory-visible.
 *
 * Флаг: при входе игрока в регион с territory-visible=allow контур региона
 * рисуется частицами окном highlight.show-seconds и гаснет сам (частицы
 * пересылаются, пока окно живо, затем прекращаются). Повторное срабатывание
 * флага — не чаще раз в highlight.cooldown-seconds на игрока и регион, поэтому
 * бег вдоль границы не спамит частицами и не нагружает сервер.
 *
 * Команда: /region visible [название] [particles|blocks] показывает/скрывает
 * контур региона; /region visible off скрывает все; /region visible type
 * <particles|blocks> задаёт тип по умолчанию (per-игрок).
 *
 * PARTICLES — частицы по рёбрам, пересчитываются каждые update-ticks.
 * BLOCKS   — BlockDisplay-точки со свечением, спавнятся при показе и
 *            убираются сами по истечении окна.
 */
public class HighlightManager implements Listener {

    private final QQRegions plugin;

    /** Активные подсветки: игрок -> (ключ региона -> окно показа). */
    private final Map<UUID, Map<String, RegionShow>> active = new HashMap<>();
    /** Кулдаун флага: игрок -> (ключ региона -> когда можно снова). */
    private final Map<UUID, Map<String, Long>> cooldown = new HashMap<>();
    /** BlockDisplay-точки: игрок -> (ключ региона -> сущности). */
    private final Map<UUID, Map<String, List<Entity>>> blockViews = new HashMap<>();
    /** Тип подсветки по умолчанию (/region visible type) на игрока. */
    private final Map<UUID, String> defaultType = new HashMap<>();
    /** Кэш точек terrain-подсветки: "world:region" -> верхние точки столбцов. */
    private final Map<String, List<BlockVector3>> terrainCache = new HashMap<>();
    /** Флаг-регионы, подсвеченные входом/выходом и ещё НЕ вышедшие (для hide-on-exit). */
    private final Map<UUID, Set<String>> flagShown = new HashMap<>();

    private int scanTimer = 0;
    private int renderTimer = 0;

    private static final class RegionShow {
        final long until;
        final String type;
        final String world;
        final String name;

        RegionShow(long until, String type, String world, String name) {
            this.until = until;
            this.type = type;
            this.world = world;
            this.name = name;
        }
    }

    public HighlightManager(QQRegions plugin) {
        this.plugin = plugin;
    }

    public void tick() {
        Config cfg = plugin.config();
        if (!cfg.highlight().enabled) {
            clearAll();
            return;
        }
        scanTimer += 5;
        if (scanTimer >= cfg.highlight().scanTicks) {
            scanTimer = 0;
            scan();
        }
        renderTimer += 5;
        if (renderTimer >= cfg.highlight().particles.updateTicks) {
            renderTimer = 0;
            render();
        }
    }

    // ---------- флаг territory-visible ----------

    private void scan() {
        Config.HighlightOptions h = plugin.config().highlight();
        if (!h.flagEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.config().isWorldDisabled(p.getWorld())) {
                continue;
            }
            java.util.Set<String> cur = new java.util.HashSet<>();
            List<ProtectedRegion> under = new ArrayList<>();
            for (ProtectedRegion r : plugin.wg().at(p.getWorld(), p.getLocation())) {
                if (!plugin.wg().territoryVisibleAllows(p.getWorld(), r, p)) {
                    continue;
                }
                cur.add(key(p.getWorld(), r));
                under.add(r);
            }
            // hide-on-exit: если региона больше нет под игроком — скрываем.
            if (h.hideOnExit) {
                Set<String> prev = flagShown.get(p.getUniqueId());
                if (prev != null) {
                    for (String k : new ArrayList<>(prev)) {
                        if (!cur.contains(k)) {
                            prev.remove(k);
                            cooldownRemove(p, k);
                            if (isActive(p, k)) {
                                removeActive(p, k);
                            }
                        }
                    }
                }
            }
            for (ProtectedRegion r : under) {
                String key = key(p.getWorld(), r);
                if (onCooldown(p, key, now)) {
                    continue;
                }
                markCooldown(p, key, now);
                flagShown.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(key);
                show(p, p.getWorld(), r, h.type);
            }
        }
    }

    private void cooldownRemove(Player p, String key) {
        Map<String, Long> m = cooldown.get(p.getUniqueId());
        if (m != null) {
            m.remove(key);
            if (m.isEmpty()) {
                cooldown.remove(p.getUniqueId());
            }
        }
    }

    private boolean onCooldown(Player p, String key, long now) {
        Map<String, Long> m = cooldown.get(p.getUniqueId());
        return m != null && now < m.getOrDefault(key, 0L);
    }

    private void markCooldown(Player p, String key, long now) {
        cooldown.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>())
                .put(key, now + plugin.config().highlight().cooldownMillis);
    }

    // ---------- показ / скрытие ----------

    /** Показать подсветку региона на show-seconds (shared командой и флагом). */
    public void show(Player p, World world, ProtectedRegion r, String type) {
        String key = key(world, r);
        String t = normalizeType(type);
        long until = System.currentTimeMillis() + plugin.config().highlight().showMillis;
        active.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>())
                .put(key, new RegionShow(until, t, world.getName(), r.getId()));
        if (isBlocks(t) && world.getName().equals(p.getWorld().getName())) {
            spawnBlocks(p, world, r, key);
        }
    }

    /** true — подсветка включена, false — была активна и скрыта (toggle). */
    public boolean toggle(Player p, World world, ProtectedRegion r, String type) {
        String key = key(world, r);
        if (isActive(p, key)) {
            removeActive(p, key);
            return false;
        }
        show(p, world, r, type);
        return true;
    }

    public boolean isActive(Player p, ProtectedRegion r) {
        return isActive(p, key(p.getWorld(), r));
    }

    /** Скрыть подсветку конкретного региона. */
    public void hide(Player p, World world, ProtectedRegion r) {
        removeActive(p, key(world, r));
    }

    /** Скрыть все подсветки игрока. */
    public void hideAll(Player p) {
        Map<String, RegionShow> map = active.get(p.getUniqueId());
        if (map == null) {
            return;
        }
        for (String key : new ArrayList<>(map.keySet())) {
            removeActive(p, key);
        }
    }

    // ---------- рендер ----------

    private void render() {
        long now = System.currentTimeMillis();
        for (UUID uid : new ArrayList<>(active.keySet())) {
            Player p = Bukkit.getPlayer(uid);
            if (p == null) {
                removePlayer(uid);
                continue;
            }
            Map<String, RegionShow> map = active.get(uid);
            for (Map.Entry<String, RegionShow> e : new ArrayList<>(map.entrySet())) {
                RegionShow s = e.getValue();
                if (now >= s.until) {
                    removeActive(p, e.getKey());
                } else if (!isBlocks(s.type)) {
                    renderParticles(p, s);
                }
            }
        }
    }

    private void renderParticles(Player p, RegionShow s) {
        Config.HighlightOptions h = plugin.config().highlight();
        if (!h.particles.enabled) {
            return;
        }
        World world = Bukkit.getWorld(s.world);
        ProtectedRegion r = world == null ? null : plugin.wg().byName(world, s.name);
        if (world == null || r == null) {
            return;
        }
        if ("TERRITORY".equals(s.type)) {
            // TERRITORY: частицы над верхними блоками вдоль границы (по рельефу).
            List<BlockVector3> pts = terrainPoints(world, r);
            int budget = Math.min(h.particles.maxPoints, pts.size());
            for (int i = 0; i < budget; i++) {
                BlockVector3 pt = pts.get(i);
                int cx = pt.getBlockX() >> 4;
                int cz = pt.getBlockZ() >> 4;
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                spawnParticle(world, h.particles, pt.getBlockX() + 0.5, pt.getBlockY() + 0.5, pt.getBlockZ() + 0.5);
            }
            return;
        }
        BlockVector3 mn = r.getMinimumPoint();
        BlockVector3 mx = r.getMaximumPoint();
        for (BlockVector3 pt : BoxOutline.points(mn, mx, h.particles.maxPoints)) {
            int cx = pt.getBlockX() >> 4;
            int cz = pt.getBlockZ() >> 4;
            if (!world.isChunkLoaded(cx, cz)) {
                continue;
            }
            spawnParticle(world, h.particles, pt.getBlockX() + 0.5, pt.getBlockY() + 0.5, pt.getBlockZ() + 0.5);
        }
    }

    // ---------- TERRITORY (террейн-подсветка вдоль границы) ----------

    /**
     * Верхние точки столбцов вдоль периметра региона: для каждой колонки
     * (x,z) периметра ищется верхний не-воздушный блок — в обычном мире это
     * поверхность рельефа, под землёй — потолок пещеры/камень. Результат
     * кэшируется на "world:region" и пересчитывается при пересоздании.
     */
    private List<BlockVector3> terrainPoints(World world, ProtectedRegion r) {
        String ck = world.getName() + ":" + r.getId();
        List<BlockVector3> cached = terrainCache.get(ck);
        if (cached != null) {
            return cached;
        }
        List<BlockVector3> out = new ArrayList<>();
        try {
            BlockVector3 mn = r.getMinimumPoint();
            BlockVector3 mx = r.getMaximumPoint();
            int minY = Math.max(world.getMinHeight(), mn.y());
            int maxY = Math.min(world.getMaxHeight() - 1, mx.y());
            Set<Long> seen = new HashSet<>();
            for (BlockVector3 pt : BoxOutline.points(mn, mx, 4000)) {
                long col = ((long) pt.getBlockX() << 32) | (pt.getBlockZ() & 0xffffffffL);
                if (!seen.add(col)) {
                    continue;
                }
                int cx = pt.getBlockX() >> 4;
                int cz = pt.getBlockZ() >> 4;
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                int top = topNonAirY(world, pt.getBlockX(), pt.getBlockZ(), minY, maxY);
                if (top >= 0) {
                    out.add(BlockVector3.at(pt.getBlockX(), top + 1, pt.getBlockZ()));
                }
            }
        } catch (Throwable t) {
            plugin.dbg("terrainPoints error: " + t.getMessage());
        }
        terrainCache.put(ck, out);
        return out;
    }

    /** Самый верхний блок колонки (не воздух) в диапазоне высот; -1 если нет. */
    private int topNonAirY(World world, int x, int z, int minY, int maxY) {
        for (int y = maxY; y >= minY; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type != Material.AIR && type != Material.CAVE_AIR && type != Material.VOID_AIR) {
                return y;
            }
        }
        return -1;
    }

    private void spawnParticle(World world, Config.ParticleOptions po, double x, double y, double z) {
        Particle particle;
        try {
            particle = Particle.valueOf(po.particleName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            particle = Particle.DUST;
        }
        Object data = null;
        if (particle == Particle.DUST) {
            data = new Particle.DustOptions(po.dustColor, po.dustSize);
        }
        world.spawnParticle(particle, x, y, z, po.amount, 0.0, 0.0, 0.0, po.speed, data);
    }

    // ---------- BLOCKS ----------

    private void spawnBlocks(Player p, World world, ProtectedRegion r, String key) {
        Config.HighlightOptions h = plugin.config().highlight();
        Map<String, List<Entity>> perPlayer = blockViews.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        if (perPlayer.containsKey(key)) {
            return;
        }
        int budget = Math.min(h.particles.maxPoints, 600);
        List<Entity> list = new ArrayList<>(budget);
        BlockVector3 mn = r.getMinimumPoint();
        BlockVector3 mx = r.getMaximumPoint();
        for (BlockVector3 pt : BoxOutline.points(mn, mx, budget)) {
            int cx = pt.getBlockX() >> 4;
            int cz = pt.getBlockZ() >> 4;
            if (!world.isChunkLoaded(cx, cz)) {
                continue;
            }
            BlockDisplay d = world.spawn(
                    new Location(world, pt.getBlockX() + 0.5, pt.getBlockY() + 0.5, pt.getBlockZ() + 0.5),
                    BlockDisplay.class);
            d.setBlock(h.block.createBlockData());
            d.setTransformation(new Transformation(
                    new Vector3f(), new Quaternionf(),
                    new Vector3f(h.blockScale, h.blockScale, h.blockScale), new Quaternionf()));
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(0);
            d.setGlowColorOverride(h.particles.dustColor);
            d.setInvulnerable(true);
            list.add(d);
        }
        perPlayer.put(key, list);
    }

    private void despawnBlocks(Player p, String key) {
        Map<String, List<Entity>> perPlayer = blockViews.get(p.getUniqueId());
        if (perPlayer == null) {
            return;
        }
        List<Entity> list = perPlayer.remove(key);
        if (list != null) {
            for (Entity e : list) {
                e.remove();
            }
        }
        if (perPlayer.isEmpty()) {
            blockViews.remove(p.getUniqueId());
        }
    }

    // ---------- тип по умолчанию (команда) ----------

    public void setDefaultType(Player p, String type) {
        defaultType.put(p.getUniqueId(), normalizeType(type));
    }

    public String typeOf(Player p) {
        String t = defaultType.get(p.getUniqueId());
        return t != null ? t : plugin.config().highlight().type;
    }

    // ---------- уборка ----------

    private boolean isActive(Player p, String key) {
        Map<String, RegionShow> map = active.get(p.getUniqueId());
        return map != null && map.containsKey(key);
    }

    private void removeActive(Player p, String key) {
        Map<String, RegionShow> map = active.get(p.getUniqueId());
        if (map != null) {
            map.remove(key);
            if (map.isEmpty()) {
                active.remove(p.getUniqueId());
            }
        }
        despawnBlocks(p, key);
    }

    /** Полная очистка игрока (выход / оффлайн). */
    private void removePlayer(UUID uid) {
        Map<String, List<Entity>> perPlayer = blockViews.remove(uid);
        if (perPlayer != null) {
            for (List<Entity> list : perPlayer.values()) {
                for (Entity e : list) {
                    e.remove();
                }
            }
        }
        active.remove(uid);
        cooldown.remove(uid);
        defaultType.remove(uid);
        flagShown.remove(uid);
    }

    /** Полная очистка всех (выключение плагина / highlight.enabled=false). */
    public void clearAll() {
        for (Map<String, List<Entity>> perPlayer : blockViews.values()) {
            for (List<Entity> list : perPlayer.values()) {
                for (Entity e : list) {
                    e.remove();
                }
            }
        }
        blockViews.clear();
        active.clear();
        cooldown.clear();
        defaultType.clear();
        flagShown.clear();
        terrainCache.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        removePlayer(e.getPlayer().getUniqueId());
    }

    // ---------- утилиты ----------

    private static String key(World world, ProtectedRegion r) {
        return world.getName() + ":" + r.getId();
    }

    private static boolean isBlocks(String type) {
        return "BLOCKS".equals(type);
    }

    private static String normalizeType(String type) {
        if (type != null && "TERRITORY".equalsIgnoreCase(type)) {
            return "TERRITORY";
        }
        return type != null && "BLOCKS".equalsIgnoreCase(type) ? "BLOCKS" : "PARTICLES";
    }
}