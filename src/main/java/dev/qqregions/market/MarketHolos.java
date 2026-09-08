package dev.qqregions.market;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.qqregions.QQRegions;
import dev.qqregions.config.Config;
import dev.qqregions.util.Msg;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Голограмма-вывеска рынка (market.market-holo): для каждого активного
 * объявления — ОДНА плавающая текстовая голограмма, которая «облетает»
 * периметр региона следом за игроком и всегда смотрит на него.
 *
 * Поведение:
 *  — позиция: ближайшая к игроку точка периметра региона (по X/Z), высота Y =
 *    уровень игрока + market-holo.y-offset;
 *  — смотрит всегда на игрока (billboard CENTER);
 *  — видна любому игроку в радиусе view-distance, чей Y внутри высот региона
 *    (игрок выше/ниже региона голограмму НЕ увидит);
 *  — без зрителя прячется.
 *
 * Содержимое зависит от состояния объявления (lang.yml market-holo.*):
 *   публичная продажа   — «Продаёт {owner} за {price}»
 *   публичная аренда    — «Сдаёт {owner} за {price} на {time}»
 *   приватная аренда    — «Сдаёт {owner} игроку {nick} за {price} на {time}»
 *   приватная продажа   — «{owner} ждёт покупку от {nick} за {price}»
 *
 * Вывеска существует только для активного/публичного или приватного
 * объявления и исчезает после продажи/аренды/снятия. Одна — на регион:
 * если регион вновь выставлен — текст обновляется.
 */
public final class MarketHolos {

    private final QQRegions plugin;
    /** key = world:region -> вывеска. */
    private final Map<String, Halo> holos = new HashMap<>();
    private int tickCounter = 0;

    public MarketHolos(QQRegions plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.config().market().marketHolo.enabled;
    }

    /** Пересчитать все вывески по текущему набору оферт (вызывается при изменениях). */
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

    /** Ведёт вывески за игроками (вызывается каждые 5 тиков; внутри — раз в секунду). */
    public void tick() {
        tickCounter++;
        if (tickCounter < 4) {
            return;
        }
        tickCounter = 0;
        if (holos.isEmpty()) {
            return;
        }
        Config.MarketHoloOptions h = plugin.config().market().marketHolo;
        for (Halo halo : holos.values()) {
            World w = plugin.getServer().getWorld(halo.world);
            ProtectedRegion r = w == null ? null : plugin.wg().byName(w, halo.region);
            if (w == null || r == null) {
                halo.hide();
                continue;
            }
            Player target = nearestViewer(w, r, h.viewDistance);
            if (target == null) {
                halo.hide();
                continue;
            }
            Location pl = target.getLocation();
            BlockVector3 min = r.getMinimumPoint();
            BlockVector3 max = r.getMaximumPoint();
            double[] bp = borderPoint(pl.getX(), pl.getZ(), min, max);
            halo.follow(w, bp[0], pl.getY() + h.yOffset, bp[1], h.scale);
        }
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
        String price = plugin.market().economy().formatAmount(o.price);
        String priceSymbol = plugin.market().economy().symbol();
        String owner = plugin.market().nameOf(o.owner != null ? o.owner : o.seller);
        if (o.kind == Offer.Kind.RENT) {
            String time = plugin.lang().shortTime(o.periodMillis / 60_000L);
            if (o.status == Offer.Status.PENDING && o.tenant != null) {
                return plugin.lang().fmt("market-holo.rent-pending",
                        "owner", owner,
                        "nick", plugin.market().nameOf(o.tenant),
                        "price", price, "price-symbol", priceSymbol, "time", time);
            }
            return plugin.lang().fmt("market-holo.rent",
                    "owner", owner, "price", price, "price-symbol", priceSymbol, "time", time);
        }
        // SALE
        if (o.status == Offer.Status.PENDING && o.buyer != null) {
            return plugin.lang().fmt("market-holo.sale-pending",
                    "owner", owner,
                    "nick", plugin.market().nameOf(o.buyer), "price", price, "price-symbol", priceSymbol);
        }
        return plugin.lang().fmt("market-holo.sale", "owner", owner, "price", price, "price-symbol", priceSymbol);
    }

    /** Ближайший игрок (по X/Z), чей Y внутри высот региона и кто в радиусе. */
    private Player nearestViewer(World w, ProtectedRegion r, double dist) {
        BlockVector3 min = r.getMinimumPoint();
        BlockVector3 max = r.getMaximumPoint();
        double minX = Math.min(min.x(), max.x()), maxX = Math.max(min.x(), max.x());
        double minY = Math.min(min.y(), max.y()), maxY = Math.max(min.y(), max.y());
        double minZ = Math.min(min.z(), max.z()), maxZ = Math.max(min.z(), max.z());
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : w.getPlayers()) {
            if (!p.isOnline()) {
                continue;
            }
            Location loc = p.getLocation();
            double y = loc.getY();
            if (y < minY || y > maxY) {
                continue;            // игрок выше или ниже региона — не ведём
            }
            double d = rectDist(loc.getX(), loc.getZ(), minX, maxX, minZ, maxZ);
            if (d <= dist && d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static double rectDist(double x, double z, double minX, double maxX,
                                   double minZ, double maxZ) {
        double dx = Math.max(minX - x, x - maxX);
        if (dx < 0) {
            dx = 0;
        }
        double dz = Math.max(minZ - z, z - maxZ);
        if (dz < 0) {
            dz = 0;
        }
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Ближайшая точка ПЕРИМЕТРА прямоугольника (X/Z) к игроку. */
    static double[] borderPoint(double x, double z, BlockVector3 min, BlockVector3 max) {
        double minX = Math.min(min.x(), max.x()), maxX = Math.max(min.x(), max.x());
        double minZ = Math.min(min.z(), max.z()), maxZ = Math.max(min.z(), max.z());
        if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
            // игрок внутри по X/Z — точка на ближайшей к нему стороне
            double dL = x - minX, dR = maxX - x, dD = z - minZ, dU = maxZ - z;
            double bd = dL, bx = minX, bz = z;
            if (dR < bd) { bd = dR; bx = maxX; bz = z; }
            if (dD < bd) { bd = dD; bx = x; bz = minZ; }
            if (dU < bd) { bd = dU; bx = x; bz = maxZ; }
            return new double[]{bx, bz};
        }
        return new double[]{
                Math.max(minX, Math.min(maxX, x)),
                Math.max(minZ, Math.min(maxZ, z))
        };
    }

    private Halo spawn(World w, ProtectedRegion r, String text) {
        Config.MarketHoloOptions h = plugin.config().market().marketHolo;
        BlockVector3 min = r.getMinimumPoint();
        BlockVector3 max = r.getMaximumPoint();
        double y = Math.min(min.y(), max.y()) + h.yOffset;
        TextDisplay td = w.spawn(new Location(w, min.x() + 0.5, y, min.z() + 0.5), TextDisplay.class);
        td.text(Msg.color(text));
        td.setLineWidth(h.lineWidth);
        td.setSeeThrough(true);
        td.setShadowed(true);
        td.setAlignment(TextDisplay.TextAlignment.CENTER);
        td.setBillboard(Display.Billboard.CENTER);
        Halo halo = new Halo(w.getName(), r.getId(), text, td);
        halo.hide();                                // появится, когда рядом игрок
        return halo;
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

    /** Живая вывеска: мир, регион, текущий текст, сущность и её позиция. */
    private static final class Halo {
        final String world;
        final String region;
        final String text;
        final TextDisplay display;
        boolean visible;
        double lastX, lastY, lastZ;
        float currentScale;

        Halo(String world, String region, String text, TextDisplay display) {
            this.world = world;
            this.region = region;
            this.text = text;
            this.display = display;
            this.currentScale = -1f;
        }

        /** Показать в точке (x,y,z) с масштабом scale (телепорт только при сдвиге). */
        void follow(World w, double x, double y, double z, double scale) {
            if (!visible || Math.abs(lastX - x) > 0.3 || Math.abs(lastY - y) > 0.3
                    || Math.abs(lastZ - z) > 0.3) {
                lastX = x;
                lastY = y;
                lastZ = z;
                display.teleport(new Location(w, x, y, z));
            }
            visible = true;
            applyScale((float) scale);
        }

        /** Спрятать (масштаб 0 — невидима, остаётся на последней позиции). */
        void hide() {
            if (visible) {
                visible = false;
                applyScale(0f);
            } else if (currentScale < 0f) {
                applyScale(0f);
            }
        }

        private void applyScale(float s) {
            if (Math.abs(currentScale - s) < 0.001f) {
                return;
            }
            currentScale = s;
            display.setTransformation(new Transformation(
                    new Vector3f(),
                    new Quaternionf(),
                    new Vector3f(s, s, s),
                    new Quaternionf()));
        }
    }
}