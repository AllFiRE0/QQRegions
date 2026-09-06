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
    /** Точки-кубики контура: ключ = позиция блока, значение = дисплей. */
    private final Map<BlockVector3, Entity> viewBlocks = new HashMap<>();
    private Entity viewMarker;
    private Entity viewMarker2;
    private int timer = 0;
    /** Отпечаток последнего кадра: если не изменился — рендер пропускается
     * (никакого спама частиц/дисплеев и debug-лога в покое). */
    private String lastFp = "";

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

    /**
     * Немедленный рендер (без троттлинга) — для мгновенного отклика.
     * Пропускает работу, если выделение и маркер не изменились.
     */
    public void renderNow(Selection sel, Color color, Material blockMat, BlockVector3 marker) {
        timer = plugin.config().particles().updateTicks;
        if (sel == null) {
            lastFp = "";
            return;
        }
        String fp = fp(sel, marker, color, blockMat, false);
        if (fp.equals(lastFp)) {
            return;
        }
        lastFp = fp;
        if (plugin.config().blockView()) {
            renderBlockView(sel, color, blockMat, marker);
        } else {
            renderParticles(sel, color, marker);
        }
    }

    private static String fp(Selection sel, BlockVector3 marker, Color color, Material mat, boolean select) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(sel.getWorld().getName()).append('|')
                .append(sel.min().blockX()).append(',').append(sel.min().blockY()).append(',').append(sel.min().blockZ()).append('|')
                .append(sel.max().blockX()).append(',').append(sel.max().blockY()).append(',').append(sel.max().blockZ());
        if (marker != null) {
            sb.append('|').append(marker.blockX()).append(',').append(marker.blockY()).append(',').append(marker.blockZ());
        }
        if (color != null) {
            sb.append('|').append(color.asRGB());
        }
        if (mat != null) {
            sb.append('|').append(mat.name());
        }
        sb.append(select ? 'S' : 'R');
        return sb.toString();
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
        // select-режим спавнит маркеры каждые 5 тиков; пропускаем работу,
        // только если НИЧЕГО не менялось (обе точки, активная, цвета).
        String fp = fp(sel, othPos, oth.highlight, oth.block, true)
                + '|' + activePoint
                + '|' + actPos.blockX() + ',' + actPos.blockY() + ',' + actPos.blockZ()
                + '|' + act.highlight.asRGB();
        if (fp.equals(lastFp)) {
            return;
        }
        lastFp = fp;
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
        // Весь контур рисуется БЕЗ обрезания по дистанции: лимит точек уже
        // ограничивает нагрузку (max-points), а далёкие куски раньше
        // «отрывались» из-за view-distance.
        for (BlockVector3 p : edgePoints(sel, po.maxPoints)) {
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

        // Точки-кубики по всем 12 рёбрам: пунктир 20б -> 1·0·1, 40б -> 1·0·0·1
        // (у каждого ребра почти равное число точек, шаг растёт с длиной).
        // Бюджет точек на ОДНО ребро = view-dots-per-edge.
        List<BlockVector3> points = edgePoints(sel, Math.max(24, cfg.viewDotsPerEdge() * 12));

        List<BlockVector3> need = new ArrayList<>();
        List<BlockVector3> spare = new ArrayList<>();
        for (BlockVector3 p : points) {
            if (!viewBlocks.containsKey(p)) {
                need.add(p);
            }
        }
        Set<BlockVector3> wanted = new HashSet<>();
        for (BlockVector3 p : points) {
            wanted.add(p);
        }
        for (BlockVector3 key : new ArrayList<>(viewBlocks.keySet())) {
            if (!wanted.contains(key)) {
                spare.add(key);
            }
        }

        // Существующие дисплеи ТЕЛЕПОРТИРУЮТСЯ на новые места (а не
        // пересоздаются): кадр при движении точки плавно смещается целиком.
        int si = 0;
        boolean changed = false;
        for (BlockVector3 p : need) {
            Entity ent = null;
            while (si < spare.size()) {
                Entity c = viewBlocks.remove(spare.get(si++));
                if (c != null && c.isValid()) {
                    ent = c;
                    break;
                }
            }
            if (ent == null) {
                viewBlocks.put(p, spawnViewBlock(world, p, blockMat, color, cfg.viewBlockScale()));
            } else {
                ent.teleport(displayLoc(world, p));
                viewBlocks.put(p, ent);
            }
            changed = true;
        }
        for (; si < spare.size(); si++) {
            Entity removed = viewBlocks.remove(spare.get(si));
            if (removed != null) {
                removed.remove();
            }
            changed = true;
        }

        // Блок/свечение — только когда кадр реально менялся (не гоняем NBT
        // пакеты по всем дисплеям каждый тик в покое).
        if (changed) {
            for (Entity e : viewBlocks.values()) {
                if (e instanceof BlockDisplay bd) {
                    bd.setBlock(blockMat.createBlockData());
                    bd.setGlowColorOverride(color);
                }
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

    /**
     * Пунктир по всем 12 рёбрам по правилу «20б -> 1·0·1, 40б -> 1·0·0·1,
     * и т.д.»: у КАЖДОГО ребра почти равный бюджет точек (cap/12),
     * шаг = округление длины/бюджета — период растёт с длиной ребра,
     * короткие рёбра идут каждым блоком. Все 12 рёбер рисуются ЦЕЛИКОМ
     * (без общего потолка, который раньше отбирал точки у последних
     * вертикальных граней), поэтому вертикали не могут пропасть.
     * При debug: true в консоль выводится фактическое число точек каждого
     * ребра (низ 0-3, верх 4-7, вертикали 8-11) — проверка «вертикали =
     * горизонтали» по факту.
     */
    private List<BlockVector3> edgePoints(Selection sel, int maxPoints) {
        int cap = Math.max(24, maxPoints > 0 ? maxPoints : 24);
        BlockVector3 mn = sel.min();
        BlockVector3 mx = sel.max();
        int sx = Math.abs(mx.getX() - mn.getX());
        int sy = Math.abs(mx.getY() - mn.getY());
        int sz = Math.abs(mx.getZ() - mn.getZ());

        int perEdge = Math.max(2, cap / 12);
        int need = perEdge - 1;

        Edge[] edges = new Edge[]{
                edge(mn, BlockVector3.at(mx.getX(), mn.getY(), mn.getZ()), sx, strideFor(sx, need)),
                edge(mn, BlockVector3.at(mn.getX(), mn.getY(), mx.getZ()), sz, strideFor(sz, need)),
                edge(BlockVector3.at(mx.getX(), mn.getY(), mn.getZ()), BlockVector3.at(mx.getX(), mn.getY(), mx.getZ()), sz, strideFor(sz, need)),
                edge(BlockVector3.at(mn.getX(), mn.getY(), mx.getZ()), BlockVector3.at(mx.getX(), mn.getY(), mx.getZ()), sx, strideFor(sx, need)),
                edge(BlockVector3.at(mn.getX(), mx.getY(), mn.getZ()), BlockVector3.at(mx.getX(), mx.getY(), mn.getZ()), sx, strideFor(sx, need)),
                edge(BlockVector3.at(mn.getX(), mx.getY(), mn.getZ()), BlockVector3.at(mn.getX(), mx.getY(), mx.getZ()), sz, strideFor(sz, need)),
                edge(BlockVector3.at(mx.getX(), mx.getY(), mn.getZ()), BlockVector3.at(mx.getX(), mx.getY(), mx.getZ()), sz, strideFor(sz, need)),
                edge(BlockVector3.at(mn.getX(), mx.getY(), mx.getZ()), BlockVector3.at(mx.getX(), mx.getY(), mx.getZ()), sx, strideFor(sx, need)),
                edge(mn, BlockVector3.at(mn.getX(), mx.getY(), mn.getZ()), sy, strideFor(sy, need)),
                edge(BlockVector3.at(mx.getX(), mn.getY(), mn.getZ()), BlockVector3.at(mx.getX(), mx.getY(), mn.getZ()), sy, strideFor(sy, need)),
                edge(BlockVector3.at(mn.getX(), mn.getY(), mx.getZ()), BlockVector3.at(mn.getX(), mx.getY(), mx.getZ()), sy, strideFor(sy, need)),
                edge(BlockVector3.at(mx.getX(), mn.getY(), mx.getZ()), BlockVector3.at(mx.getX(), mx.getY(), mx.getZ()), sy, strideFor(sy, need)),
        };

        // Σ точек по всем 12 рёбрам <= 12*perEdge <= cap; каждое ребро
        // рисуется полностью — ни одна грань (в т.ч. вертикальная) не
        // пропускается, бюджет соблюдается.
        List<BlockVector3> out = new ArrayList<>(Math.min(cap + 16, 4096));
        Set<BlockVector3> seen = new HashSet<>();
        int[] ns = new int[edges.length];
        for (int i = 0; i < edges.length; i++) {
            ns[i] = edges[i].n;
        }
        for (Edge e : edges) {
            while (e.hasNext()) {
                BlockVector3 p = e.next();
                if (seen.add(p)) {
                    out.add(p);
                }
            }
        }
        if (plugin.config().debug()) {
            plugin.getLogger().info("[selection-view] " + mn + ".." + mx
                    + " куб " + sx + "x" + sy + "x" + sz
                    + " cap=" + cap + " perEdge=" + perEdge
                    + " точек=" + out.size()
                    + " низ=" + arr(ns, 0, 4)
                    + " верх=" + arr(ns, 4, 8)
                    + " вертY=" + arr(ns, 8, 12));
        }
        return out;
    }

    private static String arr(int[] a, int from, int to) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = from; i < to; i++) {
            if (i > from) {
                sb.append(',');
            }
            sb.append(a[i]);
        }
        return sb.append(']').toString();
    }

    /** Шаг пунктира ребра длины len: короткое ребро (len <= need) — каждый блок. */
    private static int strideFor(int len, int need) {
        return len <= need ? 1 : (len + need - 1) / need;
    }

    /** Ленивое ребро: {n} равномерных точек от a к b (углы всегда включены). */
    private static Edge edge(BlockVector3 a, BlockVector3 b, int len, int stride) {
        return new Edge(a, b, len, stride);
    }

    private static final class Edge {
        final BlockVector3 a;
        final int dx, dy, dz;
        final int len;
        final int stride;
        final int n;
        int cur = 0;

        Edge(BlockVector3 a, BlockVector3 b, int len, int stride) {
            this.a = a;
            this.dx = b.getX() - a.getX();
            this.dy = b.getY() - a.getY();
            this.dz = b.getZ() - a.getZ();
            this.len = len;
            this.stride = stride;
            // не меньше 2 точек: оба угла ребра есть всегда
            this.n = Math.max(2, len / stride + 1);
        }

        boolean hasNext() {
            return cur < n;
        }

        BlockVector3 next() {
            int j = cur++;
            // серёдина интервалов — равномерно, последняя точка = конец (len),
            // чтобы верхний угол не «съедался» тем, что шаг не делит длину.
            int t = j == n - 1 ? len : Math.min(len, j * stride);
            return BlockVector3.at(
                    a.getX() + dx * t / Math.max(1, len),
                    a.getY() + dy * t / Math.max(1, len),
                    a.getZ() + dz * t / Math.max(1, len));
        }
    }
}