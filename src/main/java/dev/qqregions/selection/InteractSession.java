package dev.qqregions.selection;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import dev.qqregions.QQRegions;
import dev.qqregions.config.Config;
import dev.qqregions.config.SelectionTemplate;
import dev.qqregions.util.Msg;
import dev.qqregions.wg.RegionException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Интерактивная сессия выделения (/region select).
 * Сохраняет инвентарь игрока, выкладывает кнопки в хотбар (слоты можно
 * свободно переключать; действие — кликом зажатой кнопкой), колесо мыши
 * в режиме выделения двигает активную точку. Частицы и боссбар настраиваются
 * в config.yml. Если включён sync-worldedit, выделение передаётся в WorldEdit
 * (sVis подсвечивает область автоматически).
 */
public class InteractSession {

    public static final int SLOT_CREATE = 0;
    public static final int SLOT_SELECT = 3;
    public static final int SLOT_RESET = 5;
    public static final int SLOT_CANCEL = 8;

    /** Ключ NBT-тега кнопки сессии (значение = "create"|"select"|"reset"|"cancel"). */
    public static final String BTN_TAG_KEY = "session-button";

    private final QQRegions plugin;
    private final Player player;

    private final ItemStack[] contents;
    private final ItemStack[] armor;
    private final ItemStack offhand;

    private boolean selectingMode = false;
    private int activePoint = 2;
    private boolean namePrompt = false;

    private BossBar bar;
    private NamespacedKey barKey;
    private int barTimer = 0;

    /** Общий рендер выделения (частицы или блок-дисплеи). */
    private final SelectionView view;

    /** Мир, в котором живёт сессия (для сброса WE-селекции). */
    private final World world;

    public InteractSession(QQRegions plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.world = player.getWorld();
        PlayerInventory inv = player.getInventory();
        this.contents = inv.getContents().clone();
        this.armor = inv.getArmorContents().clone();
        this.offhand = inv.getItemInOffHand().clone();
        this.view = new SelectionView(plugin, player);
    }

    public void start() {
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setItemInOffHand(null);
        renderButtons();
        inv.setHeldItemSlot(SLOT_SELECT);
        player.sendMessage(plugin.lang().compPrefixed("select.interactive-on"));
        player.sendMessage(plugin.lang().comp("select.interactive-help"));
        plugin.dbg("session start: " + player.getName() + " @" + world.getName()
                + " (syncWorldEdit=" + plugin.config().syncWorldEdit() + ")");
    }

    private ItemStack button(String id, String nameKey) {
        Material m = plugin.config().buttonMaterial(id);
        ItemStack item = new ItemStack(m == null ? Material.BARRIER : m);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Msg.color(plugin.lang().get(nameKey)));
            meta.getPersistentDataContainer().set(buttonKey(), PersistentDataType.STRING, id);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Раскладка «четыре кнопки» вне режима выделения. */
    private void renderButtons() {
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            inv.setItem(slot, null);
        }
        inv.setItem(SLOT_CREATE, button("create", "select.button-create"));
        inv.setItem(SLOT_SELECT, button("select", "select.button-select"));
        inv.setItem(SLOT_RESET, button("reset", "select.button-reset"));
        inv.setItem(SLOT_CANCEL, button("cancel", "select.button-cancel"));
    }

    /** Раскладка «стеклянные панели» в режиме выделения: все 9 слотов. */
    private void renderSelectHotbar() {
        PlayerInventory inv = player.getInventory();
        Config.PointStyle style = plugin.config().pointStyle(activePoint);
        ItemStack pane = new ItemStack(style.pane);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.displayName(Msg.color(plugin.lang().get(
                    activePoint == 1 ? "select.point-pane-1" : "select.point-pane-2")));
            pane.setItemMeta(meta);
        }
        for (int slot = 0; slot < 9; slot++) {
            inv.setItem(slot, pane.clone());
        }
    }

    private NamespacedKey buttonKey() {
        return new NamespacedKey(plugin, "session-button");
    }

    // ---------- действия по кнопкам (клик зажатой кнопкой) ----------

    public void runButton(String id) {
        plugin.dbg("session button " + id + " by " + player.getName());
        switch (id) {
            case "create":
                create();
                break;
            case "select":
                toggleSelect();
                break;
            case "reset":
                reset();
                break;
            case "cancel":
                cancel();
                break;
            default:
                break;
        }
    }

    private void create() {
        SelectionManager mgr = plugin.selections();
        Selection sel = mgr.get(player);
        if (sel == null) {
            player.sendMessage(plugin.lang().compPrefixed("create.none", "alias", plugin.config().commandName()));
            return;
        }
        if (mgr.belowMin(player, sel)) {
            SelectionTemplate t = mgr.template(player);
            player.sendMessage(plugin.lang().compPrefixed("select.below-min",
                    "current", fmt(sel.volume()), "min", fmt(t.getMinBlocks())));
            return;
        }
        namePrompt = true;
        player.sendMessage(plugin.lang().compPrefixed("create.prompt"));
    }

    private void toggleSelect() {
        selectingMode = !selectingMode;
        plugin.dbg("toggleSelect -> " + selectingMode);
        if (selectingMode) {
            SelectionManager mgr = plugin.selections();
            if (mgr.get(player) == null) {
                // Первая точка — на теле игрока, вторая — в ногах; дальше свободно.
                BlockVector3 feet = feet(player);
                Selection sel = new Selection(player.getWorld(), feet.withY(feet.getBlockY() + 1), feet);
                mgr.set(player, mgr.clampToWorld(sel));
            }
            activePoint = 1;
            renderSelectHotbar();
        } else {
            renderButtons();
        }
        syncWorldEdit();
        player.sendMessage(plugin.lang().compPrefixed("select.interactive-select-mode",
                "point", plugin.lang().fmt("select.point-" + activePoint)));
    }

    private void reset() {
        BlockVector3 feet = feet(player);
        plugin.selections().set(player, new Selection(player.getWorld(), feet, feet));
        syncWorldEdit();
        player.sendMessage(plugin.lang().compPrefixed("select.reset"));
    }

    private void cancel() {
        plugin.selections().endSession(player);
    }

    public void end() {
        namePrompt = false;
        resetWheelAcc();
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setArmorContents(armor);
        inv.setItemInOffHand(offhand);
        inv.setContents(contents);
        hideBar();
        view.cleanup();
        clearWorldEdit();
        if (player.isOnline()) {
            player.sendMessage(plugin.lang().compPrefixed("select.interactive-off"));
        }
        plugin.dbg("session end: " + player.getName());
    }

    // ---------- события мыши ----------

    public boolean isSelectingMode() {
        return selectingMode;
    }

    public boolean isPrompting() {
        return namePrompt;
    }

    public void onSwing() {
        if (!selectingMode) {
            return;
        }
        if (player.isSneaking()) {
            confirm();
            return;
        }
        setActivePoint(activePoint == 1 ? 2 : 1);
    }

    public void setActivePoint(int point) {
        activePoint = point;
        resetWheelAcc();
        renderSelectHotbar();
        player.sendMessage(plugin.lang().compPrefixed("select.interactive-select-mode",
                "point", plugin.lang().fmt("select.point-" + activePoint)));
        plugin.dbg("point switch -> " + activePoint);
    }

    /** ЛКМ+Шифт: подтвердить выделение и вернуться к четырём кнопкам. */
    private void confirm() {
        selectingMode = false;
        renderButtons();
        invHoldSelectSlot();
        syncWorldEdit();
        Selection sel = plugin.selections().get(player);
        long blocks = sel == null ? 0 : sel.volume();
        player.sendMessage(plugin.lang().compPrefixed("select.confirmed", "blocks", fmt(blocks)));
        plugin.dbg("select confirmed: " + blocks + " blocks");
    }

    private void invHoldSelectSlot() {
        player.getInventory().setHeldItemSlot(SLOT_SELECT);
    }

    public void onWheel(boolean scrollUp) {
        if (!selectingMode) {
            return;
        }
        Selection sel = plugin.selections().get(player);
        if (sel == null) {
            return;
        }
        boolean forward = plugin.config().invertWheel() == scrollUp;
        BlockVector3 cur = sel.getPos(activePoint);
        BlockVector3 next = computeMove(cur, forward);
        if (next == null) {
            player.sendMessage(plugin.lang().compPrefixed("select.point-locked"));
            return;
        }
        if (next == cur) {
            return; // крошечный шаг округлился в 0 — состояние не меняется
        }
        plugin.selections().set(player, plugin.selections().clampToWorld(sel.withPoint(activePoint, next)));
        syncWorldEdit();
        Selection moved = plugin.selections().get(player);
        if (moved != null) {
            Config cfg = plugin.config();
            Color c = cfg.pointStyle(activePoint).highlight;
            view.renderNow(moved, c, cfg.pointStyle(activePoint).block, moved.getPos(activePoint));
        }
        plugin.dbg("wheel: point" + activePoint + " " + cur + " -> " + next);
    }

    /**
     * Движение активной точки ТОЛЬКО по направлению взгляда (все оси).
     * За одно деление колеса точка проходит
     * wheel-distance * (shift-множитель) / wheel-slots блоков; дробные остатки
     * копятся в wheelAcc, чтобы медленные значения не терялись.
     */
    private final double[] wheelAcc = new double[3];

    private void resetWheelAcc() {
        wheelAcc[0] = wheelAcc[1] = wheelAcc[2] = 0;
    }

    private BlockVector3 computeMove(BlockVector3 cur, boolean forward) {
        Config cfg = plugin.config();
        double per = cfg.wheelDistance()
                * (player.isSneaking() ? cfg.wheelShiftSpeed() : 1.0)
                / cfg.wheelSlots();
        if (!forward) {
            per = -per;
        }
        org.bukkit.util.Vector dir = player.getLocation().getDirection();
        double[] dv = {dir.getX() * per, dir.getY() * per, dir.getZ() * per};

        int x = cur.getBlockX();
        int y = cur.getBlockY();
        int z = cur.getBlockZ();
        BlockVector3 next = BlockVector3.at(
                x + moveAxis(wheelAcc, 0, dv[0]),
                y + moveAxis(wheelAcc, 1, dv[1]),
                z + moveAxis(wheelAcc, 2, dv[2]));

        int minY = player.getWorld().getMinHeight();
        int maxY = player.getWorld().getMaxHeight() - 1;
        BlockVector3 clamped = next;
        if (next.getBlockY() < minY) {
            clamped = clamped.withY(minY);
        } else if (next.getBlockY() > maxY) {
            clamped = clamped.withY(maxY);
        }
        // Совсем не сдвинулось (дробный шаг округлился в 0) — без сообщений.
        if (next.equals(cur)) {
            return cur;
        }
        // Хотели сдвинуться, но упёрлись в границу мира — сообщение.
        if (clamped.equals(cur)) {
            return null;
        }
        return clamped;
    }

    /** Прибавляет дробный сдвиг к аккумулятору оси и возвращает целое число блоков. */
    private int moveAxis(double[] acc, int axis, double delta) {
        acc[axis] += delta;
        int step = (int) Math.round(acc[axis]);
        acc[axis] -= step;
        return step;
    }

    // ---------- ввод названия ----------

    public void tryCreate(String raw) {
        namePrompt = false;
        String name = raw == null ? "" : raw.trim();
        List<String> cancelWords = plugin.lang().stringList("create.cancel-words");
        for (String w : cancelWords) {
            if (name.equalsIgnoreCase(w)) {
                player.sendMessage(plugin.lang().compPrefixed("create.cancelled"));
                return;
            }
        }
        Selection sel = plugin.selections().get(player);
        if (sel == null) {
            player.sendMessage(plugin.lang().compPrefixed("create.none", "alias", plugin.config().commandName()));
            return;
        }
        if (!plugin.config().namePattern().matcher(name).matches()) {
            player.sendMessage(plugin.lang().compPrefixed("create.invalid-name",
                    "regex", plugin.config().namePattern().pattern()));
            namePrompt = true;
            return;
        }
        String norm = plugin.config().normalizeName(name);
        try {
            plugin.wg().create(sel, norm, player);
            player.sendMessage(plugin.lang().compPrefixed("create.ok",
                    "region", norm, "world", sel.getWorld().getName(), "blocks", fmt(sel.volume())));
        } catch (RegionException e) {
            player.sendMessage(plugin.lang().compPrefixed(e.getKey(), e.getKv()));
        }
    }

    // ---------- рендер: частицы + боссбар ----------

    public void update() {
        Selection sel = plugin.selections().get(player);
        if (sel == null) {
            hideBar();
            view.cleanup();
            return;
        }
        Config cfg = plugin.config();
        if (selectingMode) {
            view.update(sel, cfg.pointStyle(activePoint).highlight,
                    cfg.pointStyle(activePoint).block, sel.getPos(activePoint));
        } else {
            view.update(sel, cfg.particles().dustColor, cfg.pointStyle(2).block, null);
        }

        if (cfg.bossbar().enabled) {
            barTimer += 5;
            if (barTimer >= cfg.bossbar().updateTicks) {
                barTimer = 0;
                updateBar(sel, cfg.bossbar());
            }
        } else {
            hideBar();
        }
    }

    private void updateBar(Selection sel, Config.BossBarOptions bo) {
        String mode = bo.mode;
        if (mode.equals("NONE")) {
            hideBar();
            return;
        }
        long cur = sel.volume();
        long max = plugin.selections().template(player).getMaxBlocks();
        boolean conflict = !plugin.selections().isBypassed(player) && !plugin.wg().intersecting(sel).isEmpty();

        String text;
        BarColor color;
        if (conflict) {
            text = bo.conflictText;
            color = bo.conflictColor;
        } else if (max > 0 && cur >= max) {
            text = bo.fullText;
            color = bo.fullColor;
        } else {
            text = bo.normalText;
            color = bo.normalColor;
        }
        String percent = max <= 0 ? "100" : String.valueOf(Math.min(100L, cur * 100 / max));
        text = text.replace("{current}", fmt(cur))
                .replace("{max}", fmt(max))
                .replace("{percent}", percent)
                .replace("{player}", player.getName());
        Component comp = Msg.color(text);

        if (mode.equals("ACTIONBAR")) {
            player.sendActionBar(comp);
            return;
        }

        if (bar == null) {
            barKey = new NamespacedKey(plugin, "selection_" + player.getUniqueId());
            bar = Bukkit.createBossBar(
                    barKey,
                    PlainTextComponentSerializer.plainText().serialize(comp), color, bo.style);
        } else {
            bar.setTitle(PlainTextComponentSerializer.plainText().serialize(comp));
            bar.setColor(color);
        }
        double progress = max <= 0 ? 1.0 : Math.min(1.0, (double) cur / max);
        bar.setProgress(progress);
        bar.addPlayer(player);
    }

    private void hideBar() {
        if (bar != null) {
            bar.removePlayer(player);
            if (barKey != null) {
                Bukkit.removeBossBar(barKey);
            }
            bar = null;
            barKey = null;
        }
    }

    // ---------- синхронизация с WorldEdit (для подсветки sVis) ----------

    /**
     * Передаёт текущее выделение в WorldEdit-сессию игрока, чтобы sVis
     * (Selection Visualizer) сразу подсвечивал область. Работает тихим
     * fallback'ом: при любых ошибках — только debug-лог, без помех сессии.
     */
    private void syncWorldEdit() {
        if (!plugin.config().syncWorldEdit()) {
            return;
        }
        try {
            Selection sel = plugin.selections().get(player);
            if (sel == null) {
                return;
            }
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(sel.getWorld());
            com.sk89q.worldedit.LocalSession session = com.sk89q.worldedit.WorldEdit.getInstance()
                    .getSessionManager().get(BukkitAdapter.adapt(player));
            session.setRegionSelector(weWorld, new CuboidRegionSelector(weWorld, sel.min(), sel.max()));
        } catch (Throwable t) {
            plugin.dbg("WE sync failed for " + player.getName() + ": " + t);
        }
    }

    /** Сбрасывает WorldEdit-селекцию игрока при выходе из сессии. */
    private void clearWorldEdit() {
        if (!plugin.config().syncWorldEdit()) {
            return;
        }
        try {
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            com.sk89q.worldedit.LocalSession session = com.sk89q.worldedit.WorldEdit.getInstance()
                    .getSessionManager().get(BukkitAdapter.adapt(player));
            session.setRegionSelector(weWorld, null);
        } catch (Throwable t) {
            plugin.dbg("WE clear failed for " + player.getName() + ": " + t);
        }
    }

    // ---------- утилиты ----------

    private static BlockVector3 feet(Player p) {
        return BlockVector3.at(p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ());
    }

    private static String fmt(long v) {
        return String.format("%,d", v).replace(',', ' ');
    }
}