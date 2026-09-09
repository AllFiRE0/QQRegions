package dev.qqregions.util;

import dev.qqregions.QQRegions;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Универсальный исполнитель действий команд (кнопки меню, уведомления рейда,
 * товары магазина {@code commands/allow-cmds/deny-cmds}).
 *
 * Поддерживаются префиксы действий:
 *   message!&lt;текст&gt;            — сообщение игроку (без префикса плагина)
 *   gMessage!&lt;текст&gt;           — сообщение ВСЕМ игрокам на сервере
 *   title:&lt;stop&gt;:&lt;stay&gt;:&lt;fade&gt;!&lt;текст&gt; — тайтл с таймингом в тиках
 *   title!&lt;текст&gt;               — тайтл по умолчанию (20/40/20)
 *   actionbar:&lt;тики&gt;!&lt;текст&gt; — экшнбар N тиков
 *   actionbar!&lt;текст&gt;           — экшнбар 60 тиков (3 секунды)
 *   sound!&lt;звук&gt; [громкость] [высота/питч]  — звук игроку (например sound!ENTITY_VILLAGER_YES 1 1)
 *   gSound!&lt;звук&gt; [громкость] [высота]      — звук всем онлайн игрокам
 *   asConsole!&lt;команда&gt;          — команда от консоли
 *   asPlayer!&lt;команда&gt;           — команда от имени игрока
 *   delay:&lt;тики&gt;!&lt;действие&gt;    — выполнить вложенное действие с задержкой
 *
 * Перед выполнением текст проходит {@code Papi.set}: заполнители {@code %...%}
 * и {@code \{%...\}} раскрываются для игрока.
 */
public final class Actions {

    private static final Pattern DELAY = Pattern.compile("^(\\d+)!(.*)$", Pattern.DOTALL);
    private static final Pattern TITLE_TIMING = Pattern.compile("^(\\d+):(\\d+):(\\d+)!(.*)$", Pattern.DOTALL);

    /** Базовые префиксы действий (для isAction). */
    private static final String[] PREFIXES = {
            "message!", "gmessage!", "title:", "title!", "actionbar:", "actionbar!",
            "gsound!", "sound!", "asconsole!", "asplayer!", "delay:"
    };

    private Actions() {
    }

    /** Является ли строка действием (а не обычной команды игрока). */
    public static boolean isAction(String raw) {
        if (raw == null) {
            return false;
        }
        String s = raw.toLowerCase(Locale.ROOT).trim();
        for (String p : PREFIXES) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** Выполнить список действий последовательно (пустые строки пропускаются). */
    public static void runAll(QQRegions plugin, Player p, List<String> actions) {
        if (actions == null) {
            return;
        }
        for (String a : actions) {
            if (a != null && !a.trim().isEmpty()) {
                run(plugin, p, a);
            }
        }
    }

    public static void run(QQRegions plugin, Player p, String raw) {
        if (raw == null) {
            return;
        }
        String msg = Papi.set(p, raw).trim();
        if (msg.isEmpty()) {
            return;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        String body;

        if (lower.startsWith("delay:")) {
            body = msg.substring("delay:".length()).trim();
            Matcher m = DELAY.matcher(body);
            if (!m.matches() || m.group(2).trim().isEmpty()) {
                return;
            }
            final String next = m.group(2).trim();
            final Player fp = p;
            int ticks = parseSafe(m.group(1), 1);
            Bukkit.getScheduler().runTaskLater(plugin, () -> run(plugin, fp, next), Math.max(1, ticks));
            return;
        }
        if (lower.startsWith("message!")) {
            body = msg.substring("message!".length()).trim();
            if (!body.isEmpty()) {
                p.sendMessage(Msg.color(body));
            }
            return;
        }
        if (lower.startsWith("gmessage!")) {
            body = msg.substring("gMessage!".length()).trim();
            if (!body.isEmpty()) {
                Bukkit.broadcast(Msg.color(body));
            }
            return;
        }
        if (lower.startsWith("title:")) {
            body = msg.substring("title:".length()).trim();
            Matcher m = TITLE_TIMING.matcher(body);
            int fi = 20;
            int st = 40;
            int fo = 20;
            String text = body;
            if (m.matches()) {
                fi = parseSafe(m.group(1), 20);
                st = parseSafe(m.group(2), 40);
                fo = parseSafe(m.group(3), 20);
                text = m.group(4);
            }
            p.sendTitle(Msg.color(text), Msg.color(""), fi, st, fo);
            return;
        }
        if (lower.startsWith("title!")) {
            body = msg.substring("title!".length()).trim();
            p.sendTitle(Msg.color(body), Msg.color(""), 20, 40, 20);
            return;
        }
        if (lower.startsWith("actionbar:")) {
            body = msg.substring("actionbar:".length()).trim();
            Matcher m = DELAY.matcher(body);
            if (m.matches()) {
                String text = m.group(2);
                int ticks = parseSafe(m.group(1), 60);
                plugin.lang().sendActionbar(p, text, Math.max(1, (ticks + 19) / 20));
            } else {
                plugin.lang().sendActionbar(p, body, 3);
            }
            return;
        }
        if (lower.startsWith("actionbar!")) {
            body = msg.substring("actionbar!".length()).trim();
            plugin.lang().sendActionbar(p, body, 3);
            return;
        }
        if (lower.startsWith("gsound!")) {
            body = msg.substring("gSound!".length()).trim();
            playSoundAll(body);
            return;
        }
        if (lower.startsWith("sound!")) {
            body = msg.substring("sound!".length()).trim();
            playSound(p, body);
            return;
        }
        if (lower.startsWith("asconsole!")) {
            body = msg.substring("asConsole!".length()).trim();
            if (!body.isEmpty()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), body);
            }
            return;
        }
        if (lower.startsWith("asplayer!")) {
            body = msg.substring("asPlayer!".length()).trim();
            if (!body.isEmpty() && p.isOnline()) {
                p.performCommand(body);
            }
        }
    }

    private static void playSound(Player p, String spec) {
        if (spec == null || spec.isEmpty()) {
            return;
        }
        String[] parts = spec.split("\\s+");
        String name = parts[0].toUpperCase(Locale.ROOT);
        float vol = (float) parseDouble(parts.length > 1 ? parts[1] : "1", 1.0);
        float pitch = (float) parseDouble(parts.length > 2 ? parts[2] : "1", 1.0);
        try {
            Sound snd = Sound.valueOf(name);
            p.playSound(p.getLocation(), snd, vol, pitch);
        } catch (Throwable ignored) {
            try {
                p.playSound(p.getLocation(), name, vol, pitch);
            } catch (Throwable ignored2) {
                // неизвестный звук — молча пропускаем
            }
        }
    }

    private static void playSoundAll(String spec) {
        Sound snd = null;
        String name = null;
        float vol = 1.0f;
        float pitch = 1.0f;
        if (spec != null && !spec.isEmpty()) {
            String[] parts = spec.split("\\s+");
            String n = parts[0].toUpperCase(Locale.ROOT);
            vol = (float) parseDouble(parts.length > 1 ? parts[1] : "1", 1.0);
            pitch = (float) parseDouble(parts.length > 2 ? parts[2] : "1", 1.0);
            try {
                snd = Sound.valueOf(n);
            } catch (Throwable ignored) {
                name = n;
            }
        }
        final Sound sound = snd;
        final String soundName = name;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                if (sound != null) {
                    p.playSound(p.getLocation(), sound, vol, pitch);
                } else {
                    p.playSound(p.getLocation(), soundName, vol, pitch);
                }
            } catch (Throwable ignored) {
                // незвучий онлайн-игрок — пропускаем
            }
        }
    }

    private static int parseSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}