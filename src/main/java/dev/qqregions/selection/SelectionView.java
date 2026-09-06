package dev.qqregions.selection;

import com.sk89q.worldedit.math.BlockVector3;
import dev.qqregions.QQRegions;
import dev.qqregions.config.Config;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Общий рендер выделения игрока: view-mode PARTICLES (частицы по рёбрам)
 * или BLOCKS (BlockDisplay со свечением и дистанционной фильтрацией).
 * Используется интерактивной сессией (/region select) и обычными командами
 * выделения (pos, chunk, expand, ...) через SelectionManager.
 * Цвет, материал и маркер активной точки задаёт вызывающий код.
 */
public class SelectionView {

    private final QQRegions plugin;
    private final Player player;
    private final Map<Long, Entity> viewBlocks = new HashMap<>();
    private Entity viewMarker;
    private Entity viewMarker2;
    private int timer = 0;

    public SelectionView(QQRegions plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    /** Троттлинг-обновление (вызывается каждый тик плагина). */
    public void update(Selection sel, Color color, Material blockMat, BlockVector3 marker) {
        timer += 5;
        if (timer < plugin.config().particles().updateTicks) {
            return;
        }
        timer = 0;
        renderNow(sel, color, blockMat, marker);
    }

    /** Немедленный рендер (без троттлинга) — для мгновенного отклика. */
    public void renderNow(Selection sel, Color color, Material blockMat, BlockVector3 marker) {
        timer = plugin.config().particles().updateTicks;
        if (plugin.config().blockView()) {
            renderBlockView(sel, color, blockMat, marker);
        } else {
            renderParticles(sel, color, marker);
        }
    }

    /**
     * Рендер в select-режиме: контур объёма — цветом активной точки,
     * маркеры ОБЕИХ точек всегда видны и каждый своим цветом (точка 1 —
     * серый, точка 2 — оранжевый), чтобы при переключении не терять соседа.
     */
    public void renderSelect(Selection sel, Config.PointStyle p1, Config.PointStyle p2, int activePoint) {
        Config cfg = plugin.config();
        timer = cfg.particles().updateTicks;
        Config.PointStyle act = activePoint == 1 ? p1 : p2;
        Config.PointStyle oth = activePoint == 1 ? p2 : p1;
        BlockVector3 actPos = sel.getPos(activePoint);
        BlockVector3 othPos = sel.getPos(activePoint == 1 ? 2 : 1);
        if (cfg.blockView()) {
            renderBlockView(sel, act.highlight, act.block, actPos);
            updateOtherMarker(sel.getWorld(), oth.highlight, oth.block, othPos);
        } else {
            renderParticles(sel, act.highlight, null);
            Config.ParticleOptions po = cfg.particles();
            markerCube(sel.getWorld(), po, p1.highlight, sel.getPos(1));
            markerCube(sel.getWorld(), po, p2.highlight, sel.getPos(2));
        }
    }

    /** Удаляет все спавненные сущности (выход из сессии / смена режима). */
    public void cleanup() {
        for (Entity e : viewBlocks.values()) {
            e.remove();
        }
        viewBlocks.clear();
        if (viewMarker != null) {
            viewMarker.remove();
            viewMarker = null;
        }
        if (viewMarker2 != null) {
            viewMarker2.remove();
            viewMarker2 = null;
        }
        timer = 0;
    }

    // ---------- PARTICLES ----------

    private void renderParticles(Selection sel, Color color, BlockVector3 marker) {
        Config.ParticleOptions po = plugin.config().particles();
        if (!po.enabled) {
            return;
        }
        World world = sel.getWorld();
        double limitSq = (double) plugin.config().viewDistance() * plugin.config().viewDistance();
        Location eye = player.getLocation();
        for (BlockVector3 p : edgePoints(sel, po.density, po.maxPoints)) {
            double dx = p.getBlockX() + 0.5 - eye.getX();
            double dy = p.getBlockY() + 0.5 - eye.getY();
            double dz = p.getBlockZ() + 0.5 - eye.getZ();
            if (dx * dx + dy * dy + dz * dz > limitSq) {
                continue;
            }
            spawnParticle(world, po, color, p.getBlockX() + 0.5, p.getBlockY() + 0.5, p.getBlockZ() + 0.5);
        }
        if (marker != null) {
            markerCube(world, po, color, marker);
        }
    }

    /** Кубик-маркер точки: 8 частиц по углам блока. */
    private void markerCube(World world, Config.ParticleOptions po, Color color, BlockVector3 marker) {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    spawnParticle(world, po, color,
                            marker.getBlockX() + dx, marker.getBlockY() + dy, marker.getBlockZ() + dz);
                }
            }
        }
    }

    private void spawnParticle(World world, Config.ParticleOptions po, Color color, double x, double y, double z) {
        Particle particle;
        try {
            particle = Particle.valueOf(po.particleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            particle = Particle.DUST;
        }
        Object data = null;
        if (particle == Particle.DUST) {
            data = new Particle.DustOptions(color, po.dustSize);
        }
        world.spawnParticle(particle, x, y, z, po.amount, 0.0, 0.0, 0.0, po.speed, data);
    }

    // ---------- BLOCKS ----------

    private void renderBlockView(Selection sel, Color color, Material blockMat, BlockVector3 marker) {
        Config cfg = plugin.config();
        World world = sel.getWorld();
        double limitSq = (double) cfg.viewDistance() * cfg.viewDistance();
        int maxBlocks = cfg.viewMaxBlocks();
        Location eye = player.getLocation();

        List<BlockVector3> inRange = new ArrayList<>();
        Set<Long> wanted = new HashSet<>();
        for (BlockVector3 p : edgePoints(sel, cfg.particles().density, maxBlocks)) {
            if (inRange.size() >= maxBlocks) {
                break;
            }
            double dx = p.getBlockX() + 0.5 - eye.getX();
            double dy = p.getBlockY() + 0.5 - eye.getY();
            double dz = p.getBlockZ() + 0.5 - eye.getZ();
            if (dx * dx + dy * dy + dz * dz <= limitSq) {
                wanted.add(blockKey(p));
                inRange.add(p);
            }
        }

        viewBlocks.entrySet().removeIf(e -> {
            if (!wanted.contains(e.getKey())) {
                e.getValue().remove();
                return true;
            }
            return false;
        });

        for (BlockVector3 p : inRange) {
            long key = blockKey(p);
            if (viewBlocks.containsKey(key) || viewBlocks.size() >= maxBlocks) {
                continue;
            }
            viewBlocks.put(key, spawnViewBlock(world, p, blockMat, color, cfg.viewBlockScale()));
        }

        for (Entity e : viewBlocks.values()) {
            if (e instanceof BlockDisplay bd) {
                bd.setBlock(blockMat.createBlockData());
                bd.setGlowColorOverride(color);
            }
        }

        if (marker == null) {
            if (viewMarker != null) {
                viewMarker.remove();
                viewMarker = null;
            }
            if (viewMarker2 != null) {
                viewMarker2.remove();
                viewMarker2 = null;
            }
        } else if (viewMarker != null && viewMarker.isValid()) {
            viewMarker.teleport(displayLoc(world, marker));
            if (viewMarker instanceof BlockDisplay bd) {
                bd.setBlock(blockMat.createBlockData());
                bd.setGlowColorOverride(color);
            }
        } else {
            viewMarker = spawnViewBlock(world, marker, blockMat, color,
                    Math.min(1.0f, cfg.viewBlockScale() * 2.0f));
        }
    }

    /** Маркер второй (неактивной) точки в BLOCKS-режиме — свой цвет и блок. */
    private void updateOtherMarker(World world, Color color, Material blockMat, BlockVector3 pos) {
        if (viewMarker2 != null && viewMarker2.isValid()) {
            viewMarker2.teleport(displayLoc(world, pos));
            if (viewMarker2 instanceof BlockDisplay bd) {
                bd.setBlock(blockMat.createBlockData());
                bd.setGlowColorOverride(color);
            }
        } else {
            viewMarker2 = spawnViewBlock(world, pos, blockMat, color,
                    plugin.config().viewBlockScale());
        }
    }

    private BlockDisplay spawnViewBlock(World world, BlockVector3 p, Material mat, Color glow, float scale) {
        BlockDisplay d = world.spawn(displayLoc(world, p), BlockDisplay.class);
        d.setBlock(mat.createBlockData());
        d.setTransformation(new Transformation(
                new Vector3f(), new Quaternionf(),
                new Vector3f(scale, scale, scale),
                new Quaternionf()));
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(0);
        d.setGlowColorOverride(glow);
        d.setInvulnerable(true);
        return d;
    }

    // ---------- утилиты ----------

    private static Location displayLoc(World world, BlockVector3 p) {
        return new Location(world, p.getBlockX() + 0.5, p.getBlockY() + 0.5, p.getBlockZ() + 0.5);
    }

    private static long blockKey(BlockVector3 p) {
        return ((long) (p.getBlockX() + 30000000) << 42)
                | ((long) (p.getBlockY() + 1024) << 21)
                | (p.getBlockZ() + 30000000);
    }

    /**
     * Точки по всем 12 рёбрам куба. Каждое ребро рисуется СПЛОШНОЙ полосой
     * (без чередования): ни одна линия не обрывается в середине. Бюджет cap
     * делится поровну на ребро (cap/12) — короткие рёбра (в т.ч. вертикали)
     * идут каждым блоком, длинные ужимаются до своей доли, поэтому весь
     * объём влезает в бюджет целиком.
     */
    private static List<BlockVector3> edgePoints(Selection sel, int density, int maxPoints) {
        int cap = Math.max(24, maxPoints > 0 ? maxPoints : 24);
        int base = Math.max(1, density);
        BlockVector3 mn = sel.min();
        BlockVector3 mx = sel.max();
        int sx = Math.abs(mx.getBlockX() - mn.getBlockX());
        int sy = Math.abs(mx.getBlockY() - mn.getBlockY());
        int sz = Math.abs(mx.getBlockZ() - mn.getBlockZ());

        int perEdge = Math.max(2, cap / 12);
        int need = perEdge - 1;
        int stX = strideFor(sx, need, base);
        int stY = strideFor(sy, need, base);
        int stZ = strideFor(sz, need, base);

        Edge[] edges = new Edge[]{
                edge(mn, BlockVector3.at(mx.getBlockX(), mn.getBlockY(), mn.getBlockZ()), sx, stX),
                edge(mn, BlockVector3.at(mn.getBlockX(), mn.getBlockY(), mx.getBlockZ()), sz, stZ),
                edge(BlockVector3.at(mx.getBlockX(), mn.getBlockY(), mn.getBlockZ()), BlockVector3.at(mx.getBlockX(), mn.getBlockY(), mx.getBlockZ()), sz, stZ),
                edge(BlockVector3.at(mn.getBlockX(), mn.getBlockY(), mx.getBlockZ()), BlockVector3.at(mx.getBlockX(), mn.getBlockY(), mx.getBlockZ()), sx, stX),
                edge(BlockVector3.at(mn.getBlockX(), mx.getBlockY(), mn.getBlockZ()), BlockVector3.at(mx.getBlockX(), mx.getBlockY(), mn.getBlockZ()), sx, stX),
                edge(BlockVector3.at(mn.getBlockX(), mx.getBlockY(), mn.getBlockZ()), BlockVector3.at(mn.getBlockX(), mx.getBlockY(), mx.getBlockZ()), sz, stZ),
                edge(BlockVector3.at(mx.getBlockX(), mx.getBlockY(), mn.getBlockZ()), BlockVector3.at(mx.getBlockX(), mx.getBlockY(), mx.getBlockZ()), sz, stZ),
                edge(BlockVector3.at(mn.getBlockX(), mx.getBlockY(), mx.getBlockZ()), BlockVector3.at(mx.getBlockX(), mx.getBlockY(), mx.getBlockZ()), sx, stX),
                edge(mn, BlockVector3.at(mn.getBlockX(), mx.getBlockY(), mn.getBlockZ()), sy, stY),
                edge(BlockVector3.at(mx.getBlockX(), mn.getBlockY(), mn.getBlockZ()), BlockVector3.at(mx.getBlockX(), mx.getBlockY(), mn.getBlockZ()), sy, stY),
                edge(BlockVector3.at(mn.getBlockX(), mn.getBlockY(), mx.getBlockZ()), BlockVector3.at(mn.getBlockX(), mx.getBlockY(), mx.getBlockZ()), sy, stY),
                edge(BlockVector3.at(mx.getBlockX(), mn.getBlockY(), mx.getBlockZ()), BlockVector3.at(mx.getBlockX(), mx.getBlockY(), mx.getBlockZ()), sy, stY),
        };

        List<BlockVector3> out = new ArrayList<>(Math.min(cap + 8, 4096));
        Set<Long> seen = new HashSet<>();
        for (Edge e : edges) {
            while (e.hasNext() && out.size() < cap) {
                BlockVector3 p = e.next();
                if (seen.add(blockKey(p))) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /**
     * Доля бюджета для ребра длины len: короткое ребро (len <= need) рисуется
     * КАЖДЫМ блоком (шаг 1), длинное ужимается до ~need точек (не чаще base).
     */
    private static int strideFor(int len, int need, int base) {
        if (len <= need) {
            return 1;
        }
        return Math.max(base, (len + need - 1) / need);
    }

    /** Ленивое ребро: точки через {stride} блоков от a к b (включая конец). */
    private static Edge edge(BlockVector3 a, BlockVector3 b, int len, int stride) {
        return new Edge(a, b, len, stride);
    }

    private static final class Edge {
        final BlockVector3 a;
        final int dx, dy, dz;
        final int len;
        final int stride;
        int cur = 0;

        Edge(BlockVector3 a, BlockVector3 b, int len, int stride) {
            this.a = a;
            this.dx = b.getBlockX() - a.getBlockX();
            this.dy = b.getBlockY() - a.getBlockY();
            this.dz = b.getBlockZ() - a.getBlockZ();
            this.len = len;
            this.stride = stride;
        }

        boolean hasNext() {
            return cur <= len;
        }

        BlockVector3 next() {
            int t = Math.min(cur, len);
            cur += stride;
            return BlockVector3.at(
                    t == 0 ? a.getBlockX() : a.getBlockX() + dx * t / Math.max(1, len),
                    t == 0 ? a.getBlockY() : a.getBlockY() + dy * t / Math.max(1, len),
                    t == 0 ? a.getBlockZ() : a.getBlockZ() + dz * t / Math.max(1, len));
        }
    }
}