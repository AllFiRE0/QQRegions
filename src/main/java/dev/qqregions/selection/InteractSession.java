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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private int particleTimer = 0;
    private int barTimer = 0;

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
    }

    public void start() {
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setItemInOffHand(null);
        inv.setItem(SLOT_CREATE, button("create", "select.button-create"));
        inv.setItem(SLOT_SELECT, button("select", "select.button-select"));
        inv.setItem(SLOT_RESET, button("reset", "select.button-reset"));
        inv.setItem(SLOT_CANCEL, button("cancel", "select.button-cancel"));
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
            BlockVector3 feet = feet(player);
            Selection sel = new Selection(player.getWorld(), feet.withY(feet.getBlockY() + 1), feet);
            mgr.set(player, mgr.clampToWorld(sel));
            activePoint = 2;
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
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setArmorContents(armor);
        inv.setItemInOffHand(offhand);
        inv.setContents(contents);
        hideBar();
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
        activePoint = activePoint == 1 ? 2 : 1;
        player.sendMessage(plugin.lang().compPrefixed("select.interactive-select-mode",
                "point", plugin.lang().fmt("select.point-" + activePoint)));
        plugin.dbg("point switch -> " + activePoint);
    }

    public void onWheel(boolean scrollUp) {
        if (!selectingMode) {
            return;
        }
        Selection sel = plugin.selections().get(player);
        if (sel == null) {
            return;
        }
        boolean forward = plugin.config().invertWheel() != scrollUp;
        BlockVector3 cur = sel.getPos(activePoint);
        BlockVector3 next = computeMove(cur, player.getLocation(), forward);
        if (next == null) {
            player.sendMessage(plugin.lang().compPrefixed("select.point-locked"));
            return;
        }
        plugin.selections().set(player, plugin.selections().clampToWorld(sel.withPoint(activePoint, next)));
        syncWorldEdit();
        plugin.dbg("wheel: point" + activePoint + " " + cur + " -> " + next);
    }

    private BlockVector3 computeMove(BlockVector3 cur, Location eye, boolean forward) {
        int angle = plugin.config().lookAngle();
        float pitch = eye.getPitch();
        if (pitch > angle) {
            return moveY(cur, forward ? -1 : 1);
        }
        if (pitch < -angle) {
            return moveY(cur, forward ? 1 : -1);
        }
        return moveHoriz(cur, eye, forward);
    }

    private BlockVector3 moveY(BlockVector3 cur, int direction) {
        int step = plugin.config().wheelStep();
        int ny = cur.getBlockY() + direction * step;
        World w = player.getWorld();
        ny = clamp(ny, w.getMinHeight(), w.getMaxHeight() - 1);
        Selection sel = plugin.selections().get(player);
        if (sel != null) {
            BlockVector3 other = sel.getPos(activePoint == 1 ? 2 : 1);
            if (activePoint == 1) {
                ny = Math.max(ny, other.getBlockY());
            } else {
                ny = Math.min(ny, other.getBlockY());
            }
            if (ny == cur.getBlockY()) {
                return null;
            }
        }
        return BlockVector3.at(cur.getBlockX(), ny, cur.getBlockZ());
    }

    private BlockVector3 moveHoriz(BlockVector3 cur, Location eye, boolean forward) {
        double rad = Math.toRadians(eye.getYaw());
        double dx = -Math.sin(rad);
        double dz = Math.cos(rad);
        int step = plugin.config().wheelStep();
        int steps = forward ? step : -step;
        int nx = cur.getBlockX() + (int) Math.round(dx * steps);
        int nz = cur.getBlockZ() + (int) Math.round(dz * steps);
        if (steps < 0) {
            int px = eye.getBlockX();
            int pz = eye.getBlockZ();
            nx = clamp(nx, Math.min(px, cur.getBlockX()), Math.max(px, cur.getBlockX()));
            nz = clamp(nz, Math.min(pz, cur.getBlockZ()), Math.max(pz, cur.getBlockZ()));
        }
        return BlockVector3.at(nx, cur.getBlockY(), nz);
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
            return;
        }
        Config cfg = plugin.config();

        if (cfg.particles().enabled) {
            particleTimer += 5;
            if (particleTimer >= cfg.particles().updateTicks) {
                particleTimer = 0;
                renderParticles(sel, cfg.particles());
            }
        } else {
            particleTimer = 0;
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

    private void renderParticles(Selection sel, Config.ParticleOptions po) {
        World world = sel.getWorld();
        Particle particle;
        try {
            particle = Particle.valueOf(po.particleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            particle = Particle.DUST;
        }
        Object data = null;
        if (particle == Particle.DUST) {
            data = new Particle.DustOptions(po.dustColor, po.dustSize);
        }
        List<BlockVector3> pts = edgePoints(sel, po.density, po.maxPoints);
        for (BlockVector3 p : pts) {
            world.spawnParticle(particle,
                    p.getBlockX() + 0.5, p.getBlockY() + 0.5, p.getBlockZ() + 0.5,
                    po.amount, 0.0, 0.0, 0.0, po.speed, data);
        }
    }

    private static List<BlockVector3> edgePoints(Selection sel, int density, int maxPoints) {
        int d = Math.max(0, density);
        BlockVector3 mn = sel.min();
        BlockVector3 mx = sel.max();
        int x1 = mn.getBlockX(), y1 = mn.getBlockY(), z1 = mn.getBlockZ();
        int x2 = mx.getBlockX(), y2 = mx.getBlockY(), z2 = mx.getBlockZ();

        List<BlockVector3> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        addLine(out, seen, maxPoints, BlockVector3.at(x1, y1, z1), BlockVector3.at(x2, y1, z1), d);
        addLine(out, seen, maxPoints, BlockVector3.at(x1, y1, z1), BlockVector3.at(x1, y1, z2), d);
        addLine(out, seen, maxPoints, BlockVector3.at(x2, y1, z1), BlockVector3.at(x2, y1, z2), d);
        addLine(out, seen, maxPoints, BlockVector3.at(x1, y1, z2), BlockVector3.at(x2, y1, z2), d);

        addLine(out, seen, maxPoints, BlockVector3.at(x1, y2, z1), BlockVector3.at(x2, y2, z1), d);
        addLine(out, seen, maxPoints, BlockVector3.at(x1, y2, z1), BlockVector3.at(x1, y2, z2), d);
        addLine(out, seen, maxPoints, BlockVector3.at(x2, y2, z1), BlockVector3.at(x2, y2, z2), d);
        addLine(out, seen, maxPoints, BlockVector3.at(x1, y2, z2), BlockVector3.at(x2, y2, z2), d);

        addLine(out, seen, maxPoints, BlockVector3.at(x1, y1, z1), BlockVector3.at(x1, y2, z1), d);
        addLine(out, seen, maxPoints, BlockVector3.at(x2, y1, z1), BlockVector3.at(x2, y2, z1), d);
        addLine(out, seen, maxPoints, BlockVector3.at(x1, y1, z2), BlockVector3.at(x1, y2, z2), d);
        addLine(out, seen, maxPoints, BlockVector3.at(x2, y1, z2), BlockVector3.at(x2, y2, z2), d);

        return out;
    }

    private static void addLine(List<BlockVector3> out, Set<Long> seen, int maxPoints,
                                BlockVector3 a, BlockVector3 b, int density) {
        int len = Math.abs(a.getBlockX() - b.getBlockX())
                + Math.abs(a.getBlockY() - b.getBlockY())
                + Math.abs(a.getBlockZ() - b.getBlockZ());
        int steps = density <= 0 || len == 0 ? 1 : Math.max(1, len / density + 1);
        for (int i = 0; i <= steps; i++) {
            if (out.size() >= maxPoints) {
                return;
            }
            int x = a.getBlockX() + (b.getBlockX() - a.getBlockX()) * i / steps;
            int y = a.getBlockY() + (b.getBlockY() - a.getBlockY()) * i / steps;
            int z = a.getBlockZ() + (b.getBlockZ() - a.getBlockZ()) * i / steps;
            long key = ((long) (x + 30000000) << 42) | ((long) (y + 1024) << 21) | (z + 30000000);
            if (seen.add(key)) {
                out.add(BlockVector3.at(x, y, z));
            }
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

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String fmt(long v) {
        return String.format("%,d", v).replace(',', ' ');
    }
}