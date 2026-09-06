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

    /** Есть ли у игрока что-то к сбросу (сессия или выделение/подсветка). */
    public boolean hasAny(Player player) {
        UUID id = player.getUniqueId();
        return sessions.containsKey(id) || selections.containsKey(id) || views.containsKey(id);
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

    /** Прижатие к границам мира. Сохраняет идентичность точек (без пересортировки!). */
    public Selection clampToWorld(Selection selection) {
        World w = selection.getWorld();
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight() - 1;
        BlockVector3 p1 = clampPoint(selection.getPos(1), minY, maxY);
        BlockVector3 p2 = clampPoint(selection.getPos(2), minY, maxY);
        return new Selection(w, p1, p2);
    }

    private static BlockVector3 clampPoint(BlockVector3 p, int minY, int maxY) {
        return BlockVector3.at(
                clamp(p.getBlockX(), -30000000, 30000000),
                clamp(p.getBlockY(), minY, maxY),
                clamp(p.getBlockZ(), -30000000, 30000000));
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
        if (!s.start()) {
            // Инвентарь не удалось снять на диск — сессия не запускается,
            // чтобы игрок не остался без вещей при краше/рестарте сервера.
            return false;
        }
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
        UUID id = player.getUniqueId();
        InteractSession s = sessions.remove(id);
        if (s != null) {
            s.end();
        }
        // «Выход из сессии в обычный инвентарь» всегда сбрасывает выделение
        // и убирает подсветку (cancel, повторный /region select, смерть,
        // урон, кик/бан, запрещённый мир — всё идёт через endSession).
        reset(player);
    }

    /** Запрещён ли мир для плагина с учётом прав игрока. */
    public boolean worldDisabledFor(Player player) {
        return plugin.config().isWorldDisabled(player.getWorld())
                && !player.hasPermission("qqregions.admin")
                && !player.hasPermission("qqregions.bypass.disabled-worlds");
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
        // В запрещённом мире сессия/выделение не живут (без скана регионов —
        // только проверка конфига).
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (worldDisabledFor(p)) {
                plugin.dbg("session reset: disabled world " + p.getWorld().getName() + " for " + p.getName());
                endSession(p);
            }
        }
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
            if (p == null || !p.isOnline() || worldDisabledFor(p)) {
                continue;
            }
            Selection sel = se.getValue();
            if (!p.getWorld().equals(sel.getWorld())) {
                continue;
            }
            // Вырожденное выделение (0x0x0 / одна точка) рисовать незачем:
            // это и не выделение ещё, только лишняя работа каждый тик.
            if (sel.volume() <= 1) {
                SelectionView dead = views.remove(id);
                if (dead != null) {
                    dead.cleanup();
                }
                continue;
            }
            Config cfg = plugin.config();
            if (cfg.viewHideDistance() > 0 && farFromSelection(p, sel, cfg.viewHideDistance())) {
                SelectionView far = views.remove(id);
                if (far != null) {
                    far.cleanup();
                }
                continue;
            }
            SelectionView v = views.computeIfAbsent(id, k -> new SelectionView(plugin, p));
            if (cfg.blockView()) {
                v.update(sel, cfg.pointStyle(2).highlight, cfg.pointStyle(2).block, null);
            } else {
                v.update(sel, cfg.particles().dustColor, cfg.pointStyle(2).block, null);
            }
        }
    }

    /** true, если игрок дальше blocks от центра выделения (2D-радиус).
     *  Предназначено для view-hide-distance в config.yml. */
    private boolean farFromSelection(Player p, Selection sel, int blocks) {
        BlockVector3 mn = sel.min();
        BlockVector3 mx = sel.max();
        double cx = (mn.getBlockX() + mx.getBlockX()) / 2.0;
        double cz = (mn.getBlockZ() + mx.getBlockZ()) / 2.0;
        double dx = p.getLocation().getX() - cx;
        double dz = p.getLocation().getZ() - cz;
        return (dx * dx + dz * dz) > ((double) blocks * blocks);
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