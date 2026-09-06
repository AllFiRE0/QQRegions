package dev.qqregions.selection;

import dev.qqregions.QQRegions;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Слушатели интерактивной сессии выделения:
 * хотбар свободен (действия — кликом кнопкой в руке и ПКМ/ЛКМ по воздуху),
 * в select-режиме колесо двигает активную точку, ЛКМ пустой рукой меняет точку,
 * команды из protect-списка блокируются, чат — имя региона.
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

    private NamespacedKey buttonKey() {
        return new NamespacedKey(plugin, InteractSession.BTN_TAG_KEY);
    }

    /** Возвращает id кнопки ("create"|"select"|"reset"|"cancel") или null, если предмет не кнопка. */
    private String buttonId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(buttonKey(), PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemHeld(PlayerItemHeldEvent e) {
        InteractSession s = session(e.getPlayer());
        if (s == null) {
            return;
        }
        int prev = e.getPreviousSlot();
        int next = e.getNewSlot();
        if (!s.isSelectingMode()) {
            plugin.dbg("hotbar slot: " + e.getPlayer().getName() + " -> " + next);
            return;
        }
        // Определяем направление и число "щелчков" колеса по разнице слотов.
        // Bukkit при быстром прокручивании шлёт ОДНО событие с разницей > 1
        // (промежуточные слоты пропускаются), поэтому ориентируемся на дельту.
        int delta = (next - prev + 9) % 9;      // 0..8
        boolean scrollUp;
        int steps;
        if (delta <= 4) {
            scrollUp = true;
            steps = delta;
        } else {
            scrollUp = false;
            steps = 9 - delta;
        }
        // В select-режиме колесо двигает точку, слот не меняем.
        e.setCancelled(true);
        if (steps >= 1) {
            s.onWheel(scrollUp, steps);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnimation(PlayerAnimationEvent e) {
        InteractSession s = session(e.getPlayer());
        if (s == null || e.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        // ЛКМ кнопкой = её действие; ЛКМ пустой рукой = смена активной точки.
        if (buttonId(e.getPlayer().getInventory().getItemInMainHand()) == null) {
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
        if (a == Action.LEFT_CLICK_AIR || a == Action.RIGHT_CLICK_AIR
                || a == Action.LEFT_CLICK_BLOCK || a == Action.RIGHT_CLICK_BLOCK) {
            String id = buttonId(e.getPlayer().getInventory().getItemInMainHand());
            if (id != null) {
                s.runButton(id);
            }
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

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        InteractSession s = session(e.getPlayer());
        if (s == null) {
            return;
        }
        String cmd = e.getMessage().substring(1).toLowerCase();
        for (String blocked : plugin.config().blockedCommands()) {
            if (blocked.isEmpty()) {
                continue;
            }
            if (cmd.equals(blocked) || cmd.startsWith(blocked + " ")) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(plugin.lang().compPrefixed("select.command-blocked", "cmd", e.getMessage()));
                plugin.dbg("blocked command '" + e.getMessage() + "' by " + e.getPlayer().getName());
                return;
            }
        }
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

    // ---------- аварийные выходы из сессии ----------

    /** Урон от чего угодно (падение, лава, моб, PvP) — сессия закрывается,
     * выделение сбрасывается и не отображается. */
    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && plugin.selections().hasAny(p)) {
            plugin.dbg("session reset by damage to " + p.getName());
            plugin.selections().endSession(p);
        }
    }

    /** Нанесён урон ДРУГОМУ ИГРОКУ — у атакующего сессия закрывается,
     * выделение сбрасывается. */
    @EventHandler
    public void onDamageDealt(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p
                && e.getEntity() instanceof Player
                && plugin.selections().hasAny(p)) {
            plugin.dbg("session reset by pvp: " + p.getName());
            plugin.selections().endSession(p);
        }
    }

    /** Смерть — сессия закрывается, выделение сбрасывается.
     *  Настоящие вещи при этом НЕ подлежат «защите»: при обычной смерти
     *  (keepInventory=false) они выпадают из игрока, как обычно; при
     *  keepInventory=true остаются в инвентаре. Иначе игроки стали бы
     *  входить в сессию перед боем, чтобы застраховать вещи от смерти. */
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        String uuid = p.getUniqueId().toString();
        plugin.selections().endSession(p);
        if (!plugin.store().has(uuid)) {
            return;
        }
        if (e.getKeepInventory()) {
            // keepInventory=true: вещи не выпадают, возвращаем в инвентарь.
            if (plugin.store().restore(uuid, p)) {
                plugin.dbg("death during session (keepInventory): inventory restored, " + p.getName());
            }
        } else {
            // обычная смерть: настоящие вещи выпадают как обычно (кнопки убраны).
            plugin.store().spillToDrops(uuid, e);
            plugin.dbg("death during session: real items dropped, " + p.getName());
        }
    }

    /** Кик — сессия закрывается, инвентарь восстанавливается. */
    @EventHandler
    public void onKick(PlayerKickEvent e) {
        plugin.selections().endSession(e.getPlayer());
    }

    // ---------- восстановление инвентаря из дискового снимка ----------

    /** Вход на сервер (например, после рестарта/краша посреди сессии):
     *  незакрытый снимок инвентаря возвращается игроку. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        restoreIfNeeded(e.getPlayer());
    }

    /** Респавн — страховка: если снимок по какой-то причине не был обработан
     *  в onDeath (крэш между событиями), вернём его здесь как фолбэк. */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        restoreIfNeeded(e.getPlayer());
    }

    private void restoreIfNeeded(Player p) {
        String uuid = p.getUniqueId().toString();
        if (!plugin.store().has(uuid)) {
            return;
        }
        plugin.selections().endSession(p);
        if (plugin.store().restore(uuid, p)) {
            p.sendMessage(plugin.lang().compPrefixed("select.inventory-restored"));
            plugin.dbg("inventory restored from disk snapshot: " + p.getName());
        } else {
            plugin.dbg("inventory restore FAILED for " + p.getName() + " (repeated later on next join/respawn)");
        }
    }
}