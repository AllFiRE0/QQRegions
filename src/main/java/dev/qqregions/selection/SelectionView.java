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
    /** Отпечаток последнего кадра: если не изменился — BLOCKS-рендер пропускается
     * (без спама NBT-пакетов и debug-лога в покое). */
    private String lastFp = "";
    /** «Проходов» обновления без изменений (1 проход = 5 серверных тиков). */
    private int idle = 0;
    /** Подсветка скрыта за задержку бездействия; рисуется снова при изменении кадра. */
    private boolean hidden = false;
    /** Часть точек кадра была в незагруженных чанках — ждём их и перерисуем. */
    private boolean unloadedPending = false;
    /** Тик-счётчик принудительного «самолечения» кадра: раз в N вызовов фоновый
     *  ре-рендер проверяет, что у всех точек кадра есть ЖИВЫЕ дисплеи (их могли
     *  убить разгрузки чанков в покое). */
    private int healTimer = 0;

    // --- отдельная логика КОМАНДНОГО выделения (pos/point/max/chunk/expand/outset) ---
    /** Маркеры точек 1 и 2 (блок-дисплеи), живущие независимо от маркеров select. */
    private Entity viewCmdMarker1;
    private Entity viewCmdMarker2;
    /** Отпечаток последнего кадра командного выделения (обе точки + цвета). */
    private String cmdFp = "";
    private int cmdIdle = 0;
    private boolean cmdHidden = false;

    public SelectionView(QQRegions plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    /**
     * Троттлинг-обновление (вызывается каждый тик плагина).
     * В PARTICLES-режиме частицы ПОДСЫПАЮТСЯ на каждом интервале, даже без
     * изменения кадра: иначе статичная подсветка бы гасла сразу (частицы
     * живут меньше секунды). В BLOCKS-режиме дисплеи обновляются/телепортируются
     * только при изменении кадра.
     * Авто-скрытие: через view-hide-after секунд без изменений подсветка
     * скрывается (частицы перестают сыпаться / дисплеи убираются); любое
     * новое изменение кадра снова включает её.
     */
    public void update(Selection sel, Color color, Material blockMat, BlockVector3 marker) {
        if (sel == null) {
            lastFp = "";
            return;
        }
        String fp = fp(sel, marker, color, blockMat, false);
        boolean changed = !fp.equals(lastFp);
        lastFp = fp;
        if (changed) {
            idle = 0;
            hidden = false;
        } else {
            idle++;
        }
        int hideCalls = plugin.config().viewHideAfterCalls();
        if (hideCalls > 0 && idle >= hideCalls) {
            if (!hidden) {
                hidden = true;
                cleanup();
            }
            return;
        }
        timer += 5;
        if (plugin.config().blockView()) {
            if (changed || unloadedPending || forceHeal()) {
                renderBlockView(sel, color, blockMat, marker);
            }
        } else {
            if (timer < plugin.config().particles().updateTicks) {
                return;
            }
            timer = 0;
            renderParticles(sel, color, marker);
        }
    }

    /**
     * Немедленный рендер (без троттлинга) — для мгновенного отклика.
     * Всегда пересылает частицы (если не скрыта и кадр не изменился).
     */
    public void renderNow(Selection sel, Color color, Material blockMat, BlockVector3 marker) {
        timer = plugin.config().particles().updateTicks;
        if (sel == null) {
            lastFp = "";
            return;
        }
        String fp = fp(sel, marker, color, blockMat, false);
        boolean changed = !fp.equals(lastFp);
        lastFp = fp;
        if (changed) {
            idle = 0;
            hidden = false;
        } else if (hidden) {
            return;
        }
        if (plugin.config().blockView()) {
            if (changed || unloadedPending || forceHeal()) {
                renderBlockView(sel, color, blockMat, marker);
            }
        } else {
            renderParticles(sel, color, marker);
        }
    }

    /** Даже без изменения кадра иногда пересобираем дисплеи: сущности в дальних
     *  чанках могут умереть от разгрузки чанков, и их надо переспавнить. */
    private boolean forceHeal() {
        healTimer++;
        return healTimer % 4 == 0;
    }

    /**
     * Рендер выделения, созданного ОБЫЧНЫМИ КОМАНДАМИ (/region select pos 1/2,
     * point, max, chunk, expand, outset). ОТДЕЛЬНАЯ логика от интерактивного
     * select (renderSelect не задействован — маркеры свои): всегда рисуются
     * маркеры ОБЕИХ точек (каждая своим цветом/блоком), при volume>1 — контур
     * объёма; маркеры телепортируются за переустановленными точками. Авто-
     * скрытие только по explicit command-selection-hide-after (0 = держать).
     */
    public void updateCommand(Selection sel) {
        if (sel == null) {
            cmdFp = "";
            return;
        }
        Config cfg = plugin.config();
        Config.PointStyle s1 = cfg.pointStyle(1);
        Config.PointStyle s2 = cfg.pointStyle(2);
        BlockVector3 p1 = sel.getPos(1);
        BlockVector3 p2 = sel.getPos(2);
        String frame = sel.getWorld().getName()
                + '|' + sel.min() + '|' + sel.max()
                + '|' + p1 + '|' + p2
                + '|' + s1.highlight.asRGB() + '|' + s2.highlight.asRGB()
                + '|' + s1.block.name() + '|' + s2.block.name();
        boolean changed = !frame.equals(cmdFp);
        cmdFp = frame;
        if (changed) {
            cmdIdle = 0;
            cmdHidden = false;
        } else {
            cmdIdle++;
        }
        int hideCalls = cfg.cmdViewHideAfter() * 4; // 4 прохода тика в секунду
        if (hideCalls > 0 && cmdIdle >= hideCalls) {
            if (!cmdHidden) {
                cmdHidden = true;
                cleanup();
            }
            return;
        }
        if (cmdHidden) {
            return;
        }
        if (cfg.blockView()) {
            if (!changed && !unloadedPending && !forceHeal()) {
                return;
            }
            renderBlockView(sel, s2.highlight, s2.block, null);
            placeCmdMarker(1, sel.getWorld(), p1, s1);
            placeCmdMarker(2, sel.getWorld(), p2, s2);
            return;
        }
        Config.ParticleOptions po = cfg.particles();
        if (!po.enabled) {
            return;
        }
        if (changed) {
            timer = po.updateTicks;
        } else {
            timer += 5;
            if (timer < po.updateTicks) {
                return;
            }
        }
        renderParticles(sel, s2.highlight, null);
        markerCube(sel.getWorld(), po, s1.highlight, p1);
        markerCube(sel.getWorld(), po, s2.highlight, p2);
    }

    /** Маркер точки командного выделения: телепорт при изменении, спавн при отсутствии. */
    private void placeCmdMarker(int which, World world, BlockVector3 pos, Config.PointStyle style) {
        Entity marker = which == 1 ? viewCmdMarker1 : viewCmdMarker2;
        if (marker != null && marker.isValid()) {
            marker.teleport(displayLoc(world, pos));
            if (marker instanceof BlockDisplay bd) {
                bd.setBlock(style.block.createBlockData());
                bd.setGlowColorOverride(style.highlight);
            }
            return;
        }
        BlockDisplay d = spawnViewBlock(world, pos, style.block, style.highlight,
                Math.min(1.0f, plugin.config().viewBlockScale() * 2.0f));
        if (which == 1) {
            viewCmdMarker1 = d;
        } else {
            viewCmdMarker2 = d;
        }
    }

    private static String fp(Selection sel, BlockVector3 marker, Color color, Material mat, boolean select) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(sel.getWorld().getName()).append('|')
                .append(sel.min().getX()).append(',').append(sel.min().getY()).append(',').append(sel.min().getZ()).append('|')
                .append(sel.max().getX()).append(',').append(sel.max().getY()).append(',').append(sel.max().getZ());
        if (marker != null) {
            sb.append('|').append(marker.getX()).append(',').append(marker.getY()).append(',').append(marker.getZ());
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
        Config.PointStyle act = activePoint == 1 ? p1 : p2;
        Config.PointStyle oth = activePoint == 1 ? p2 : p1;
        BlockVector3 actPos = sel.getPos(activePoint);
        BlockVector3 othPos = sel.getPos(activePoint == 1 ? 2 : 1);
        // select-режим рендерит маркеры обеих точек; работу пропускаем
        // только если НИЧЕГО не менялось (обе точки, активная, цвета).
        String fp = fp(sel, othPos, oth.highlight, oth.block, true)
                + '|' + activePoint
                + '|' + actPos.getX() + ',' + actPos.getY() + ',' + actPos.getZ()
                + '|' + act.highlight.asRGB();
        boolean changed = !fp.equals(lastFp);
        lastFp = fp;
        if (cfg.blockView()) {
            if (!changed && !unloadedPending && !forceHeal()) {
                return;
            }
            renderBlockView(sel, act.highlight, act.block, actPos);
            updateOtherMarker(sel.getWorld(), oth.highlight, oth.block, othPos);
            return;
        }
        // PARTICLES: статичный кадр тоже подсыпаем с троттлингом, чтобы
        // подсветка не гасла, когда игрок стоит на месте; изменение кадра
        // рендерится сразу.
        if (changed) {
            timer = cfg.particles().updateTicks;
        } else {
            timer += 5;
            if (timer < cfg.particles().updateTicks) {
                return;
            }
        }
        renderParticles(sel, act.highlight, null);
        Config.ParticleOptions po = cfg.particles();
        markerCube(sel.getWorld(), po, p1.highlight, sel.getPos(1));
        markerCube(sel.getWorld(), po, p2.highlight, sel.getPos(2));
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
        if (viewCmdMarker1 != null) {
            viewCmdMarker1.remove();
            viewCmdMarker1 = null;
        }
        if (viewCmdMarker2 != null) {
            viewCmdMarker2.remove();
            viewCmdMarker2 = null;
        }
        timer = 0;
    }

    // ---------- PARTICLES ----------

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
    /** Отпечаток генератора: по нему в логе видно, из какого кода собран jar.
     *  b1 — старый дедуп по blockKey (терял южные вертикали),
     *  b3 — текущий дедуп по BlockVector3; южные вертикали были заданы
     *  вырождённо (обе точки = mn.getY()) и не генерировались,
     *  b4 — южные вертикали починены (верхняя точка = mx.getY()). */
    private static final String EDGE_GEN_REV = "b4";

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
                    + " ген=" + EDGE_GEN_REV
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

    private void renderParticles(Selection sel, Color color, BlockVector3 marker) {
        Config.ParticleOptions po = plugin.config().particles();
        if (!po.enabled) {
            return;
        }
        World world = sel.getWorld();
        // Вернулся ровно старый пунктир (edgePoints): у каждого ребра куба почти
        // равный бюджет точек (cap/12) и свой шаг по длине ребра, все 12 рёбер
        // рисуются целиком (вертикали не пропадают). Потолок — view-max-blocks.
        for (BlockVector3 p : edgePoints(sel, Math.max(24, plugin.config().viewMaxBlocks()))) {
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
        BlockVector3 mn0 = sel.min();
        BlockVector3 mx0 = sel.max();

        // Контур выделения — ровно старый вид (edgePoints): у каждого ребра куба
        // почти равный бюджет точек (cap/12) и равномерный шаг по длине ребра,
        // все 12 рёбер рисуются целиком. Бюджет BLOCKS = view-dots-per-edge*12
        // (не view-max-blocks): плотные 500 точек не упираются в потолок display-
        // сущностей на чанк и не съедают последние (южные) вертикали. Сначала
        // идут интерьеры вертикальных колонок — они спавнятся в приоритете.
        List<BlockVector3> points = edgePoints(sel, Math.max(24, cfg.viewDotsPerEdge() * 12));
        verticalFirst(points, mn0, mx0);
        // Точки в незагруженных чанках пропускаем (дисплей там невидим и
        // падает при выгрузке чанка); флаг заставляет рисовать снова, пока
        // все чанки кадра не загрузятся.
        unloadedPending = false;
        List<BlockVector3> visible = new ArrayList<>(points.size());
        for (BlockVector3 p : points) {
            if (world.isChunkLoaded(p.getBlockX() >> 4, p.getBlockZ() >> 4)) {
                visible.add(p);
            } else {
                unloadedPending = true;
            }
        }
        points = visible;

        List<BlockVector3> need = new ArrayList<>();
        List<BlockVector3> spare = new ArrayList<>();
        for (BlockVector3 p : points) {
            // containsKey() НЕ достаточно: если дисплей умер вместе с выгрузкой
            // чанка, ключ-позиция остаётся в map, а сущность невалидна — без
            // этой проверки вертикали в дальних чанках пропадают НАВСЕГДА.
            Entity existing = viewBlocks.get(p);
            if (existing == null || !existing.isValid()) {
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
                BlockDisplay spawned = spawnViewBlock(world, p, blockMat, color, cfg.viewBlockScale());
                if (spawned != null) {
                    viewBlocks.put(p, spawned);
                }
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

        if (plugin.config().debug()) {
            // Диагностика двух южных/северных вертикальных колонок:
            // считаем ЖИВЫЕ дисплеи по 4 угловым колонкам (x=min|max, z=min|max).
            int north = 0;
            int south = 0;
            for (Map.Entry<BlockVector3, Entity> e : viewBlocks.entrySet()) {
                BlockVector3 k = e.getKey();
                int kx = k.getBlockX();
                int kz = k.getBlockZ();
                boolean cornerX = kx == mn0.getBlockX() || kx == mx0.getBlockX();
                boolean cornerZ = kz == mn0.getBlockZ() || kz == mx0.getBlockZ();
                if (cornerX && cornerZ) {
                    if (kz == mx0.getBlockZ()) {
                        south++;
                    } else {
                        north++;
                    }
                }
            }
            plugin.getLogger().info("[selection-view] колонки: север=" + north
                    + " юг=" + south + " всего=" + viewBlocks.size() + " хотелось=" + wanted.size());
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

        // Самовосстановление: если какой-то желаемой точке кадра не хватило
        // ЖИВОГО дисплея (умер при выгрузке чанка, спавн провалился) — ставим
        // флаг, чтобы следующий тик перерисовал кадр и доспавнил недостающее.
        // Гарантирует 12 полных рёбер даже после смерти сущностей в дальних
        // чанках; в здоровом кадре флаг не ставится (ре-рендеров не будет).
        for (BlockVector3 p : points) {
            Entity e = viewBlocks.get(p);
            if (e == null || !e.isValid()) {
                unloadedPending = true;
                break;
            }
        }
    }

    /** Интерьеры 4 угловых вертикалей в начало списка: вертикальные колонки
     *  спавнятся/телепортируются ПЕРВЫМИ, поэтому при любом потолке дисплеев
     *  на чанк горизонт-ребра не могут «украсть» точки у вертикалей. */
    private static void verticalFirst(List<BlockVector3> pts, BlockVector3 mn, BlockVector3 mx) {
        int x0 = mn.getBlockX(), x1 = mx.getBlockX();
        int z0 = mn.getBlockZ(), z1 = mx.getBlockZ();
        int y0 = mn.getBlockY(), y1 = mx.getBlockY();
        pts.sort((a, b) -> {
            boolean av = isVertical(a, x0, x1, z0, z1, y0, y1);
            boolean bv = isVertical(b, x0, x1, z0, z1, y0, y1);
            return av == bv ? 0 : (av ? -1 : 1);
        });
    }

    private static boolean isVertical(BlockVector3 p, int x0, int x1, int z0, int z1, int y0, int y1) {
        int x = p.getBlockX(), y = p.getBlockY(), z = p.getBlockZ();
        return (x == x0 || x == x1) && (z == z0 || z == z1) && y > y0 && y < y1;
    }

    /** Маркер второй (неактивной) точки в BLOCKS-режиме — свой цвет и блок. */
    private void updateOtherMarker(World world, Color color, Material blockMat, BlockVector3 pos) {
        if (!world.isChunkLoaded(pos.getBlockX() >> 4, pos.getBlockZ() >> 4)) {
            unloadedPending = true;
            return;
        }
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
        if (!world.isChunkLoaded(p.getBlockX() >> 4, p.getBlockZ() >> 4)) {
            unloadedPending = true;
            return null;
        }
        try {
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
        } catch (Throwable t) {
            unloadedPending = true;
            return null;
        }
    }

    // ---------- утилиты ----------

    private static Location displayLoc(World world, BlockVector3 p) {
        return new Location(world, p.getBlockX() + 0.5, p.getBlockY() + 0.5, p.getBlockZ() + 0.5);
    }
}