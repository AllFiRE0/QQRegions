package dev.qqregions.selection;

import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.World;

/**
 * Кубоидное выделение в мире: две точки.
 */
public class Selection {

    private final World world;
    private BlockVector3 pos1;
    private BlockVector3 pos2;

    public Selection(World world, BlockVector3 pos1, BlockVector3 pos2) {
        this.world = world;
        this.pos1 = pos1;
        this.pos2 = pos2;
    }

    public World getWorld() {
        return world;
    }

    public BlockVector3 getPos(int index) {
        return index == 1 ? pos1 : pos2;
    }

    public void setPos(int index, BlockVector3 p) {
        if (index == 1) {
            pos1 = p;
        } else {
            pos2 = p;
        }
    }

    public BlockVector3 min() {
        return BlockVector3.at(
                Math.min(pos1.getBlockX(), pos2.getBlockX()),
                Math.min(pos1.getBlockY(), pos2.getBlockY()),
                Math.min(pos1.getBlockZ(), pos2.getBlockZ()));
    }

    public BlockVector3 max() {
        return BlockVector3.at(
                Math.max(pos1.getBlockX(), pos2.getBlockX()),
                Math.max(pos1.getBlockY(), pos2.getBlockY()),
                Math.max(pos1.getBlockZ(), pos2.getBlockZ()));
    }

    public int sizeX() {
        return max().getBlockX() - min().getBlockX() + 1;
    }

    public int sizeY() {
        return max().getBlockY() - min().getBlockY() + 1;
    }

    public int sizeZ() {
        return max().getBlockZ() - min().getBlockZ() + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    /** Выделение после расширения в указанную сторону (позиции не сохраняются). */
    public Selection withExpanded(ExpandDirection dir, int amount) {
        BlockVector3 mn = min();
        BlockVector3 mx = max();
        switch (dir) {
            case NORTH:
                mn = mn.withZ(mn.getBlockZ() - amount);
                break;
            case SOUTH:
                mx = mx.withZ(mx.getBlockZ() + amount);
                break;
            case WEST:
                mn = mn.withX(mn.getBlockX() - amount);
                break;
            case EAST:
                mx = mx.withX(mx.getBlockX() + amount);
                break;
            case DOWN:
                mn = mn.withY(mn.getBlockY() - amount);
                break;
            case UP:
                mx = mx.withY(mx.getBlockY() + amount);
                break;
        }
        return new Selection(world, mn, mx);
    }

    /** Выделение после расширения во все стороны (или по осям). */
    public Selection withOutset(int amount, boolean horizontal, boolean vertical) {
        BlockVector3 mn = min();
        BlockVector3 mx = max();
        if (horizontal) {
            mn = mn.withX(mn.getBlockX() - amount).withZ(mn.getBlockZ() - amount);
            mx = mx.withX(mx.getBlockX() + amount).withZ(mx.getBlockZ() + amount);
        }
        if (vertical) {
            mn = mn.withY(mn.getBlockY() - amount);
            mx = mx.withY(mx.getBlockY() + amount);
        }
        return new Selection(world, mn, mx);
    }

    /** Выделение с прижатой точкой (для интерактивного режима). */
    public Selection withPoint(int index, BlockVector3 p) {
        return new Selection(world, index == 1 ? p : pos1, index == 1 ? pos2 : p);
    }

    /** Проверка, что внутри выделения нет других выделений/путем сравнения. */
    public boolean sameAs(Selection other) {
        return other != null
                && other.getWorld().equals(world)
                && other.min().equals(min())
                && other.max().equals(max());
    }
}