package dev.qqregions.selection;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.qqregions.QQRegions;
import dev.qqregions.config.Config;
import dev.qqregions.util.Msg;
import dev.qqregions.util.Papi;
import dev.qqregions.wg.Wg;
import net.kyori.adventure.text.Component;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Общие вычисления статуса выделения: текст/цвет/прогресс боссбара и
 * дополнительной информации ({height-top}, {height-bottom}, конфликты).
 * Используется интерактивным select (InteractSession), командными
 * выделениями (SelectionManager) и PAPI-заполнителями.
 */
public final class SelectStatus {

    private SelectStatus() {
    }

    public static long current(Selection sel) {
        return sel == null ? 0 : sel.volume();
    }

    public static long max(QQRegions plugin, Player p) {
        return plugin.selections().effectiveMaxBlocks(p);
    }

    /** Пересекает ли выделение хоть один существующий регион (без байпаса). */
    public static boolean conflict(QQRegions plugin, Player p, Selection sel) {
        return !plugin.selections().isBypassed(p) && !plugin.wg().intersecting(sel).isEmpty();
    }

    public static boolean full(QQRegions plugin, Player p, Selection sel) {
        long max = max(plugin, p);
        return max > 0 && sel.volume() >= max;
    }

    public static BarColor color(QQRegions plugin, Player p, Selection sel, Config.BossBarOptions bo) {
        if (conflict(plugin, p, sel)) {
            return bo.conflictColor;
        }
        return full(plugin, p, sel) ? bo.fullColor : bo.normalColor;
    }

    public static double progress(QQRegions plugin, Player p, Selection sel) {
        long max = max(plugin, p);
        return max <= 0 ? 1.0 : Math.min(1.0, (double) sel.volume() / max);
    }

    /** Цветной текст статуса (normal/full/conflict) с подстановкой {заполнителей}. */
    public static Component text(QQRegions plugin, Player p, Selection sel, Config.BossBarOptions bo) {
        long cur = sel.volume();
        long mx = max(plugin, p);
        String text;
        if (conflict(plugin, p, sel)) {
            text = bo.conflictText;
        } else if (full(plugin, p, sel)) {
            text = bo.fullText;
        } else {
            text = bo.normalText;
        }
        String percent = mx <= 0 ? "100" : String.valueOf(Math.min(100L, cur * 100 / mx));
        // {value-color} — цвет перед {current}: &f в норме, красный при лимите.
        String valueColor = full(plugin, p, sel)
                ? plugin.lang().get("select-status.value-full")
                : bo.valueColor;
        text = text.replace("{value-color}", valueColor)
                .replace("{current}", fmt(cur))
                .replace("{max}", fmt(mx))
                .replace("{percent}", percent)
                .replace("{player}", p.getName());
        return Msg.color(Papi.set(p, text));
    }

    // ---------- дополнительная информация о выделении ----------

    /** Сколько блоков от ног игрока до верхней (maxY) границы выделения. */
    public static int heightTop(Selection sel, Player p) {
        return sel.max().getBlockY() - p.getLocation().getBlockY();
    }

    /** Сколько блоков от ног игрока до нижней (minY) границы выделения. */
    public static int heightBottom(Selection sel, Player p) {
        return p.getLocation().getBlockY() - sel.min().getBlockY();
    }

    /** Чужие регионы (не принадлежащие игроку), пересекающие выделение.
     *  Используется прямое владение/участие (isMember), а НЕ role(): последняя
     *  для админа/op возвращает OWNER для всех регионов, и конфликт с чужой
     *  областью превращался бы в 0. Админ тоже должен видеть чужие области. */
    public static List<ProtectedRegion> foreignIntersecting(QQRegions plugin, Selection sel, Player p) {
        List<ProtectedRegion> out = new ArrayList<>();
        for (ProtectedRegion r : plugin.wg().intersecting(sel)) {
            if (plugin.wg().isMember(r, p)) {
                continue;
            }
            out.add(r);
        }
        return out;
    }

    private static String foreignNames(QQRegions plugin, Selection sel, Player p) {
        List<ProtectedRegion> foreign = foreignIntersecting(plugin, sel, p);
        List<String> names = new ArrayList<>(foreign.size());
        for (ProtectedRegion r : foreign) {
            names.add(r.getId());
        }
        return String.join(", ", names);
    }

    /** Собрать текст доп. инфо-экшнбара: высоты + конфликты + база. */
    public static String renderInfo(QQRegions plugin, Player p, Selection sel, String template) {
        List<ProtectedRegion> foreign = foreignIntersecting(plugin, sel, p);
        long mx = max(plugin, p);
        long cur = sel.volume();
        String percent = mx <= 0 ? "100" : String.valueOf(Math.min(100L, cur * 100 / mx));
        String text = template == null ? "" : template;
        String yes = plugin.lang().get("select-status.yes");
        String no = plugin.lang().get("select-status.no");
        text = text.replace("{height-top}", fmt(heightTop(sel, p)))
                .replace("{height-bottom}", fmt(heightBottom(sel, p)))
                .replace("{conflict}", foreign.isEmpty() ? no : yes)
                .replace("{conflict-regions}", foreign.isEmpty() ? no : foreignNames(plugin, sel, p))
                .replace("{conflict-count}", fmt(foreign.size()))
                .replace("{current}", fmt(cur))
                .replace("{max}", fmt(mx))
                .replace("{percent}", percent)
                .replace("{player}", p.getName());
        return Papi.set(p, text);
    }

    public static String fmt(long v) {
        return String.format("%,d", v).replace(',', ' ');
    }
}