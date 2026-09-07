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
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
    /** Персональный переключатель «подсветка для себя» (/region visible self).
     *  Влияет ТОЛЬКО на автоматическую подсветку по флагу territory-visible;
     *  ручная команда и меню показывают всегда. true — по умолчанию. */
    private final Map<UUID, Boolean> selfEnabled = new HashMap<>();
    /** Кэш точек terrain-подсветки: "world:region" -> (точки + время скана). */
    private final Map<String, TerrainEntry> terrainCache = new HashMap<>();
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

    private static final class TerrainEntry {
        final List<BlockVector3> points;
        final long scannedAt;
        final long version;

        TerrainEntry(List<BlockVector3> points, long scannedAt, long version) {
            this.points = points;
            this.scannedAt = scannedAt;
            this.version = version;
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

    long terrainVersion = 0; // растёт при каждом полном пересканировании terrain

    /** Когда последний раз пересканировалась территория (для TERRITORY-показов). */
    private long lastTerrainRescan = 0;

    /** Пересчитать точки территории и пересобрать «заборы» активных TERRITORY-подсветок. */
    private void rescanActiveTerrain() {
        for (UUID uid : new ArrayList<>(active.keySet())) {
            Player p = Bukkit.getPlayer(uid);
            if (p == null) {
                continue;
            }
            Map<String, RegionShow> map = active.get(uid);
            if (map == null) {
                continue;
            }
            for (Map.Entry<String, RegionShow> e : new ArrayList<>(map.entrySet())) {
                RegionShow s = e.getValue();
                terrainCache.remove(s.world + ":" + s.name);
                if (isFence(s.type)) {
                    World w = Bukkit.getWorld(s.world);
                    ProtectedRegion r = w == null ? null : plugin.wg().byName(w, s.name);
                    if (w != null && r != null && w.getName().equals(p.getWorld().getName())) {
                        spawnBlocks(p, w, r, e.getKey(), "TERRITORY");
                    }
                }
            }
        }
        terrainVersion++;
    }

    // ---------- флаг territory-visible ----------

    /** Включена ли у игрока автоматическая подсветка «для себя» (по флагу). */
    public boolean isSelfEnabled(Player p) {
        return selfEnabled.getOrDefault(p.getUniqueId(), Boolean.TRUE);
    }

    /**
     * Задать персональную подсветку «для себя». Влияет ТОЛЬКО на флаг
     * territory-visible: команда и меню показывают подсветку всегда.
     * При выключении скрываются уже показанные автоматические подсветки.
     */
    public void setSelfEnabled(Player p, boolean enabled) {
        if (enabled) {
            selfEnabled.remove(p.getUniqueId());
            return;
        }
        selfEnabled.put(p.getUniqueId(), false);
        Set<String> prev = flagShown.get(p.getUniqueId());
        if (prev != null) {
            for (String k : new ArrayList<>(prev)) {
                prev.remove(k);
                cooldownRemove(p, k);
            }
            if (prev.isEmpty()) {
                flagShown.remove(p.getUniqueId());
            }
        }
    }

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
            // личная подсветка «для себя» выключена — флаг не срабатывает
            if (!isSelfEnabled(p)) {
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
            Set<String> was = flagShown.get(p.getUniqueId());
            Set<String> next = was == null ? new HashSet<>() : new HashSet<>(was);
            // ВХОД: регион только что появился под игроком — показать один раз
            // (entry-семантика, как entry/exit у WorldGuard: пока игрок бегает
            // внутри, подсветка НЕ перезапускается).
            for (ProtectedRegion r : under) {
                String k = key(p.getWorld(), r);
                if (next.contains(k)) {
                    continue;
                }
                if (onCooldown(p, k, now)) {
                    continue;
                }
                markCooldown(p, k, now);
                next.add(k);
                show(p, p.getWorld(), r, typeFor(p.getWorld(), r));
            }
            // ВЫХОД: hide-on-exit — скрыть, когда региона больше нет под игроком.
            if (was != null) {
                for (String k : was) {
                    if (cur.contains(k)) {
                        continue;
                    }
                    next.remove(k);
                    cooldownRemove(p, k);
                    if (h.hideOnExit && isActive(p, k)) {
                        removeActive(p, k);
                    }
                }
            }
            if (next.isEmpty()) {
                flagShown.remove(p.getUniqueId());
            } else {
                flagShown.put(p.getUniqueId(), next);
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
        if (!world.getName().equals(p.getWorld().getName())) {
            return;
        }
        if ("BLOCKS".equals(t)) {
            spawnBlocks(p, world, r, key, "BLOCKS");
        } else if ("TERRITORY".equals(t)) {
            // повторный запуск = свежий скан (новые блоки на границе видны сразу)
            terrainCache.remove(key);
            if (isFence(t)) {
                spawnBlocks(p, world, r, key, "TERRITORY");
            }
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
        boolean hasTerrainActive = false;
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
                    terrainCache.remove(s.world + ":" + s.name);
                } else if ("TERRITORY".equals(s.type)) {
                    hasTerrainActive = true;
                    if (!isFence(s.type)) {
                        renderTerrainParticles(p, s);
                    }
                    // забор пересобирается в rescanActiveTerrain() по истечении TTL
                } else if (!isBlocks(s.type)) {
                    renderParticles(p, s);
                }
            }
        }
        if (hasTerrainActive) {
            int ttl = plugin.config().highlight().terrainCacheSeconds;
            if (ttl > 0 && now - lastTerrainRescan >= ttl * 1000L) {
                lastTerrainRescan = now;
                rescanActiveTerrain();
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
            renderTerrainParticles(world, r, h.particles);
            return;
        }
        BlockVector3 mn = r.getMinimumPoint();
        BlockVector3 mx = r.getMaximumPoint();
        for (BlockVector3 pt : outlinePoints(mn, mx, plugin.config().outline().maxPoints)) {
            int cx = pt.getBlockX() >> 4;
            int cz = pt.getBlockZ() >> 4;
            if (!world.isChunkLoaded(cx, cz)) {
                continue;
            }
            spawnParticle(world, h.particles, pt.getBlockX() + 0.5, pt.getBlockY() + 0.5, pt.getBlockZ() + 0.5);
        }
    }

    /** TERRITORY-частицы над блоками по периметру (для одного показа). */
    private void renderTerrainParticles(Player p, RegionShow s) {
        World world = Bukkit.getWorld(s.world);
        ProtectedRegion r = world == null ? null : plugin.wg().byName(world, s.name);
        if (world == null || r == null) {
            return;
        }
        renderTerrainParticles(world, r, plugin.config().highlight().particles);
    }

    private void renderTerrainParticles(World world, ProtectedRegion r, Config.ParticleOptions po) {
        List<BlockVector3> pts = terrainPoints(world, r, terrainVersion);
        int budget = Math.min(po.maxPoints, pts.size());
        int step = Math.max(1, (pts.size() + budget - 1) / Math.max(1, budget));
        for (int i = 0; i < pts.size(); i += step) {
            BlockVector3 pt = pts.get(i);
            int cx = pt.getBlockX() >> 4;
            int cz = pt.getBlockZ() >> 4;
            if (!world.isChunkLoaded(cx, cz)) {
                continue;
            }
            spawnParticle(world, po, pt.getBlockX() + 0.5, pt.getBlockY() + 0.5, pt.getBlockZ() + 0.5);
        }
    }

    // ---------- рёбра + кольца (PARTICLES/BLOCKS) ----------

    /**
     * Точки контура региона для PARTICLES/BLOCKS: рёбра куба + «кольца» по
     * периметру (outline.rings) + сетка-«квадраты» на ВСЕХ гранях (верх, низ
     * и четыре боковые — outline.grid). Шаг точек любой линии не больше
     * outline.max-gap — у высоких регионов нет «дыр» из сотен блоков.
     * TERRITORY сюда не попадает — у него свой проход по рельефу
     * (terrainPoints/fenceEntities).
     */
    private List<BlockVector3> outlinePoints(BlockVector3 mn, BlockVector3 mx, int maxPoints) {
        Config.OutlineOptions o = plugin.config().outline();
        int ringStep = o.ringsEnabled ? o.ringStep : Integer.MAX_VALUE;
        return BoxOutline.outline(mn, mx, maxPoints, o.maxGap, ringStep, o.gridStep);
    }

    // ---------- TERRITORY (террейн-подсветка вдоль границы) ----------

    /**
     * Точки подсветки территории вдоль периметра: для каждой колонки (x,z)
     * собираются ВЕРХИ всех пластов (каждый Y-уровень, где блок не воздух,
     * а над ним воздух) и НИЗЫ (блок не воздух, а под ним воздух — своды
     * пещер и выступы). Так деревья больше не «съедают» границу: светятся
     * и трава, и кроны, и потолки пещер.
     *
     * Кэш "world:region" ограничен по времени (terrain-cache-seconds) и
     * инвалидируется: по событиям загрузки чанков (живое появление новых
     * кусков границы), по истечении TTL (подтягиваются свежие блоки) и
     * на каждый повторный запуск подсветки (show()). Внутри одного окна
     * показа пересканирование не чаще раза в TTL секунд.
     */
    private List<BlockVector3> terrainPoints(World world, ProtectedRegion r, long version) {
        String ck = world.getName() + ":" + r.getId();
        TerrainEntry en = terrainCache.get(ck);
        if (en != null && en.version == version && !expired(en)) {
            return en.points;
        }
        List<BlockVector3> out = new ArrayList<>();
        try {
            BlockVector3 mn = r.getMinimumPoint();
            BlockVector3 mx = r.getMaximumPoint();
            int minY = Math.max(world.getMinHeight(), mn.y());
            int maxY = Math.min(world.getMaxHeight() - 1, mx.y());
            Set<Material> ignore = plugin.config().highlight().territoryIgnore;
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
                addColumnLevels(world, pt.getBlockX(), pt.getBlockZ(), minY, maxY, ignore, out);
            }
        } catch (Throwable t) {
            plugin.dbg("terrainPoints error: " + t.getMessage());
        }
        terrainCache.put(ck, new TerrainEntry(out, System.currentTimeMillis(), version));
        return out;
    }

    private boolean expired(TerrainEntry en) {
        int ttl = plugin.config().highlight().terrainCacheSeconds;
        return ttl > 0 && System.currentTimeMillis() - en.scannedAt > ttl * 1000L;
    }

    /**
     * Дополняет out точками всех «слоёв» одной колонки (верхи + пещерные низы).
     * ignore — материалы, которые НЕ считаются рельефом (грибы, трава и т.п.):
     * такие блоки не дают ни верха, ни низа пласта.
     */
    private void addColumnLevels(World world, int x, int z, int minY, int maxY, Set<Material> ignore, List<BlockVector3> out) {
        boolean prevSolid = false; // блок сразу над текущим был не воздухом
        for (int y = maxY; y >= minY; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            boolean solid = type != Material.AIR && type != Material.CAVE_AIR && type != Material.VOID_AIR
                    && (ignore.isEmpty() || !ignore.contains(type));
            if (solid && !prevSolid) {
                // верх пласта: воздух сверху (или граница мира) — точка над блоком
                out.add(BlockVector3.at(x, y + 1, z));
            } else if (!solid && prevSolid) {
                // низ пласта: воздух снизу (свод пещеры / выступ) — точка в пустоте
                out.add(BlockVector3.at(x, y + 1, z));
            }
            prevSolid = solid;
        }
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

    /**
     * Спавн блок-дисплеев по контуру региона.
     * type == BLOCKS   — миниатюрные дисплеи scale block-scale по рёбрам объёма.
     * type == TERRITORY (fence) — «забор»: BlockDisplay по периметру, повторяющий
     * рельеф, с размерами из highlight.territory.fence.
     * На повторный вызов старые дисплеи пересобираются (отражают свежие блоки).
     */
    private void spawnBlocks(Player p, World world, ProtectedRegion r, String key, String type) {
        Config.HighlightOptions h = plugin.config().highlight();
        Map<String, List<Entity>> perPlayer = blockViews.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        List<Entity> old = perPlayer.remove(key);
        if (old != null) {
            for (Entity e : old) {
                e.remove();
            }
        }
        List<Entity> list = "TERRITORY".equals(type)
                ? fenceEntities(world, r, h)
                : boxBlocks(world, r, h);
        perPlayer.put(key, list);
    }

    /** Точки-кубики BLOCKS по рёбрам объёма + кольца по высоте. */
    private List<Entity> boxBlocks(World world, ProtectedRegion r, Config.HighlightOptions h) {
        int budget = Math.max(24, plugin.config().outline().maxPoints);
        List<Entity> list = new ArrayList<>(budget);
        BlockVector3 mn = r.getMinimumPoint();
        BlockVector3 mx = r.getMaximumPoint();
        for (BlockVector3 pt : outlinePoints(mn, mx, budget)) {
            int cx = pt.getBlockX() >> 4;
            int cz = pt.getBlockZ() >> 4;
            if (!world.isChunkLoaded(cx, cz)) {
                continue;
            }
            BlockDisplay d = spawnDisplay(world, pt.getBlockX() + 0.5, pt.getBlockY() + 0.5, pt.getBlockZ() + 0.5,
                    h.block.createBlockData(),
                    new Vector3f(h.blockScale, h.blockScale, h.blockScale),
                    h.particles.dustColor);
            if (d != null) {
                list.add(d);
            }
        }
        return list;
    }

    /** Создать одиночный BlockDisplay. glow == null — без свечения. */
    private BlockDisplay spawnDisplay(World world, double x, double y, double z,
                                      org.bukkit.block.data.BlockData data, Vector3f scale, Color glow) {
        try {
            BlockDisplay d = world.spawn(new Location(world, x, y, z), BlockDisplay.class);
            d.setBlock(data);
            d.setTransformation(new Transformation(
                    new Vector3f(), new Quaternionf(), scale, new Quaternionf()));
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(0);
            if (glow != null) {
                d.setGlowColorOverride(glow);
            }
            d.setInvulnerable(true);
            return d;
        } catch (Throwable t) {
            plugin.dbg("spawnDisplay error: " + t.getMessage());
            return null;
        }
    }

    /**
     * Блок-дисплеи TERRITORY: по каждой из 4 сторон прямоугольника региона
     * ставится ОТДЕЛЬНЫЙ блок-дисплей (штакетина) с шагом fence.spacing:
     * размеры width вдоль границы, height вверх, thickness поперёк, сдвиг
     * offset. Штакетины ставятся на КАЖДУЮ высоту каждой колонки границы,
     * где есть рельеф (не воздух и не из ignore-списка): многослойная
     * граница (дерево, постройка в несколько этажей) больше не создаёт
     * «пустого разрыва» у земли.
     */
    private List<Entity> fenceEntities(World world, ProtectedRegion r, Config.HighlightOptions h) {
        Config.TerrainFenceOptions f = h.fence;
        BlockVector3 mn = r.getMinimumPoint();
        BlockVector3 mx = r.getMaximumPoint();
        int minX = mn.getBlockX(), maxX = mx.getBlockX();
        int minZ = mn.getBlockZ(), maxZ = mx.getBlockZ();
        int minY = Math.max(world.getMinHeight(), mn.getBlockY());
        int maxY = Math.min(world.getMaxHeight() - 1, mx.getBlockY());
        List<Entity> list = new ArrayList<>(16);
        // Бюджет частиц по КАЖДОМУ краю (а не общий): у длинных границ раньше
        // хватало первых сторон, края 3-4 не дорисовывались вовсе.
        int budget = Math.max(1000, h.particles.maxPoints);
        Set<Material> ignore = h.territoryIgnore;
        // Стороны, параллельные X (z фиксирован).
        picketEdge(world, list, f, minX, maxX, minY, maxY, minZ, true, budget, ignore);
        picketEdge(world, list, f, minX, maxX, minY, maxY, maxZ, true, budget, ignore);
        // Стороны, параллельные Z (x фиксирован).
        picketEdge(world, list, f, minZ, maxZ, minY, maxY, minX, false, budget, ignore);
        picketEdge(world, list, f, minZ, maxZ, minY, maxY, maxX, false, budget, ignore);
        return list;
    }

    /**
     * Один край региона. При alongX=true колонки идут по X при фиксированном
     * fixed=Z; блок получает scale (width, height, thickness). При alongX=false
     * колонки идут по Z при фиксированном fixed=X; scale (thickness, height, width).
     * Штакетина ставится на КАЖДУЮ высоту колонки, где есть рельеф (не воздух
     * и не игнорируемый блок): дерево или структура в несколько слоёв больше
     * не создаёт «пустого разрыва» у земли — забор идёт по всему рельефу
     * границы, от земли до верха.
     */
    private void picketEdge(World world, List<Entity> list, Config.TerrainFenceOptions f,
                            int lo, int hi, int minY, int maxY, int fixed, boolean alongX,
                            int budget, Set<Material> ignore) {
        double step = f.spacing;
        int made = 0;
        for (double pos = lo; pos <= hi + 1e-6 && made < budget; pos += step) {
            int col = Math.max(lo, Math.min(hi, (int) Math.round(pos)));
            int x = alongX ? col : fixed;
            int z = alongX ? fixed : col;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            double cx = alongX ? pos + 0.5 : fixed + 0.5;
            double cz = alongX ? fixed + 0.5 : pos + 0.5;
            Vector3f scale = alongX
                    ? new Vector3f((float) f.width, (float) f.height, (float) f.thickness)
                    : new Vector3f((float) f.thickness, (float) f.height, (float) f.width);
            // КАЖДАЯ высота колонки с рельефом получает штакетину.
            for (int y = maxY; y >= minY && made < budget; y--) {
                if (!isTerrain(world.getBlockAt(x, y, z).getType(), ignore)) {
                    continue;
                }
                BlockDisplay d = spawnDisplay(world, cx, y + f.height / 2.0 + f.offset, cz,
                        f.material.createBlockData(), scale,
                        f.glow ? plugin.config().highlight().particles.dustColor : null);
                if (d != null) {
                    list.add(d);
                    made++;
                }
            }
        }
    }

    /** true, если блок — рельеф (не воздух и не в списке ignore). */
    private static boolean isTerrain(Material type, Set<Material> ignore) {
        if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
            return false;
        }
        return ignore.isEmpty() || !ignore.contains(type);
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

    /** Тип подсветки для региона: из флага territory-type региона, иначе тип по умолчанию. */
    private String typeFor(World world, ProtectedRegion r) {
        String t = plugin.wg().territoryTypeOf(world, r);
        return (t == null || t.isEmpty()) ? plugin.config().highlight().type : t;
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

    /** Загрузился чанк — кэш территории мира устарел (могут появиться новые столбцы). */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (terrainCache.isEmpty()) {
            return;
        }
        String prefix = e.getWorld().getName() + ":";
        Iterator<Map.Entry<String, TerrainEntry>> it = terrainCache.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey().startsWith(prefix)) {
                it.remove();
            }
        }
    }

    // ---------- утилиты ----------

    private static String key(World world, ProtectedRegion r) {
        return world.getName() + ":" + r.getId();
    }

    private static boolean isBlocks(String type) {
        return "BLOCKS".equals(type);
    }

    /** TERRITORY отображается «забором» (highlight.territory.display: BLOCKS). */
    private boolean isFence(String type) {
        return "TERRITORY".equals(type)
                && "BLOCKS".equals(plugin.config().highlight().terrainDisplay);
    }

    private static String normalizeType(String type) {
        if (type != null && "TERRITORY".equalsIgnoreCase(type)) {
            return "TERRITORY";
        }
        return type != null && "BLOCKS".equalsIgnoreCase(type) ? "BLOCKS" : "PARTICLES";
    }
}