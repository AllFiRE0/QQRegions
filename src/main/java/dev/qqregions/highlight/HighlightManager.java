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

    /** Позиция и параметры одной «штакетины» забора (для сравнения при резкане). */
    private static final class Picket {
        final double x, y, z;
        final org.bukkit.block.data.BlockData data;
        final Vector3f scale;
        final Color glow;

        Picket(double x, double y, double z, org.bukkit.block.data.BlockData data,
               Vector3f scale, Color glow) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.data = data;
            this.scale = scale;
            this.glow = glow;
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
                        resyncTerrainFence(p, w, r, e.getKey());
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
            // Только at-регионы (ПОД игроком): по этому набору считается ВЫХОД.
            java.util.Set<String> underNow = new java.util.HashSet<>();
            List<ProtectedRegion> under = new ArrayList<>();
            // 1) Чужие/по флагу: регионы ПОД игроком, где territory-visible ALLOW.
            for (ProtectedRegion r : plugin.wg().at(p.getWorld(), p.getLocation())) {
                if (!plugin.wg().territoryVisibleAllows(p.getWorld(), r, p)) {
                    continue;
                }
                String k = key(p.getWorld(), r);
                underNow.add(k);
                if (cur.add(k)) {
                    under.add(r);
                }
            }
            // 2) «Свои» (владелец/участник): АВТО в радиусе auto-show-radius.
            //    Флаг требуется ВСЕМ одинаково — свои подсвечиваются только
            //    там, где territory-visible ALLOW: соседний регион без флага
            //    не загорится ни при полёте рядом, ни при входе/выходе из региона
            //    с флагом (границы не имеют значения).
            List<ProtectedRegion> near = ownRegionsAround(p);
            for (ProtectedRegion r : near) {
                if (!plugin.wg().territoryVisibleAllows(p.getWorld(), r, p)) {
                    continue;
                }
                cur.add(key(p.getWorld(), r));
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
            // ВЫХОД: регион исчез ИЗ-ПОД игрока (набор underNow, а НЕ общий cur).
            // Раньше здесь был cur, в который входил и радиус auto-show-radius:
            // свой регион в радиусе 16 блоков «удерживался» как бы под игроком,
            // из-за чего exit-подсветка владельца зажигалась только ЗА 16+ блоков
            // от реальной границы. У чужих регионов радиус не учитывается вообще,
            // поэтому белый забор всегда гас/зажигался мгновенно на самой границе.
            if (was != null) {
                for (String k : was) {
                    if (underNow.contains(k)) {
                        continue;
                    }
                    next.remove(k);
                    if (h.showOnExit) {
                        if (!onCooldown(p, k, now)) {
                            markCooldown(p, k, now);
                            showOnExit(p, k);
                        }
                    } else {
                        cooldownRemove(p, k);
                        if (h.hideOnExit && isActive(p, k)) {
                            removeActive(p, k);
                        }
                    }
                }
            }
            // 2а) Свои рядом (радиус auto-show-radius), но НЕ под игроком: если
            //     подсветка уже погасла (region-hide-seconds) — показать заново
            //     (раз в cooldown-секунд), чтобы владелец видел границы со стороны.
            //     Это не «вход» в регион и на exit-семантику выше не влияет.
            for (ProtectedRegion r : near) {
                String k = key(p.getWorld(), r);
                if (underNow.contains(k)) {
                    continue;
                }
                if (!plugin.wg().territoryVisibleAllows(p.getWorld(), r, p)) {
                    continue;
                }
                if (next.contains(k) && isActive(p, k)) {
                    continue;
                }
                if (onCooldown(p, k, now)) {
                    continue;
                }
                markCooldown(p, k, now);
                next.add(k);
                show(p, p.getWorld(), r, typeFor(p.getWorld(), r));
            }
            if (next.isEmpty()) {
                flagShown.remove(p.getUniqueId());
            } else {
                flagShown.put(p.getUniqueId(), next);
            }
        }
    }

    /** Свои регионы (владелец/участник) в радиусе auto-show-radius вокруг игрока. */
    private List<ProtectedRegion> ownRegionsAround(Player p) {
        int r = plugin.config().highlight().autoShowRadius;
        if (r <= 0) {
            return List.of();
        }
        World w = p.getWorld();
        org.bukkit.Location l = p.getLocation();
        int bx = l.getBlockX();
        int bz = l.getBlockZ();
        BlockVector3 mn = BlockVector3.at(bx - r, w.getMinHeight(), bz - r);
        BlockVector3 mx = BlockVector3.at(bx + r, w.getMaxHeight() - 1, bz + r);
        List<ProtectedRegion> out = new ArrayList<>();
        UUID id = p.getUniqueId();
        for (ProtectedRegion reg : plugin.wg().regionsInCube(w, mn, mx)) {
            if (plugin.wg().isOwner(reg, id) || plugin.wg().isMember(reg, id)) {
                out.add(reg);
            }
        }
        return out;
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

    /**
     * Показать подсветку при выходе из региона (exit-семантика WorldGuard).
     * В key зашит world:id, поэтому регион доставляется из него, без
     * необходимости иметь объект World на руках.
     */
    private void showOnExit(Player p, String k) {
        int colon = k.indexOf(':');
        if (colon <= 0 || colon == k.length() - 1) {
            return;
        }
        String worldName = k.substring(0, colon);
        String regionId = k.substring(colon + 1);
        World world = p.getWorld();
        if (world == null || !world.getName().equals(worldName)) {
            return;
        }
        ProtectedRegion r = plugin.wg().byName(world, regionId);
        if (r == null || !plugin.wg().territoryVisibleAllows(world, r, p)) {
            return;
        }
        show(p, world, r, typeFor(world, r));
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
     * (terrainPoints/fencePickets).
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
                ? spawnPickets(world, fencePickets(world, r, h, p))
                : boxBlocks(world, r, h);
        perPlayer.put(key, list);
    }

    /**
     * Пересобрать «забор» активной TERRITORY-подсветки, НЕ трогая дисплеи,
     * если набор штакетин не изменился (позиции те же). Сравнение по позициям
     * (округление до 1 мм) вместо слепого «удалить-и-заново»: иначе каждый
     * резкан раз в terrain-cache-seconds давал бы видимое мигание забора.
     */
    private void resyncTerrainFence(Player p, World w, ProtectedRegion r, String key) {
        Config.HighlightOptions h = plugin.config().highlight();
        List<Picket> fresh = fencePickets(w, r, h, p);
        Map<String, List<Entity>> perPlayer = blockViews.get(p.getUniqueId());
        List<Entity> old = perPlayer == null ? null : perPlayer.get(key);
        if (picketsEqual(old, fresh)) {
            return;
        }
        if (old != null) {
            perPlayer.remove(key);
            for (Entity e : old) {
                e.remove();
            }
        }
        List<Entity> list = spawnPickets(w, fresh);
        blockViews.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(key, list);
    }

    /** true — текущие дисплеи и новый набор штакетин занимают те же позиции. */
    private static boolean picketsEqual(List<Entity> old, List<Picket> fresh) {
        if (old == null || old.size() != fresh.size()) {
            return false;
        }
        Set<String> have = new HashSet<>(old.size());
        for (Entity e : old) {
            have.add(posKey(e.getLocation()));
        }
        for (Picket pt : fresh) {
            have.remove(posKey(pt));
        }
        return have.isEmpty();
    }

    private static String posKey(Location loc) {
        return posKey(loc.getX(), loc.getY(), loc.getZ());
    }

    private static String posKey(Picket pt) {
        return posKey(pt.x, pt.y, pt.z);
    }

    /** Ключ позиции с точностью 1 мм (координаты могут быть огромными — строка). */
    private static String posKey(double x, double y, double z) {
        return Math.round(x * 1000) + "|" + Math.round(y * 1000) + "|" + Math.round(z * 1000);
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
     * offset. Штакетина ставится на КАЖДЫЙ видимый верх пласта колонки
     * границы (блок рельефа, над которым уже не рельеф) и поднимается ровно
     * на поверхность: многослойная граница (дерево, постройка в несколько
     * этажей) получает забор на каждом слое, а ровная земля — сплошную
     * линию забора по всей длине кромки.
     * Позиции возвращаются отдельно от спавна (fencePickets -> spawnPickets),
     * чтобы при периодическом резкане сравнивать с уже стоящими дисплеями и
     * НЕ пересоздавать неизменившийся забор (иначе он видимо мигает).
     */
    private List<Picket> fencePickets(World world, ProtectedRegion r, Config.HighlightOptions h, Player p) {
        Config.TerrainFenceOptions f = h.fence;
        // Цвет забора по роли ИГРОКА: владелец/участник/чужой — свой материал
        // (highlight.territory.fence.owner/member/foreign). У каждого игрока
        // свои дисплеи, поэтому роль читается именно на его перспективу.
        Config.TerrainFenceOptions.FenceRoleStyle role = f.forRelation(
                p != null && plugin.wg().isOwner(r, p.getUniqueId()),
                p != null && plugin.wg().isMember(r, p.getUniqueId()));
        Material roleMat = role.material;
        Color roleGlow = role.glow ? plugin.config().highlight().particles.dustColor : null;
        BlockVector3 mn = r.getMinimumPoint();
        BlockVector3 mx = r.getMaximumPoint();
        int minX = mn.getBlockX(), maxX = mx.getBlockX();
        int minZ = mn.getBlockZ(), maxZ = mx.getBlockZ();
        int minY = Math.max(world.getMinHeight(), mn.getBlockY());
        int maxY = Math.min(world.getMaxHeight() - 1, mx.getBlockY());
        List<Picket> list = new ArrayList<>(16);
        // Бюджет частиц по КАЖДОМУ краю (а не общий): у длинных границ раньше
        // хватало первых сторон, края 3-4 не дорисовывались вовсе.
        int budget = Math.max(1000, h.particles.maxPoints);
        Set<Material> ignore = h.territoryIgnore;
        // Стороны, параллельные X (z фиксирован): наружу региона = -Z/+Z.
        picketEdge(world, list, f, roleMat, roleGlow, minX, maxX, minY, maxY, minZ, true, -1, budget, ignore);
        picketEdge(world, list, f, roleMat, roleGlow, minX, maxX, minY, maxY, maxZ, true, +1, budget, ignore);
        // Стороны, параллельные Z (x фиксирован): наружу региона = -X/+X.
        picketEdge(world, list, f, roleMat, roleGlow, minZ, maxZ, minY, maxY, minX, false, -1, budget, ignore);
        picketEdge(world, list, f, roleMat, roleGlow, minZ, maxZ, minY, maxY, maxX, false, +1, budget, ignore);
        // Углы: каждая сторона ставит свой дисплей в один и тот же угловой блок
        // (X-грань и Z-грань сходятся в центр угла) — оставляем ОДИН штакет на
        // угол, иначе в 4 углах стоят сдвоенные «кресты», ломающие сетку по
        // центрам блоков. По позициям (1 мм) дедупликация не трогает соседей.
        Set<String> seen = new HashSet<>(list.size());
        List<Picket> dedup = new ArrayList<>(list.size());
        for (Picket pt : list) {
            if (seen.add(posKey(pt))) {
                dedup.add(pt);
            }
        }
        if (plugin.config().debug()) {
            StringBuilder sb = new StringBuilder();
            sb.append("[territory-fence] ПАРАМЕТРЫ: material=").append(roleMat.name())
                    .append(" glow=").append(roleGlow != null)
                    .append(" h=").append(f.height).append(" w=").append(f.width)
                    .append(" t=").append(f.thickness).append(" spacing=").append(f.spacing)
                    .append(" offset=").append(f.offset)
                    .append(" along=").append(f.alongOffset)
                    .append(" across=").append(f.acrossOffset)
                    .append(" бюджет=").append(budget);
            plugin.getLogger().info(sb.toString());
            plugin.getLogger().info("[territory-fence] регион x=" + minX + ".." + maxX
                    + " z=" + minZ + ".." + maxZ
                    + " всего=" + dedup.size() + " (до дедупа=" + list.size() + ")");
            int n = Math.min(8, dedup.size());
            for (int i = 0; i < n; i++) {
                Picket pt = dedup.get(i);
                plugin.getLogger().info("[territory-fence] штакетина#" + i
                        + " x=" + String.format(java.util.Locale.ROOT, "%.3f", pt.x)
                        + " y=" + String.format(java.util.Locale.ROOT, "%.3f", pt.y)
                        + " z=" + String.format(java.util.Locale.ROOT, "%.3f", pt.z));
            }
        }
        return dedup;
    }

    private List<Entity> spawnPickets(World world, List<Picket> pickets) {
        List<Entity> list = new ArrayList<>(pickets.size());
        for (Picket pt : pickets) {
            BlockDisplay d = spawnDisplay(world, pt.x, pt.y, pt.z, pt.data, pt.scale, pt.glow);
            if (d != null) {
                list.add(d);
            }
        }
        return list;
    }

    /**
     * Один край региона. При alongX=true колонки идут по X при фиксированном
     * fixed=Z; блок получает scale (width, height, thickness). При alongX=false
     * колонки идут по Z при фиксированном fixed=X; scale (thickness, height, width).
     * Штакетина ставится на КАЖДЫЙ видимый верх пласта колонки (блок рельефа,
     * над которым уже не рельеф) и ПОДНИМАЕТСЯ ровно на поверхность (ячейка
     * выше топ-блока), где её не закрывают соседние твёрдые блоки. Раньше
     * штакетина писалась по центру самого блока рельефа -> на ровной границе
     * она тонула внутри земли и была невидима; вдобавок заглубленные слои
     * съедали весь бюджет и грани обрывались уже у углов.
     * outwardSign: +1 — «наружу» региона здесь = рост координаты (maxX/maxZ),
     * -1 — наружу = падение координаты (minX/minZ). Нужен для across-offset.
     */
    private void picketEdge(World world, List<Picket> out, Config.TerrainFenceOptions f,
                            Material picketMat, Color glow,
                            int lo, int hi, int minY, int maxY, int fixed, boolean alongX,
                            int outwardSign, int budget, Set<Material> ignore) {
        double step = f.spacing;
        int made = 0;
        int cols = 0;
        int colsTerrain = 0;
        boolean dbg = plugin.config().debug();
        for (double pos = lo; pos <= hi + 1e-6 && made < budget; pos += step) {
            int col = Math.max(lo, Math.min(hi, (int) Math.round(pos)));
            int x = alongX ? col : fixed;
            int z = alongX ? fixed : col;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            cols++;
            // Позиция в ячейке: по умолчанию центр колонки (X) и центр колонки (Z);
            // along-offset двигает ВДОЛЬ края границы, across-offset — поперёк
            // (в сторону outwardSign), чтобы забор можно было выставить по центру
            // блока, заподлицо с внешней гранью региона или целиком снаружи.
            // BlockDisplay растёт от точки спавна в +X и +Z (в Y — от низа), поэтому
            // из расчётного центра вычитаем ПОЛОВИНУ протяжённости по обеим осям:
            // иначе панель «прилипает» к спавну правым/южным краем и весь забор
            // визуально съезжает как целое (на max-сторонах — вовсе за стену).
            double cx = (alongX ? pos + 0.5 + f.alongOffset - f.width / 2.0
                                : fixed + 0.5 + outwardSign * f.acrossOffset - f.thickness / 2.0);
            double cz = (alongX ? fixed + 0.5 + outwardSign * f.acrossOffset - f.thickness / 2.0
                                : pos + 0.5 + f.alongOffset - f.width / 2.0);
            Vector3f scale = alongX
                    ? new Vector3f((float) f.width, (float) f.height, (float) f.thickness)
                    : new Vector3f((float) f.thickness, (float) f.height, (float) f.width);
            // Только ВЕРХИ пластов: блок — опора забора, а над ним уже не опора.
            // Заглублённые слои пропускаем — они всё равно невидимы, а бюджет
            // тратят (раньше первые колонки от угла съедали весь лимит края).
            for (int y = maxY; y >= minY && made < budget; y--) {
                if (!isFenceTerrain(world.getBlockAt(x, y, z).getType(), ignore)) {
                    continue;
                }
                Material above = world.getBlockAt(x, y + 1, z).getType();
                if (isFenceTerrain(above, ignore)) {
                    continue;
                }
                colsTerrain++;
                out.add(new Picket(cx,
                        y + 1 + f.height / 2.0 + f.offset, cz,
                        picketMat.createBlockData(), scale, glow));
                made++;
            }
        }
        if (dbg) {
            plugin.getLogger().info("[territory-fence] alongX=" + alongX + " fixed=" + fixed
                    + " lo=" + lo + " hi=" + hi + " колонок=" + cols
                    + " сРельефом=" + colsTerrain + " штакетин=" + made
                    + " лимит=" + budget);
        }
    }

    /** true, если блок — рельеф (не воздух и не в списке ignore). */
    private static boolean isTerrain(Material type, Set<Material> ignore) {
        if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
            return false;
        }
        return ignore.isEmpty() || !ignore.contains(type);
    }

    /**
     * Опора ЗАБОРА (только для TERRITORY+BLOCKS): блок считается опорой только
     * если он ПОЛНЫЙ и НЕПРОЗРАЧНЫЙ (Material.isOccluding), не воздух и не в
     * списке ignore. Листья, стёкла, плиты, ступени, заборы, рельсы, люки и всё
     * прочее прозрачное/неполное опорой не являются — забор встаёт ПОД листвой
     * (на ствол/землю), а не сверху кроны, и не стоит на стёклах/плитах.
     * Для particles/particle-рельефа (addColumnLevels) продолжает работать
     * обычный isTerrain — там ignore-blocks просто отсекают декор.
     */
    private static boolean isFenceTerrain(Material type, Set<Material> ignore) {
        if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
            return false;
        }
        if (!ignore.isEmpty() && ignore.contains(type)) {
            return false;
        }
        return type.isOccluding();
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