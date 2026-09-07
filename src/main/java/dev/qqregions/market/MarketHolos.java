package dev.qqregions.market;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.qqregions.QQRegions;
import dev.qqregions.config.Config;
import dev.qqregions.util.Msg;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.TextDisplay;

import java.util.HashMap;
import java.util.Map;

/**
 * Голограммы-вывески рынка (market.market-holo): большой текст над регионом,
 * который видит любой игрок, пока регион выставлен на продажу/аренду.
 *
 * Содержимое зависит от состояния объявления:
 *   публичная продажа   — «Продаётся за {price}»
 *   публичная аренда    — «Сдаётся за {price} на {time}»
 *   приватная аренда    — «Сдаётся игроку {nick} за {price} на {time}»
 *   приватная продажа   — «Ожидает покупку от {nick} за {price}»
 *
 * Вывеска существует только для активного/публичного или приватного
 * (в ожидании) объявления и исчезает после продажи/аренды/снятия.
 * Одна вывеска на регион: если регион вновь выставлен — текст обновляется.
 */
public final class MarketHolos {

    private final QQRegions plugin;
    /** key = world:region -> вывеска. */
    private final Map<String, Halo> holos = new HashMap<>();

    public MarketHolos(QQRegions plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.config().market().marketHolo.enabled;
    }

    /** Пересчитать все вывески по текущему набору оферт (вызвается при изменениях). */
    public void refresh() {
        if (!enabled()) {
            clearAll();
            return;
        }
        Map<String, Halo> next = new HashMap<>();
        for (Offer o : plugin.market().offers()) {
            if (!listingVisible(o)) {
                continue;
            }
            String key = o.world + ":" + o.region;
            Halo existing = holos.get(key);
            String text = textOf(o);
            if (existing != null && existing.text.equals(text)
                    && existing.world.equals(o.world)) {
                next.put(key, existing);              // без изменений — оставляем
                continue;
            }
            World w = org.bukkit.Bukkit.getWorld(o.world);
            ProtectedRegion r = w == null ? null : plugin.wg().byName(w, o.region);
            if (w == null || r == null) {
                continue;
            }
            if (existing != null) {
                clear(existing);
            }
            try {
                next.put(key, spawn(w, r, text));
            } catch (Throwable t) {
                plugin.dbg("market-holo: " + t.getMessage());
            }
        }
        // убрать вывески, которых больше нет
        for (Map.Entry<String, Halo> e : holos.entrySet()) {
            if (e.getKey() != null && !next.containsKey(e.getKey())) {
                clear(e.getValue());
            }
        }
        holos.clear();
        holos.putAll(next);
    }

    /** Видна ли вывеска для такого объявления (публичное ACTIVE или приватное PENDING). */
    private boolean listingVisible(Offer o) {
        if (o.status != Offer.Status.ACTIVE && o.status != Offer.Status.PENDING) {
            return false;
        }
        if (o.status == Offer.Status.ACTIVE) {
            return o.isPublicListing();
        }
        // PENDING = приватное предложение в ожидании
        return o.kind == Offer.Kind.SALE ? o.buyer != null : o.tenant != null;
    }

    private String textOf(Offer o) {
        String price = plugin.market().economy().format(o.price);
        if (o.kind == Offer.Kind.RENT) {
            String time = fmtMinutes(o.periodMillis / 60_000L);
            if (o.status == Offer.Status.PENDING && o.tenant != null) {
                return plugin.lang().fmt("market-holo.rent-pending",
                        "nick", plugin.market().nameOf(o.tenant),
                        "price", price, "time", time);
            }
            return plugin.lang().fmt("market-holo.rent",
                    "price", price, "time", time);
        }
        // SALE
        if (o.status == Offer.Status.PENDING && o.buyer != null) {
            return plugin.lang().fmt("market-holo.sale-pending",
                    "nick", plugin.market().nameOf(o.buyer), "price", price);
        }
        return plugin.lang().fmt("market-holo.sale", "price", price);
    }

    private Halo spawn(World w, ProtectedRegion r, String text) {
        Config.MarketHoloOptions h = plugin.config().market().marketHolo;
        BlockVector3 min = r.getMinimumPoint();
        BlockVector3 max = r.getMaximumPoint();
        double cx = (min.x() + max.x()) * 0.5 + h.centerXOffset + 0.5;
        double cz = (min.z() + max.z()) * 0.5 + h.centerZOffset + 0.5;
        double y = Math.min(min.y(), max.y()) + h.yOffset;
        TextDisplay td = w.spawn(new Location(w, cx, y, cz), TextDisplay.class);
        td.setText(Msg.toLegacy(Msg.color(text)));
        td.setLineWidth(h.lineWidth);
        td.setSeeThrough(true);
        td.setShadowed(true);
        return new Halo(w.getName(), text, td);
    }

    public void clearAll() {
        for (Halo h : holos.values()) {
            clear(h);
        }
        holos.clear();
    }

    private static void clear(Halo h) {
        TextDisplay d = h.display;
        if (d.isValid()) {
            d.remove();
        }
    }

    /** Дружелюбное отображение количества минут. */
    static String fmtMinutes(long minutes) {
        if (minutes >= 1440) {
            long days = minutes / 1440;
            long h = (minutes % 1440) / 60;
            return h > 0 ? days + "д " + h + "ч" : days + "д";
        }
        if (minutes >= 60) {
            long h = minutes / 60;
            long m = minutes % 60;
            return m > 0 ? h + "ч " + m + "м" : h + "ч";
        }
        return minutes + "м";
    }

    /** Живая вывеска: мир, текущий текст, сущность. */
    private static final class Halo {
        final String world;
        final String text;
        final TextDisplay display;

        Halo(String world, String text, TextDisplay display) {
            this.world = world;
            this.text = text;
            this.display = display;
        }
    }
}
