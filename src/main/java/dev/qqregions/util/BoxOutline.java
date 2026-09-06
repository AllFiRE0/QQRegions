package dev.qqregions.util;

import com.sk89q.worldedit.math.BlockVector3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Пунктир по всем 12 рёбрам куба: бюджет точек на ОДНО ребро = cap/12,
 * шаг = округление длины/бюджета — период растёт с длиной ребра, короткие
 * рёбра идут каждым блоком. Все 12 рёбер рисуются ЦЕЛИКОМ, поэтому ни одна
 * грань (в том числе вертикальная) не пропадает.
 * Используется контуром выделения (SelectionView) и подсветкой границ
 * регионов (HighlightManager). Алгоритм скопирован из SelectionView.
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