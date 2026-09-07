package dev.qqregions.util;

import com.sk89q.worldedit.math.BlockVector3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Генерация контура кубоида для подсветки (выделение игрока и границы
 * регионов):
 *  - {@link #points} — «пунктир» по 12 рёбрам с равномерным бюджетом
 *    на ребро; используется обходом колонок территории (TERRITORY);
 *  - {@link #outline} — контур с контролем max-gap (шаг точек любой линии
 *    не больше заданного) и горизонтальными «кольцами» по высоте — основа
 *    подсветки выделения и регионов типов PARTICLES/BLOCKS.
 */
public final class BoxOutline {

    private BoxOutline() {
    }

    public static List<BlockVector3> points(BlockVector3 mn, BlockVector3 mx, int maxPoints) {
        int cap = Math.max(24, maxPoints > 0 ? maxPoints : 24);
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

        List<BlockVector3> out = new ArrayList<>(Math.min(cap + 16, 4096));
        Set<BlockVector3> seen = new HashSet<>();
        for (Edge e : edges) {
            while (e.hasNext()) {
                BlockVector3 p = e.next();
                if (seen.add(p)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /**
     * Контур объёма с жёстким контролем плотности: каждая линия (12 рёбер +
     * горизонтальные «кольца») рисуется точками с шагом НЕ БОЛЬШЕ maxGap блоков.
     * Кольца — прямоугольники по периметру на каждом ringStep-м Y-уровне между
     * низом и верхом (верх и низ рисуются всегда); вместе с вертикальными рёбрами
     * они образуют «трубу» из квадратов, по которой широкий/высокий объём читается
     * даже из середины границы. При gridStep > 1 на верхней и нижней ПЛОСКОСТЯХ
     * дополнительно рисуется внутренняя сетка-«квадраты» с шагом gridStep —
     * используется только подсветкой РЕГИОНОВ (выделение игрока рисует грани).
     *
     * @param maxPoints потолок суммарного числа точек (при превышении контур
     *                  прореживается равномерно, углы и концы линий сохраняются)
     * @param maxGap    максимальный зазор между соседними точками линии (в блоках)
     * @param ringStep  шаг колец по Y (в блоках; <= 0 или Integer.MAX_VALUE = без колец)
     * @param gridStep  шаг сетки-«квадратов» на верхе/низу (в блоках; <= 1 — без сетки)
     */
    public static List<BlockVector3> outline(BlockVector3 mn, BlockVector3 mx, int maxPoints,
                                             int maxGap, int ringStep, int gridStep) {
        int cap = Math.max(48, maxPoints > 0 ? maxPoints : 3000);
        if (maxGap <= 0) {
            maxGap = 1;
        }
        int minX = mn.getX(), maxX = mx.getX();
        int minY = mn.getY(), maxY = mx.getY();
        int minZ = mn.getZ(), maxZ = mx.getZ();

        List<BlockVector3> out = new ArrayList<>(Math.min(cap + 64, 16384));
        Set<BlockVector3> seen = new HashSet<>();

        // Уровни-«кольца»: верх и низ всегда, между ними — каждые ringStep блоков.
        List<Integer> ys = new ArrayList<>();
        boolean rings = ringStep > 0 && ringStep < Integer.MAX_VALUE && maxY > minY;
        if (rings) {
            for (long y = minY; ; y += ringStep) {
                ys.add((int) y);
                if (y >= maxY) {
                    break;
                }
                long ny = y + ringStep;
                if (ny >= maxY) {
                    ys.add(maxY);
                    break;
                }
            }
        } else {
            ys.add(minY);
            if (maxY != minY) {
                ys.add(maxY);
            }
        }

        // Прямоугольники-кольца + вертикальные рёбра по углам.
        for (int y : ys) {
            rectAt(out, seen, minX, maxX, minZ, maxZ, y, maxGap);
        }
        vertLine(out, seen, minX, minZ, minY, maxY, maxGap);
        vertLine(out, seen, maxX, minZ, minY, maxY, maxGap);
        vertLine(out, seen, minX, maxZ, minY, maxY, maxGap);
        vertLine(out, seen, maxX, maxZ, minY, maxY, maxGap);

        // Внутренняя сетка-«квадраты» на верхней и нижней плоскостях (для регионов).
        if (gridStep > 1) {
            planeGrid(out, seen, minX, maxX, minZ, maxZ, minY, gridStep, maxGap);
            if (maxY != minY) {
                planeGrid(out, seen, minX, maxX, minZ, maxZ, maxY, gridStep, maxGap);
            }
        }

        // Потолок: равномерно убрать середину, сохранив углы/концы.
        if (out.size() > cap) {
            int step = Math.max(2, (out.size() + cap - 1) / cap);
            List<BlockVector3> thin = new ArrayList<>(cap);
            Set<BlockVector3> tSeen = new HashSet<>(Math.max(16, out.size() / 2));
            for (int i = 0; i < out.size(); i++) {
                if (i == 0 || i == out.size() - 1 || i % step == 0) {
                    BlockVector3 p = out.get(i);
                    if (tSeen.add(p)) {
                        thin.add(p);
                    }
                }
            }
            out = thin;
        }
        return out;
    }

    /** Внутренние линии сетки по всей плоскости y (шаг сетки gridStep, шаг точек <= gap). */
    private static void planeGrid(List<BlockVector3> out, Set<BlockVector3> seen,
                                  int minX, int maxX, int minZ, int maxZ, int y,
                                  int gridStep, int gap) {
        for (int z = minZ; z <= maxZ; z += gridStep) {
            hLineX(out, seen, minX, maxX, y, z, gap);
        }
        for (int x = minX; x <= maxX; x += gridStep) {
            hLineZ(out, seen, minZ, maxZ, y, x, gap);
        }
    }

    /** Четыре стороны прямоугольника на уровне y (шаг точек <= gap). */
    private static void rectAt(List<BlockVector3> out, Set<BlockVector3> seen,
                               int minX, int maxX, int minZ, int maxZ, int y, int gap) {
        hLineX(out, seen, minX, maxX, y, minZ, gap);
        hLineX(out, seen, minX, maxX, y, maxZ, gap);
        hLineZ(out, seen, minZ, maxZ, y, minX, gap);
        hLineZ(out, seen, minZ, maxZ, y, maxX, gap);
    }

    /** Линия вдоль X при фиксированных y,z. */
    private static void hLineX(List<BlockVector3> out, Set<BlockVector3> seen,
                               int a, int b, int y, int z, int gap) {
        int len = b - a;
        int seg = Math.max(1, (len + gap - 1) / gap);
        int stride = Math.max(1, (len + seg - 1) / seg);
        for (int j = 0; j <= seg; j++) {
            add(out, seen, Math.min(b, a + j * stride), y, z);
        }
    }

    /** Линия вдоль Z при фиксированных x,y. */
    private static void hLineZ(List<BlockVector3> out, Set<BlockVector3> seen,
                               int a, int b, int y, int x, int gap) {
        int len = b - a;
        int seg = Math.max(1, (len + gap - 1) / gap);
        int stride = Math.max(1, (len + seg - 1) / seg);
        for (int j = 0; j <= seg; j++) {
            add(out, seen, x, y, Math.min(b, a + j * stride));
        }
    }

    /** Вертикальное ребро по углу (x,z) от y0 до y1 (шаг <= gap). */
    private static void vertLine(List<BlockVector3> out, Set<BlockVector3> seen,
                                 int x, int z, int y0, int y1, int gap) {
        int len = y1 - y0;
        int seg = Math.max(1, (len + gap - 1) / gap);
        int stride = Math.max(1, (len + seg - 1) / seg);
        for (int j = 0; j <= seg; j++) {
            add(out, seen, x, Math.min(y1, y0 + j * stride), z);
        }
    }

    private static void add(List<BlockVector3> out, Set<BlockVector3> seen, int x, int y, int z) {
        BlockVector3 p = BlockVector3.at(x, y, z);
        if (seen.add(p)) {
            out.add(p);
        }
    }

    private static int strideFor(int len, int need) {
        return len <= need ? 1 : (len + need - 1) / need;
    }

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
            this.n = Math.max(2, len / stride + 1);
        }

        boolean hasNext() {
            return cur < n;
        }

        BlockVector3 next() {
            int j = cur++;
            int t = j == n - 1 ? len : Math.min(len, j * stride);
            return BlockVector3.at(
                    a.getX() + dx * t / Math.max(1, len),
                    a.getY() + dy * t / Math.max(1, len),
                    a.getZ() + dz * t / Math.max(1, len));
        }
    }
}