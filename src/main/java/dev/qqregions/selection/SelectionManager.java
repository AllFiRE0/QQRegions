package dev.qqregions.selection;

import com.sk89q.worldedit.math.BlockVector3;
import dev.qqregions.QQRegions;
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
 */
public class SelectionManager implements Listener {

    private final QQRegions plugin;
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, InteractSession> sessions = new HashMap<>();

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
        selections.remove(player.getUniqueId());
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
    }

    public void tick() {
        for (InteractSession s : sessions.values()) {
            s.update();
        }
    }

    // ---------- события ----------

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        endSession(e.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        endSession(e.getPlayer());
    }
}