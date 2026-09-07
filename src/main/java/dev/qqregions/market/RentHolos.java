package dev.qqregions.market;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.qqregions.QQRegions;
import dev.qqregions.config.Config;
import org.bukkit.Display;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.joml.Quaternionf;
import org.joml.Transformation;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Голограмма аренды: светящееся кольцо из TextDisplay по периметру региона
 * на уровне глаз показавшего (владельца, кнопка в своём объявлении аренды).
 * Голограмма персональная: видит её только тот, кто включил.
 */
public final class RentHolos implements Listener {

    private final QQRegions plugin;
    /** key = viewerUuid:world:region */
    private final Map<String, Halo> holos = new HashMap<>();
    private int refreshTick = 0;

    public RentHolos(QQRegions plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.config().market().holo.enabled;
    }

    /** Включить/выключить голограмму для игрока. @return true, если теперь показана. */
    public boolean toggle(Player p, World w, ProtectedRegion r) {
        String key = key(p.getUniqueId(), w, r);
        if (holos.containsKey(key)) {
            remove(key);
            return false;
        }
        show(p, w, r);
        return true;
    }

    public boolean shown(Player p, World w, ProtectedRegion r) {
        return holos.containsKey(key(p.getUniqueId(), w, r));
    }

    public void show(Player p, World w, ProtectedRegion r) {
        remove(key(p.getUniqueId(), w, r));
        if (!enabled()) {
            return;
        }
        Config.HoloOptions h = plugin.config().market().holo;
        try {
            double y = p.getEyeLocation().getY() + h.yOffset;
            List<TextDisplay> list = new ArrayList<>();
            BlockVector3 min = r.getMinimumPoint();
            BlockVector3 max = r.getMaximumPoint();
            int x1 = min.x(), x2 = max.x();
            int z1 = min.z(), z2 = max.z();
            double step = Math.max(0.25, h.spacing);
            // периметр: четыре стороны
            points(x1, z1, x2, z1, step, pts -> list.add(spawn(w, (double) pts.x() + 0.5, y, (double) pts.z() + 0.5, h)));
            points(x2, z1, x2, z2, step, pts -> list.add(spawn(w, (double) pts.x() + 0.5, y, (double) pts.z() + 0.5, h)));
            points(x2, z2, x1, z2, step, pts -> list.add(spawn(w, (double) pts.x() + 0.5, y, (double) pts.z() + 0.5, h)));
            points(x1, z2, x1, z1, step, pts -> list.add(spawn(w, (double) pts.x() + 0.5, y, (double) pts.z() + 0.5, h)));
            holos.put(key, new Halo(p.getUniqueId(), w, r, y, list));
        } catch (Throwable t) {
            plugin.dbg("Голограмма не поддерживается: " + t.getMessage());
        }
    }

    private TextDisplay spawn(World w, double x, double y, double z, Config.HoloOptions h) {
        return w.spawn(new org.bukkit.Location(w, x, y, z), TextDisplay.class, t -> {
            t.setText(dev.qqregions.util.Msg.color(h.text));
            t.setBillboard(Display.Billboard.CENTER);
            t.setSeeThrough(false);
            t.setShadowed(false);
            t.setLineWidth(100);
            float width = (float) h.width;
            t.setTransformation(new Transformation(
                    new Vector3f(),
                    new Quaternionf(),
                    new Vector3f(width, 1f, 1f),
                    new Quaternionf()));
        });
    }

    /** Точки вдоль линии (x1,z1)-(x2,z2) с шагом step (интерполяция по длинной оси). */
    private void points(int x1, int z1, int x2, int z2, double step, java.util.function.Consumer<BlockVector3> sink) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double len = Math.max(Math.abs(dx), Math.abs(dz));
        if (len <= 0) {
            return;
        }
        int n = Math.max(1, (int) Math.round(len / step));
        for (int i = 0; i <= n; i++) {
            double f = n == 0 ? 0 : (double) i / n;
            sink.accept(BlockVector3.at(
                    Math.round((float) (x1 + dx * f)),
                    Math.round((float) (z1 + dz * f))));
        }
    }

    /** Поддерживать кольцо на уровне глаз показывающего (и чистить мёртвые). */
    public void tick() {
        refreshTick++;
        if (refreshTick < 40) {
            return;
        }
        refreshTick = 0;
        Iterator<Map.Entry<String, Halo>> it = holos.entrySet().iterator();
        while (it.hasNext()) {
            Halo h = it.next();
            Player p = plugin.getServer().getPlayer(h.viewer);
            World w = plugin.getServer().getWorld(h.world.getName());
            ProtectedRegion r = w == null ? null : plugin.wg().byName(w, h.region);
            if (p == null || !p.isOnline() || w == null || r == null) {
                clear(h.list);
                it.remove();
                continue;
            }
            double y = p.getEyeLocation().getY() + plugin.config().market().holo.yOffset;
            if (Math.abs(y - h.y) > 0.35) {
                h.y = y;
                for (TextDisplay d : h.list) {
                    if (d.isValid()) {
                        org.bukkit.Location loc = d.getLocation();
                        d.teleport(new org.bukkit.Location(loc.getWorld(), loc.getX(), y, loc.getZ()));
                    }
                }
            }
        }
    }

    public void onDisable() {
        for (Halo h : holos.values()) {
            clear(h.list);
        }
        holos.clear();
    }

    public void removeFor(Player p) {
        Iterator<Map.Entry<String, Halo>> it = holos.entrySet().iterator();
        while (it.hasNext()) {
            Halo h = it.next();
            if (h.viewer.equals(p.getUniqueId())) {
                clear(h.list);
                it.remove();
            }
        }
    }

    private void remove(String key) {
        Halo h = holos.remove(key);
        if (h != null) {
            clear(h.list);
        }
    }

    private static void clear(List<TextDisplay> list) {
        for (TextDisplay d : list) {
            if (d.isValid()) {
                d.remove();
            }
        }
        list.clear();
    }

    private static String key(UUID viewer, World w, ProtectedRegion r) {
        return viewer + ":" + w.getName() + ":" + r.getId();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        removeFor(e.getPlayer());
    }

    private record Halo(UUID viewer, World world, String region, double y, List<TextDisplay> list) {
    }
}