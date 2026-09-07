package dev.qqregions.market;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.qqregions.QQRegions;
import dev.qqregions.config.Config;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Рынок регионов: продажа (sell/buy) и аренда (rent/tenant).
 * Предложения (Offer) хранятся в market.yml. Активные аренды проверяются
 * раз в минуту (tick): завершение срока и периодическое списание (PERIOD).
 */
public final class MarketManager {

    private final QQRegions plugin;
    private final Economy economy;
    private final File file;
    private final YamlConfiguration store = new YamlConfiguration();
    private final List<Offer> offers = new ArrayList<>();

    public MarketManager(QQRegions plugin) {
        this.plugin = plugin;
        this.economy = new Economy(plugin);
        this.file = new File(plugin.getDataFolder(), "market.yml");
        load();
    }

    public Economy economy() {
        return economy;
    }

    /** Перечитать экономию после /region reload (новые настройки формата). */
    public void reload() {
        economy.reload();
    }

    public List<Offer> offers() {
        return offers;
    }

    public boolean enabled() {
        return plugin.config().market().enabled && economy.enabled();
    }

    // ---------- поиск ----------

    public Offer byId(String id) {
        if (id == null) {
            return null;
        }
        for (Offer o : offers) {
            if (o.id.toString().startsWith(id.toLowerCase(java.util.Locale.ROOT))) {
                return o;
            }
        }
        return null;
    }

    /** Живое предложение (PENDING/ACTIVE) на регион. */
    public Offer activeOn(World w, ProtectedRegion r) {
        if (w == null || r == null) {
            return null;
        }
        String key = w.getName() + ":" + r.getId();
        for (Offer o : offers) {
            if (o.status == Offer.Status.PENDING || o.status == Offer.Status.ACTIVE) {
                if ((o.world + ":" + o.region).equalsIgnoreCase(key)) {
                    return o;
                }
            }
        }
        return null;
    }

    /** live-предложения, где участвует игрок (как продавец/владелец). */
    public List<Offer> mine(UUID u) {
        List<Offer> out = new ArrayList<>();
        for (Offer o : offers) {
            if (o.status == Offer.Status.PENDING || o.status == Offer.Status.ACTIVE) {
                if (participates(o, u)) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    private static boolean participates(Offer o, UUID u) {
        return u.equals(o.seller) || u.equals(o.buyer)
                || u.equals(o.owner) || u.equals(o.tenant);
    }

    public boolean forSale(World w, ProtectedRegion r) {
        Offer o = activeOn(w, r);
        return o != null && o.kind == Offer.Kind.SALE;
    }

    public boolean forRent(World w, ProtectedRegion r) {
        Offer o = activeOn(w, r);
        return o != null && o.kind == Offer.Kind.RENT;
    }

    public double priceOf(World w, ProtectedRegion r) {
        Offer o = activeOn(w, r);
        return o == null ? 0 : o.price;
    }

    // ---------- создание ----------

    /**
     * Продажа региона. Без ника (<code>targetName</code> = null/пусто) —
     * ПУБЛИЧНОЕ объявление: любой купит мгновенно через /region buy или
     * кнопку рынка. С ником — приватное предложение (принимает покупатель).
     * @return "ok" | "no-market" | "already" | "no-target"
     */
    public String createSale(Player initiator, String targetName, double price,
                             World world, ProtectedRegion region) {
        if (!enabled()) {
            return "no-market";
        }
        if (activeOn(world, region) != null) {
            return "already";
        }
        Offer o = new Offer(UUID.randomUUID(), Offer.Kind.SALE);
        o.world = world.getName();
        o.region = region.getId();
        o.price = price;
        o.created = System.currentTimeMillis();
        o.createdBy = "SELLER";
        o.seller = initiator.getUniqueId();
        if (targetName == null || targetName.trim().isEmpty()) {
            o.buyer = null;
            o.listDurationMillis = plugin.config().market().listDurationMillis;
            o.listUntil = o.created + o.listDurationMillis;
            o.status = Offer.Status.ACTIVE;
        } else {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName.trim());
            if (target.getName() == null) {
                return "no-target";
            }
            o.buyer = target.getUniqueId();
            long timeout = plugin.config().market().offerTimeoutMillis;
            o.pendingUntil = timeout > 0 ? o.created + timeout : 0;
            o.status = Offer.Status.PENDING;
        }
        offers.add(o);
        save();
        plugin.marketHolos().refresh();
        return "ok";
    }

    /**
     * Аренда региона. Без ника — ПУБЛИЧНОЕ объявление (любой арендует
     * мгновенно через /region tenant или кнопку рынка). С ником — приватное
     * предложение (принимает арендатор).
     * @return "ok" | "no-market" | "already" | "no-target"
     */
    public String createRent(Player initiator, String targetName, double price,
                             long periodMillis, World world, ProtectedRegion region) {
        if (!enabled()) {
            return "no-market";
        }
        if (activeOn(world, region) != null) {
            return "already";
        }
        Offer o = new Offer(UUID.randomUUID(), Offer.Kind.RENT);
        o.world = world.getName();
        o.region = region.getId();
        o.price = price;
        o.periodMillis = periodMillis;
        o.created = System.currentTimeMillis();
        o.createdBy = "OWNER";
        o.owner = initiator.getUniqueId();
        o.autoRent = plugin.config().market().autoRent;
        if (targetName == null || targetName.trim().isEmpty()) {
            o.tenant = null;
            o.listDurationMillis = plugin.config().market().listDurationMillis;
            o.listUntil = o.created + o.listDurationMillis;
            o.status = Offer.Status.ACTIVE;
        } else {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName.trim());
            if (target.getName() == null) {
                return "no-target";
            }
            o.tenant = target.getUniqueId();
            long timeout = plugin.config().market().offerTimeoutMillis;
            o.pendingUntil = timeout > 0 ? o.created + timeout : 0;
            o.status = Offer.Status.PENDING;
        }
        offers.add(o);
        save();
        plugin.marketHolos().refresh();
        return "ok";
    }

    // ---------- мгновенная покупка / аренда ----------

    /** Мгновенно купить регион с публичного объявления. @return код результата. */
    public String buy(Player p, World world, ProtectedRegion region) {
        if (!enabled()) {
            return "no-market";
        }
        Offer o = available(world, region, Offer.Kind.SALE);
        if (o == null) {
            return "no-offer";
        }
        return accept(o, p);
    }

    /** Мгновенно арендовать регион с публичного объявления. @return код результата. */
    public String tenant(Player p, World world, ProtectedRegion region) {
        if (!enabled()) {
            return "no-market";
        }
        Offer o = available(world, region, Offer.Kind.RENT);
        if (o == null) {
            return "no-offer";
        }
        return accept(o, p);
    }

    /** Публичное объявление нужного типа на регионе (или null). */
    private Offer available(World w, ProtectedRegion r, Offer.Kind kind) {
        Offer o = activeOn(w, r);
        if (o != null && o.kind == kind && o.isPublicListing()) {
            return o;
        }
        return null;
    }

    // ---------- кнопки владельца ----------

    /** Включить/выключить автовозврат (только для объявления владельца RENT). */
    public String setAutoRent(Offer o, Player p, boolean on) {
        if (!isInitiator(o, p)) {
            return "not-you";
        }
        if (o.kind != Offer.Kind.RENT) {
            return "not-rent";
        }
        o.autoRent = on;
        save();
        return "ok";
    }

    /** Задать срок объявления (минуты). Объявление перевыставляется с этим сроком. */
    public String setListDuration(Offer o, Player p, long minutes) {
        if (!isInitiator(o, p)) {
            return "not-you";
        }
        if (o.kind != Offer.Kind.RENT) {
            return "not-rent";
        }
        if (minutes < 1) {
            return "bad-duration";
        }
        long dur = minutes * 60_000L;
        o.listDurationMillis = dur;
        if (o.isPublicListing()) {
            o.listUntil = System.currentTimeMillis() + dur;
        }
        save();
        return "ok";
    }

    /** Является ли игрок создателем/владельцем объявления («моё объявление»). */
    public boolean ownsOffer(Offer o, java.util.UUID u) {
        if (o.kind == Offer.Kind.SALE) {
            return u.equals(o.seller);
        }
        return u.equals(o.owner);
    }

    // ---------- принятие / отмена / отклонение ----------

    /**
     * Принять (завершить) сделку по объявлению. Для публичного объявления
     * принимает любой игрок, кроме продавца/владельца; для приватного —
     * только адресат. @return код результата: ok / not-you / no-money / no-region
     */
    public String accept(Offer o, Player p) {
        UUID u = p.getUniqueId();
        if (o.isActiveRental()) {
            return "not-ready";
        }
        if (o.status != Offer.Status.PENDING && !o.isPublicListing()) {
            return "not-ready";
        }
        if (!mayAccept(o, p)) {
            return "not-you";
        }
        World w = Bukkit.getWorld(o.world);
        ProtectedRegion r = w == null ? null : plugin.wg().byName(w, o.region);
        if (w == null || r == null) {
            o.status = Offer.Status.CANCELLED;
            save();
            return "no-region";
        }
        if (o.kind == Offer.Kind.SALE) {
            UUID buyerId = o.buyer != null ? o.buyer : u;
            if (!economy.has(buyerId, o.price)) {
                return "no-money";
            }
            economy.withdraw(buyerId, o.price);
            payOut(w, r, o.price, o.seller);
            plugin.wg().transferOwnership(w, r, buyerId, o.seller);
            o.status = Offer.Status.DONE;
            save();
            plugin.marketHolos().refresh();
            notifyBoth(o, "market.sale-done-buyer", "market.sale-done-seller",
                    buyerId, o.seller);
            return "ok";
        }
        // RENT
        UUID tenantId = o.tenant != null ? o.tenant : u;
        Config.MarketOptions m = plugin.config().market();
        if (m.rentCharge == Config.MarketOptions.RentCharge.ONCE
                || m.rentCharge == Config.MarketOptions.RentCharge.PERIOD) {
            if (!economy.has(tenantId, o.price)) {
                return "no-money";
            }
            economy.withdraw(tenantId, o.price);
            payOut(w, r, o.price, o.owner);
        }
        long now = System.currentTimeMillis();
        boolean asOwner = m.rentGrant == Config.MarketOptions.RentGrant.OWNER;
        plugin.wg().addPlayer(w, r, tenantId, asOwner);
        o.tenant = tenantId;
        o.until = now + o.periodMillis;
        o.lastCharge = now;
        o.listUntil = 0;
        o.status = Offer.Status.ACTIVE;
        save();
        plugin.marketHolos().refresh();
        notifyBoth(o, "market.rent-started-tenant", "market.rent-started-owner",
                tenantId, o.owner);
        return "ok";
    }

    /**
     * Выплата полученной суммы продавцу/владельцу с учётом конфигурации:
     * multiowner single — вся сумма инициатору; split — поровну всем
     * владельцам региона. Комиссия сервера (market.commission) списывается
     * с получателя(ей): каждый получает price x (1-rate).
     */
    private void payOut(World w, ProtectedRegion r, double price, UUID initiator) {
        Config.MarketOptions m = plugin.config().market();
        java.util.List<UUID> recipients = new ArrayList<>();
        if (m.multiowner == Config.MarketOptions.MultiOwner.SPLIT) {
            recipients.addAll(plugin.wg().ownerUuids(r));
            if (recipients.isEmpty()) {
                recipients.add(initiator);
            }
        } else {
            recipients.add(initiator);
        }
        double commissionRate = m.commission.enable ? m.commission.rate : 0.0;
        double net = price * (1.0 - commissionRate);
        double share = net / recipients.size();
        for (UUID rec : recipients) {
            economy.deposit(rec, share);
        }
    }

    private boolean mayAccept(Offer o, Player p) {
        UUID u = p.getUniqueId();
        if (o.kind == Offer.Kind.SALE) {
            if (o.buyer == null) {
                return !u.equals(o.seller);            // публичное объявление
            }
            return "SELLER".equals(o.createdBy) ? u.equals(o.buyer) : u.equals(o.seller);
        }
        if (o.tenant == null) {
            return !u.equals(o.owner);                 // публичное объявление аренды
        }
        if ("OWNER".equals(o.createdBy)) {
            return u.equals(o.tenant);
        }
        return u.equals(o.owner);
    }

    /** Создатель отзывает объявление (приватеое или публичное); активную
     *  аренду — прерывает (O_автовозврата не происходит). */
    public String cancel(Offer o, Player p) {
        if (!isInitiator(o, p)) {
            return "not-you";
        }
        if (o.isActiveRental()) {
            endRental(o, true);
            return "ok";
        }
        o.status = Offer.Status.CANCELLED;
        save();
        plugin.marketHolos().refresh();
        return "ok";
    }

    /** Контрагент отклоняет приватное предложение. */
    public String decline(Offer o, Player p) {
        if (o.status != Offer.Status.PENDING) {
            return "not-ready";
        }
        if (!mayAccept(o, p)) {
            return "not-you";
        }
        o.status = Offer.Status.DECLINED;
        save();
        plugin.marketHolos().refresh();
        return "ok";
    }

    private boolean isInitiator(Offer o, Player p) {
        UUID u = p.getUniqueId();
        if (o.kind == Offer.Kind.SALE) {
            return u.equals(o.seller);
        }
        return u.equals(o.owner);
    }

    // ---------- тик аренды ----------

    public void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Offer o : new ArrayList<>(offers)) {
            // аренда идёт: срок вышел / периодическое списание
            if (o.kind == Offer.Kind.RENT && o.status == Offer.Status.ACTIVE
                    && o.tenant != null) {
                if (now >= o.until) {
                    endRental(o, false);
                    changed = true;
                    continue;
                }
                Config.MarketOptions m = plugin.config().market();
                if (m.rentCharge == Config.MarketOptions.RentCharge.PERIOD
                        && now - o.lastCharge >= m.periodMillis) {
                    if (economy.has(o.tenant, o.price)) {
                        economy.withdraw(o.tenant, o.price);
                        World w = Bukkit.getWorld(o.world);
                        ProtectedRegion r = w == null ? null : plugin.wg().byName(w, o.region);
                        if (w != null && r != null) {
                            payOut(w, r, o.price, o.owner);
                        } else {
                            economy.deposit(o.owner, o.price);
                        }
                        o.lastCharge = now;
                        save();
                        notifyBoth(o, "market.rent-renew-tenant", "market.rent-renew-owner",
                                o.tenant, o.owner);
                    } else {
                        endRental(o, true);
                        changed = true;
                        notifyBoth(o, "market.rent-unpaid-tenant", "market.rent-unpaid-owner",
                                o.tenant, o.owner);
                    }
                }
                continue;
            }
            // публичное объявление: вышел срок объявления — убрать с рынка
            if (o.status == Offer.Status.ACTIVE && o.isPublicListing()
                    && o.listUntil > 0 && now >= o.listUntil) {
                o.status = Offer.Status.CANCELLED;
                save();
                changed = true;
                continue;
            }
            // приватное предложение: контрагент не принял за offer-timeout — снять.
            // Владельцы региона не меняются, деньги никому не переводятся.
            if (o.status == Offer.Status.PENDING && o.pendingUntil > 0
                    && now >= o.pendingUntil) {
                o.status = Offer.Status.CANCELLED;
                save();
                changed = true;
            }
        }
        if (changed) {
            plugin.marketHolos().refresh();
        }
    }

    /**
     * Завершить аренду: убрать доступ арендатору. При autoRent объявление
     * снова выставляется в маркете (автовозврат); иначе — закрывается.
     */
    private void endRental(Offer o, boolean cancelled) {
        World w = Bukkit.getWorld(o.world);
        ProtectedRegion r = w == null ? null : plugin.wg().byName(w, o.region);
        if (w != null && r != null) {
            Config.MarketOptions m = plugin.config().market();
            boolean asOwner = m.rentGrant == Config.MarketOptions.RentGrant.OWNER;
            plugin.wg().removePlayer(w, r, o.tenant, asOwner);
        }
        long now = System.currentTimeMillis();
        if (!cancelled && o.autoRent) {
            // автовозврат с автопродлением: объявление снова в маркете
            o.tenant = null;
            o.until = 0;
            o.lastCharge = 0;
            long dur = o.listDurationMillis > 0
                    ? o.listDurationMillis
                    : plugin.config().market().listDurationMillis;
            o.listDurationMillis = dur;
            o.listUntil = now + dur;
            o.status = Offer.Status.ACTIVE;
            save();
            plugin.marketHolos().refresh();
            player(o.owner).ifPresent(p -> plugin.lang().send(p, "market.rent-relisted",
                    "region", o.region, "world", o.world,
                    "price", economy().format(o.price)));
            return;
        }
        o.status = cancelled ? Offer.Status.CANCELLED : Offer.Status.DONE;
        o.until = now;
        o.tenant = null;
        save();
        plugin.marketHolos().refresh();
        player(o.owner).ifPresent(p -> plugin.lang().send(p, "market.rent-ended",
                "region", o.region, "world", o.world,
                "price", economy().format(o.price)));
    }

    // ---------- уведомления ----------

    private void notifyBoth(Offer o, String keyTenantOrBuyer, String keyOther, UUID first, UUID second) {
        player(first).ifPresent(p -> plugin.lang().send(p, keyTenantOrBuyer,
                "region", o.region,
                "world", o.world,
                "price", economy().format(o.price)));
        player(second).ifPresent(p -> plugin.lang().send(p, keyOther,
                "region", o.region,
                "world", o.world,
                "price", economy().format(o.price)));
    }

    private static java.util.Optional<Player> player(UUID u) {
        Player p = Bukkit.getPlayer(u);
        return p == null ? java.util.Optional.empty() : java.util.Optional.of(p);
    }

    // ---------- хранение ----------

    public void load() {
        offers.clear();
        load0(file);
    }

    private void load0(File f) {
        if (!f.exists()) {
            return;
        }
        try {
            YamlConfiguration c = YamlConfiguration.loadConfiguration(f);
            Object raw = c.get("offers");
            if (!(raw instanceof MemorySection section)) {
                return;
            }
            for (String id : section.getKeys(false)) {
                Offer.Kind kind;
                try {
                    kind = Offer.Kind.valueOf(
                            section.getString(id + ".kind", "SALE").toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                Offer o = new Offer(UUID.fromString(id), kind);
                o.world = section.getString(id + ".world", "");
                o.region = section.getString(id + ".region", "");
                o.seller = uuidOf(section.getString(id + ".seller"));
                o.buyer = uuidOf(section.getString(id + ".buyer"));
                o.owner = uuidOf(section.getString(id + ".owner"));
                o.tenant = uuidOf(section.getString(id + ".tenant"));
                o.createdBy = section.getString(id + ".createdBy", "SELLER");
                o.price = section.getDouble(id + ".price", 0);
                o.periodMillis = section.getLong(id + ".periodMillis", 0);
                o.created = section.getLong(id + ".created", 0);
                o.until = section.getLong(id + ".until", 0);
                o.lastCharge = section.getLong(id + ".lastCharge", 0);
                o.listUntil = section.getLong(id + ".listUntil", 0);
                o.listDurationMillis = section.getLong(id + ".listDurationMillis",
                        plugin.config().market().listDurationMillis);
                o.pendingUntil = section.getLong(id + ".pendingUntil", 0);
                o.autoRent = section.getBoolean(id + ".autoRent", plugin.config().market().autoRent);
                try {
                    o.status = Offer.Status.valueOf(
                            section.getString(id + ".status", "PENDING"));
                } catch (IllegalArgumentException e) {
                    o.status = Offer.Status.PENDING;
                }
                offers.add(o);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось прочитать market.yml: " + t.getMessage());
        }
    }

    private static UUID uuidOf(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void save() {
        YamlConfiguration c = new YamlConfiguration();
        for (Offer o : offers) {
            String base = "offers." + o.id + ".";
            c.set(base + "kind", o.kind.name());
            c.set(base + "world", o.world);
            c.set(base + "region", o.region);
            c.set(base + "seller", str(o.seller));
            c.set(base + "buyer", str(o.buyer));
            c.set(base + "owner", str(o.owner));
            c.set(base + "tenant", str(o.tenant));
            c.set(base + "createdBy", o.createdBy);
            c.set(base + "price", o.price);
            c.set(base + "periodMillis", o.periodMillis);
            c.set(base + "created", o.created);
            c.set(base + "until", o.until);
            c.set(base + "lastCharge", o.lastCharge);
            c.set(base + "listUntil", o.listUntil);
            c.set(base + "listDurationMillis", o.listDurationMillis);
            c.set(base + "pendingUntil", o.pendingUntil);
            c.set(base + "autoRent", o.autoRent);
            c.set(base + "status", o.status.name());
        }
        try {
            c.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить market.yml: " + e.getMessage());
        }
    }

    private static String str(UUID u) {
        return u == null ? null : u.toString();
    }

    /** Имя игрока для сообщений (по UUID; для незнакомых — короткий UUID). */
    public String nameOf(UUID u) {
        if (u == null) {
            return "?";
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        return op.getName() != null ? op.getName() : u.toString().substring(0, 8);
    }
}