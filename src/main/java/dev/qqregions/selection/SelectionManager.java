package dev.qqregions.selection;

import com.sk89q.worldedit.math.BlockVector3;
import dev.qqregions.QQRegions;
import dev.qqregions.config.Config;
import dev.qqregions.config.SelectionTemplate;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Хранилище выделений и интерактивных сессий выделения.
 * Для обычных команд выделения (pos, chunk, expand, ...) поддерживает
 * подсветку выделения через SelectionView (частицы или блок-дисплеи).
 */
public class SelectionManager implements Listener {

    private final QQRegions plugin;
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, InteractSession> sessions = new HashMap<>();
    private final Map<UUID, SelectionView> views = new HashMap<>();

    public SelectionManager(QQRegions plugin) {
        this.plugin = plugin;
    }

    // ---------- выделения ----------

    public Selection get(Player player) {
        return selections.get(player.getUniqueId());
    }

    public Selection getOrCreate(Player player, World world) {
        return selections.computeIfAbsent(player.getUniqueId(), k -> new Selection(world,
                BlockVector3.at(player.getLocation().getBlockX(),
                        player.getLocation().getBlockY(),
                        player.getLocation().getBlockZ()),
                BlockVector3.at(player.getLocation().getBlockX(),
                        player.getLocation().getBlockY(),
                        player.getLocation().getBlockZ())));
    }

    public void set(Player player, Selection selection) {
        selections.put(player.getUniqueId(), selection);
    }

    public boolean has(Player player) {
        return selections.containsKey(player.getUniqueId());
    }

    public void reset(Player player) {
        UUID id = player.getUniqueId();
        selections.remove(id);
        SelectionView v = views.remove(id);
        if (v != null) {
            v.cleanup();
        }
    }

    // ---------- шаблоны прав ----------

    public SelectionTemplate template(Player player) {
        return plugin.config().templateFor(player);
    }

    public boolean isBypassed(Player player) {
        return player.hasPermission("qqregions.admin")
                || player.hasPermission("qqregions.bypass.selection-limits");
    }

    public boolean overLimit(Player player, Selection selection) {
        return !isBypassed(player) && selection.volume() > template(player).getMaxBlocks();
    }

    public boolean belowMin(Player player, Selection selection) {
        return !isBypassed(player) && selection.volume() < template(player).getMinBlocks();
    }

    /** Прижатие к границам мира. */
    public Selection clampToWorld(Selection selection) {
        World w = selection.getWorld();
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight() - 1;
        BlockVector3 mn = selection.min();
        BlockVector3 mx = selection.max();
        mn = mn.withY(clamp(mn.getBlockY(), minY, maxY));
        mx = mx.withY(clamp(mx.getBlockY(), minY, maxY));
        mn = mn.withX(clamp(mn.getBlockX(), -30000000, 30000000));
        mx = mx.withX(clamp(mx.getBlockX(), -30000000, 30000000));
        mn = mn.withZ(clamp(mn.getBlockZ(), -30000000, 30000000));
        mx = mx.withZ(clamp(mx.getBlockZ(), -30000000, 30000000));
        if (mn.getBlockY() > mx.getBlockY()) {
            mx = mx.withY(mn.getBlockY() + 1);
        }
        return new Selection(w, mn, mx);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---------- интерактивные сессии ----------

    public boolean startSession(Player player) {
        if (sessions.containsKey(player.getUniqueId())) {
            return false;
        }
        InteractSession s = new InteractSession(plugin, player);
        s.start();
        sessions.put(player.getUniqueId(), s);
        SelectionView v = views.remove(player.getUniqueId());
        if (v != null) {
            v.cleanup();
        }
        return true;
    }

    public InteractSession session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public void endSession(Player player) {
        InteractSession s = sessions.remove(player.getUniqueId());
        if (s != null) {
            s.end();
        }
    }

    public void endAll() {
        for (InteractSession s : sessions.values()) {
            s.end();
        }
        sessions.clear();
        for (SelectionView v : views.values()) {
            v.cleanup();
        }
        views.clear();
    }

    public void tick() {
        for (InteractSession s : sessions.values()) {
            s.update();
        }
        renderCommandSelections();
    }

    /** Подсветка выделений, созданных обычными командами (не через сессию). */
    private void renderCommandSelections() {
        if (!plugin.config().commandSelectionView()) {
            if (!views.isEmpty()) {
                for (SelectionView v : views.values()) {
                    v.cleanup();
                }
                views.clear();
            }
            return;
        }
        views.entrySet().removeIf(e -> {
            UUID id = e.getKey();
            if (!selections.containsKey(id)) {
                e.getValue().cleanup();
                return true;
            }
            Player p = plugin.getServer().getPlayer(id);
            if (p == null || !p.isOnline()) {
                e.getValue().cleanup();
                return true;
            }
            return false;
        });
        for (Map.Entry<UUID, Selection> se : selections.entrySet()) {
            UUID id = se.getKey();
            if (sessions.containsKey(id)) {
                continue; // сессия рисует сама
            }
            Player p = plugin.getServer().getPlayer(id);
            if (p == null || !p.isOnline()) {
                continue;
            }
            Selection sel = se.getValue();
            if (!p.getWorld().equals(sel.getWorld())) {
                continue;
            }
            Config cfg = plugin.config();
            SelectionView v = views.computeIfAbsent(id, k -> new SelectionView(plugin, p));
            if (cfg.blockView()) {
                v.update(sel, cfg.pointStyle(2).highlight, cfg.pointStyle(2).block, null);
            } else {
                v.update(sel, cfg.particles().dustColor, cfg.pointStyle(2).block, null);
            }
        }
    }

    // ---------- события ----------

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        endSession(e.getPlayer());
        SelectionView v = views.remove(id);
        if (v != null) {
            v.cleanup();
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        endSession(e.getPlayer());
        SelectionView v = views.remove(id);
        if (v != null) {
            v.cleanup();
        }
    }
}