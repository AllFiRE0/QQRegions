package dev.qqregions.raid;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.qqregions.QQRegions;
import dev.qqregions.config.Config;
import dev.qqregions.raid.JustTeamsHook.TeamRef;
import dev.qqregions.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Рейд клана «Воришка».
 *
 * Процесс: нападающие (члены ОДНОГО чужого клана) находятся в регионе, один
 * из них жмёт кнопку «Рейд» в info-меню (шаблон other). Стартовые условия:
 *   - все нападающие подростки — члены одного клана, клан чужой для региона;
 *   - их количество >= min-attackers;
 *   - % ОНЛАЙН-членов клана, находящихся в регионе, >= online-percent;
 *   - регион не в blacklist / не banned / не на кулдауне;
 *   - (если требуется) владельцы и участники региона офлайн.
 *
 * Фаза CAPTURING: зафиксированные нападающие должны ОСТАВАТЬСЯ в регионе.
 * Выход/дисконнект/смерть-телепорт любого, появление владельца/участника
 * региона (abort-on-owner-online) — РЕЙД СРЫВАЕТСЯ. По истечении
 * capture-time случайный нападающий становится «вором»: получает доступ
 * (wg.addPlayer owner=false) на thief-time секунд, затем права снимаются.
 * После этого регион уходит в кулдаун cooldown-time.
 *
 * Требует JustTeams (мягкая зависимость, см. JustTeamsHook). Кнопка и
 * текст сообщений настраиваются в config.yml секция raid:.
 */
public final class RaidManager {

    private final QQRegions plugin;
    private JustTeamsHook teams;

    private State state = State.IDLE;
    private String worldName;
    private String regionName;
    private TeamRef clan;
    private final Set<UUID> attackers = new HashSet<>();
    private UUID thief;
    private int capturedTicks;
    private int thiefTicks;
    private int cooldownTicks;

    private int displayTicks;
    private BossBar bar;
    private NamespacedKey barKey;

    private enum State { IDLE, CAPTURING, THIEF, COOLDOWN }

    public RaidManager(QQRegions plugin) {
        this.plugin = plugin;
        this.teams = new JustTeamsHook(plugin);
    }

    public JustTeamsHook teams() {
        return teams;
    }

    public void reload() {
        teams.reload();
        if (state == State.IDLE) {
            return;
        }
        if (!plugin.config().raid().enabled) {
            reset(false);
        }
    }

    /** Псевдокоманда кнопки рейда (@raid:start). Возвращает текст ошибки/успеха (null = старт). */
    public String start(Player p) {
        if (!plugin.config().raid().enabled) {
            return "Команда рейда недоступна.";
        }
        if (!teams.enabled()) {
            return "JustTeams не установлен — кланы недоступны.";
        }
        ProtectedRegion cur = plugin.wg().current(p);
        if (cur == null) {
            return "Вы не находитесь в регионе.";
        }
        World w = p.getWorld();
        ProtectedRegion r = plugin.wg().byName(w, cur.getId());
        if (r == null) {
            return "Регион не найден.";
        }
        return start(p, r);
    }

    /** Запустить рейд в заданном регионе (регион задан явно, не по позиции игрока). */
    public String start(Player p, ProtectedRegion r) {
        if (r == null) {
            return "Регион не найден.";
        }
        if (!plugin.config().raid().enabled) {
            return "Команда рейда недоступна.";
        }
        if (!teams.enabled()) {
            return "JustTeams не установлен — кланы недоступны.";
        }
        if (state != State.IDLE) {
            return "Рейд уже идёт.";
        }
        World w = p.getWorld();
        return startChecks(p, w, r);
    }

    private String startChecks(Player p, World w, ProtectedRegion r) {
        Config.RaidOptions o = plugin.config().raid();
        if (o.isBlacklisted(r.getId()) || plugin.config().isBannedRegion(r.getId())) {
            return "С этим регионом рейды запрещены.";
        }
        // владелец/участник региона не может рейдить свой же
        if (plugin.wg().isOwner(r, p.getUniqueId()) || plugin.wg().isMember(r, p.getUniqueId())) {
            return "Вы не можете рейдить свой регион.";
        }
        TeamRef clan = teams.team(p.getUniqueId());
        if (clan == null) {
            return "Вы не состоите в клане.";
        }
        // собрать всех ЧУЖИХ игроков в регионе
        List<Player> inRegion = playersInRegion(w, r);
        if (inRegion.size() < o.minAttackers) {
            return "Слишком мало нападающих (" + inRegion.size() + "/" + o.minAttackers + ").";
        }
        List<Player> attackers = new ArrayList<>();
        for (Player pl : inRegion) {
            if (plugin.wg().isOwner(r, pl.getUniqueId()) || plugin.wg().isMember(r, pl.getUniqueId())) {
                continue; // защитник
            }
            TeamRef t = teams.team(pl.getUniqueId());
            if (t != null && t.id() == clan.id() && t.name().equals(clan.name())) {
                attackers.add(pl);
            }
        }
        if (attackers.size() < o.minAttackers) {
            return "Нападающих из вашего клана недостаточно ("
                    + attackers.size() + "/" + o.minAttackers + ").";
        }
        // % онлайн-членов клана в регионе
        List<UUID> online = teams.onlineMembers(clan);
        if (online.isEmpty()) {
            return "В клане нет онлайн-членов.";
        }
        int need = (int) Math.ceil(percentOf(online.size(), o.onlinePercent));
        long present = attackers.stream().map(Player::getUniqueId)
                .filter(online::contains).count();
        if (present < need) {
            return "В регионе слишком малый % клана (" + present + "/" + need + ").";
        }
        if (o.ownersOfflineRequired && hasDefendersOnline(w, r)) {
            return "Владелец или участник региона онлайн.";
        }
        begin(w.getName(), r, clan, attackers);
        return null;
    }

    public void begin(String worldName, ProtectedRegion region, TeamRef clan, List<Player> attackers) {
        this.worldName = worldName;
        this.regionName = region.getId();
        this.clan = clan;
        this.attackers.clear();
        for (Player pl : attackers) {
            this.attackers.add(pl.getUniqueId());
        }
        this.thief = null;
        this.capturedTicks = 0;
        this.thiefTicks = 0;
        this.cooldownTicks = 0;
        this.displayTicks = 0;
        this.state = State.CAPTURING;
        plugin.dbg("[Raid] начат: " + regionName + " от " + clan.name()
                + " нападающих " + attackers.size());
        notify(plugin.config().raid().notifyStart, Map.of(
                "region", regionName,
                "world", worldName,
                "clan", clan.name(),
                "count", String.valueOf(attackers.size()),
                "total", String.valueOf(attackers.size())));
    }

    /** Тик (раз в 5 серверных тиков = раз в 250 мс). */
    public void tick() {
        if (!plugin.config().raid().enabled || state == State.IDLE) {
            return;
        }
        switch (state) {
            case CAPTURING -> tickCapturing();
            case THIEF -> tickThief();
            case COOLDOWN -> tickCooldown();
        }
    }

    private void tickCapturing() {
        Config.RaidOptions o = plugin.config().raid();
        World w = Bukkit.getWorld(worldName);
        ProtectedRegion r = w == null ? null : plugin.wg().byName(w, regionName);
        if (w == null || r == null || clan == null) {
            reset(true);
            return;
        }
        // владелец/участник онлайн во время захвата
        if (o.abortOnOwnerOnline && hasDefendersOnline(w, r)) {
            notify(plugin.config().raid().notifyReset, ctx());
            reset(true);
            return;
        }
        // все нападающие всё ещё в регионе и онлайн
        for (UUID uid : List.copyOf(attackers)) {
            Player pl = Bukkit.getPlayer(uid);
            if (pl == null || !pl.isOnline() || !inRegion(w, r, pl)) {
                // дисконнект или выход — рейд срывается
                notify(plugin.config().raid().notifyReset, ctx());
                reset(true);
                return;
            }
        }
        capturedTicks += 5;
        int needTicks = o.captureSeconds * 20;
        if (capturedTicks >= needTicks) {
            promoteThief(w, r);
            return;
        }
        showProgress();
    }

    private void promoteThief(World w, ProtectedRegion r) {
        thief = new ArrayList<>(attackers).get(ThreadLocalRandom.current().nextInt(attackers.size()));
        // выдача прав «вору» (участник, не владелец)
        plugin.wg().addPlayer(w, r, thief, false);
        // монеты
        chargeForRaid();
        Config.RaidOptions o = plugin.config().raid();
        notify(o.notifyThief, ctxWith(
                "thief", nameOf(thief),
                "time", String.valueOf(o.thiefSeconds)));
        thiefTicks = 0;
        if (o.thiefSeconds <= 0) {
            endThiefPhase();
            return;
        }
        state = State.THIEF;
        plugin.dbg("[Raid] вор: " + nameOf(thief) + " (" + thief + ") на " + regionName);
    }

    private void tickThief() {
        World w = Bukkit.getWorld(worldName);
        ProtectedRegion r = w == null ? null : plugin.wg().byName(w, regionName);
        if (w == null || r == null) {
            endThiefPhase();
            return;
        }
        thiefTicks += 5;
        int needTicks = plugin.config().raid().thiefSeconds * 20;
        if (thiefTicks >= needTicks) {
            endThiefPhase();
            return;
        }
        showThief();
    }

    private void endThiefPhase() {
        World w = Bukkit.getWorld(worldName);
        ProtectedRegion r = w == null ? null : plugin.wg().byName(w, regionName);
        if (w != null && r != null && thief != null) {
            plugin.wg().removePlayer(w, r, thief, false);
        }
        notify(plugin.config().raid().notifyEnd, ctx());
        int cd = plugin.config().raid().cooldownSeconds;
        cooldownTicks = cd * 20;
        plugin.dbg("[Raid] завершён: " + regionName + " вор " + (thief == null ? "?" : nameOf(thief)));
        hideBar();
        thief = null;
        if (cd <= 0) {
            reset(false);
            return;
        }
        state = State.COOLDOWN;
    }

    private void tickCooldown() {
        cooldownTicks -= 5;
        if (cooldownTicks <= 0) {
            reset(false);
        }
    }

    /** Сброс: убрать права «вора», бары, кэш. silent = не слать уведомление. */
    private void reset(boolean silent) {
        if (thief != null) {
            World w = Bukkit.getWorld(worldName);
            ProtectedRegion r = w == null ? null : plugin.wg().byName(w, regionName);
            if (w != null && r != null) {
                plugin.wg().removePlayer(w, r, thief, false);
            }
        }
        hideBar();
        state = State.IDLE;
        worldName = null;
        regionName = null;
        clan = null;
        attackers.clear();
        thief = null;
        capturedTicks = thiefTicks = cooldownTicks = displayTicks = 0;
        if (!silent) {
            plugin.dbg("[Raid] сброшен");
        }
    }

    // ---------- отображение ----------

    private void showProgress() {
        Config.RaidOptions o = plugin.config().raid();
        displayTicks += 5;
        if (displayTicks < o.display.updateTicks) {
            return;
        }
        displayTicks = 0;
        int total = o.captureSeconds;
        int passed = capturedTicks / 20;
        String time = String.valueOf(Math.max(0, total - passed));
        showBar(o.display.text,
                o.display.color, o.display.style,
                ctxWith("time", time,
                        "count", String.valueOf(attackers.size()),
                        "total", String.valueOf(attackers.size()),
                        "percent", String.valueOf(percent(attackers.size(), onlineCount()))),
                progress(passed, total));
    }

    private void showThief() {
        Config.RaidOptions o = plugin.config().raid();
        int total = o.thiefSeconds;
        int passed = thiefTicks / 20;
        String time = String.valueOf(Math.max(0, total - passed));
        showBar("&2Вор &f{thief}&2: &f{time}&2 сек",
                BarColor.GREEN, o.display.style,
                ctxWith("thief", nameOf(thief), "time", time),
                progress(passed, total));
    }

    private void showBar(String tpl, BarColor color, BarStyle style, java.util.Map<String, String> ctx, double progress) {
        Config.RaidOptions.RaidDisplay d = plugin.config().raid().display;
        String text = fillContext(tpl, ctx);
        Component comp = Msg.color(text);
        if (d.mode.equals("ACTIONBAR")) {
            forEachAttacker(p -> p.sendActionBar(comp));
            return;
        }
        if (!d.mode.equals("BOSSBAR")) {
            return;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(comp);
        if (bar == null) {
            barKey = new NamespacedKey(plugin, "qqregions_raid_" + worldName + "_" + regionName);
            bar = Bukkit.createBossBar(barKey, plain, color, style);
        } else {
            bar.setTitle(plain);
            bar.setColor(color);
        }
        bar.setProgress(Math.max(0, Math.min(1.0, progress)));
        forEachAttacker(bar::addPlayer);
    }

    private void hideBar() {
        if (bar != null) {
            bar.removeAll();
            if (barKey != null) {
                Bukkit.removeBossBar(barKey);
            }
            bar = null;
            barKey = null;
        }
    }

    // ---------- монеты ----------

    private void chargeForRaid() {
        Config.RaidOptions.RaidEconomy e = plugin.config().raid().economy;
        if (!e.enabled) {
            return;
        }
        if (e.source == Config.RaidOptions.RaidSource.PLAYER) {
            economyPlayerCharge(e.percent);
        } else {
            teams.charge(clan, amountFor(teams.balanceRaw(clan)));
        }
    }

    /** Доля процента от суммы: < 1 — доля, >= 1 — проценты. */
    private static double fractionOf(int scale, double pct) {
        double p = pct >= 1 ? pct / 100.0 : pct;
        return Math.max(0, Math.min(1.0, p));
    }

    private double amountFor(double total) {
        return total * fractionOf(100, plugin.config().raid().economy.percent);
    }

    /** Списать процент от ЛИЧНОГО баланса вора (Vault). */
    private void economyPlayerCharge(double pct) {
        if (!plugin.market().economy().enabled() || thief == null) {
            return;
        }
        double have = plugin.market().economy().balance(thief);
        plugin.market().economy().withdraw(thief, have * fractionOf(100, pct));
    }

    // ---------- вспомогательное ----------

    private static double percent(int part, int whole) {
        return whole <= 0 ? 0 : Math.round(part * 10000.0 / whole) / 100.0;
    }

    private static int percentOf(int total, double pct) {
        if (pct >= 1) {
            pct = pct / 100.0;
        }
        return (int) Math.ceil(total * Math.max(0, Math.min(1.0, pct)));
    }

    private List<Player> playersInRegion(World w, ProtectedRegion r) {
        List<Player> out = new ArrayList<>();
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (pl.getWorld().equals(w) && inRegion(w, r, pl)) {
                out.add(pl);
            }
        }
        return out;
    }

    private boolean inRegion(World w, ProtectedRegion r, Player p) {
        if (!p.getWorld().equals(w)) {
            return false;
        }
        for (ProtectedRegion rw : plugin.wg().at(w, p.getLocation())) {
            if (rw.getId().equals(r.getId())) {
                return true;
            }
        }
        return false;
    }

    /** Онлайн-члены клана (на момент вычисления %). */
    private int onlineCount() {
        return teams.onlineMembers(clan).size();
    }

    private boolean hasDefendersOnline(World w, ProtectedRegion r) {
        for (UUID uid : plugin.wg().ownerUuids(r)) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null && p.isOnline()) {
                return true;
            }
        }
        return false;
    }

    private void forEachAttacker(java.util.function.Consumer<Player> action) {
        for (UUID uid : attackers) {
            Player pl = Bukkit.getPlayer(uid);
            if (pl != null) {
                try {
                    action.accept(pl);
                } catch (Throwable ignored) {
                    // невалидный игрок
                }
            }
        }
    }

    private String nameOf(UUID u) {
        if (u == null) {
            return "?";
        }
        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        return op.getName() != null ? op.getName() : u.toString().substring(0, 8);
    }

    private String fillContext(String tpl, Map<String, String> ctx) {
        String out = tpl;
        for (Map.Entry<String, String> e : ctx.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private Map<String, String> ctx() {
        return Map.of(
                "region", regionName,
                "world", worldName,
                "clan", clan == null ? "?" : clan.name(),
                "count", String.valueOf(attackers.size()),
                "total", String.valueOf(attackers.size()),
                "time", "0",
                "percent", String.valueOf(percent(attackers.size(), onlineCount())),
                "thief", thief == null ? "—" : nameOf(thief));
    }

    private Map<String, String> ctxWith(String... kv) {
        Map<String, String> m = new java.util.HashMap<>(ctx());
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private void notify(Config.RaidOptions.RaidNotify n, Map<String, String> ctx) {
        if (n == null) {
            return;
        }
        String message = fillContext(n.message, ctx);
        if (message != null && !message.isEmpty()) {
            Bukkit.broadcast(Msg.color(message));
        }
        for (String cmd : n.commands) {
            runNotifyCommand(cmd, ctx);
        }
    }

    private void runNotifyCommand(String cmd, Map<String, String> ctx) {
        String c = fillContext(cmd, ctx);
        if (c == null || c.isBlank()) {
            return;
        }
        try {
            if (c.toLowerCase(Locale.ROOT).startsWith("asconsole!")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.substring("asConsole!".length()).trim());
            } else if (c.toLowerCase(Locale.ROOT).startsWith("asplayer!")) {
                UUID first = attackers.isEmpty() ? null : attackers.iterator().next();
                Player p = first == null ? null : Bukkit.getPlayer(first);
                if (p != null) {
                    p.performCommand(c.substring("asPlayer!".length()).trim());
                }
            }
        } catch (Throwable t) {
            plugin.dbg("raid notify command failed: " + t.getMessage());
        }
    }

    private double progress(int passed, int total) {
        if (total <= 0) {
            return 1.0;
        }
        return Math.min(1.0, (double) passed / total);
    }

    // ---------- публичные геттеры для PAPI ----------

    public boolean active() {
        return state == State.CAPTURING || state == State.THIEF;
    }

    public String stateName() {
        return switch (state) {
            case CAPTURING -> "capturing";
            case THIEF -> "thief";
            case COOLDOWN -> "cooldown";
            default -> "idle";
        };
    }

    public String regionName() {
        return regionName == null ? "" : regionName;
    }

    public String worldName() {
        return worldName == null ? "" : worldName;
    }

    public String clanName() {
        return clan == null ? "" : clan.name();
    }

    public String thiefName() {
        return thief == null ? "" : nameOf(thief);
    }

    public int attackerCount() {
        return attackers.size();
    }

    /** Секунд до конца текущей фазы (для PAPI), -1 если неактивно. */
    public int remainingSeconds() {
        if (!active()) {
            return -1;
        }
        if (state == State.CAPTURING) {
            return Math.max(0, plugin.config().raid().captureSeconds - capturedTicks / 20);
        }
        return Math.max(0, plugin.config().raid().thiefSeconds - thiefTicks / 20);
    }

    public int cooldownSeconds() {
        if (state != State.COOLDOWN) {
            return 0;
        }
        return Math.max(0, cooldownTicks / 20);
    }
}