package dev.qqregions.selection;

import dev.qqregions.QQRegions;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncChatEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Слушатели интерактивной сессии выделения: ЛКМ — смена точки,
 * колесо мыши — движение точки, кнопки 1-9 — действия, чат — имя региона.
 */
public class InteractListener implements Listener {

    private final QQRegions plugin;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    public InteractListener(QQRegions plugin) {
        this.plugin = plugin;
    }

    private InteractSession session(Player p) {
        return plugin.selections().session(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemHeld(PlayerItemHeldEvent e) {
        InteractSession s = session(e.getPlayer());
        if (s == null) {
            return;
        }
        e.setCancelled(true);
        int prev = e.getPreviousSlot();
        int next = e.getNewSlot();
        boolean scrollUp = next == (prev + 1) % 9;
        boolean scrollDown = next == (prev + 8) % 9;
        if (scrollUp || scrollDown) {
            s.onWheel(scrollUp);
        } else {
            s.onHotbarSlot(next);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnimation(PlayerAnimationEvent e) {
        InteractSession s = session(e.getPlayer());
        if (s != null && e.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            s.onSwing();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        InteractSession s = session(e.getPlayer());
        if (s == null) {
            return;
        }
        Action a = e.getAction();
        if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            s.onSwing();
        }
        if (e.getHand() == EquipmentSlot.HAND) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent e) {
        InteractSession s = session(e.getPlayer());
        if (s == null || !s.isPrompting()) {
            return;
        }
        e.setCancelled(true);
        String text = LEGACY.serialize(e.message());
        plugin.getServer().getScheduler().runTask(plugin, () -> s.tryCreate(text));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (session(e.getPlayer()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(PlayerAttemptPickupItemEvent e) {
        if (session(e.getPlayer()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (session(e.getPlayer()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInvClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && session(p) != null) {
            e.setCancelled(true);
        }
    }
}