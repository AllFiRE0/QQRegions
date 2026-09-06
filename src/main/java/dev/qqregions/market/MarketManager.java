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
     * Предложение о продаже.
     * @param buyerInitiated true — /buy (покупатель просит продать), принимает продавец;
     *                       false — /sell (продавец предлагает), принимает покупатель.
     */
    public boolean createSale(Player initiator, String targetName, double price,
                              World world, ProtectedRegion region, boolean buyerInitiated) {
        if (!enabled()) {
            return false;
        }
        if (activeOn(world, region) != null) {
            return false;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (target.getName() == null) {
            return false;
        }
        Offer o = new Offer(UUID.randomUUID(), Offer.Kind.SALE);
        o.world = world.getName();
        o.region = region.getId();
        o.price = price;
        o.created = System.currentTimeMillis();
        if (buyerInitiated) {
            o.createdBy = "BUYER";
            o.buyer = initiator.getUniqueId();
            o.seller = firstOwner(world, region);
            if (o.seller == null) {
                return false;
            }
        } else {
            o.createdBy = "SELLER";
            o.seller = initiator.getUniqueId();
            o.buyer = target.getUniqueId();
        }
        offers.add(o);
        save();
        return true;
    }

    /**
     * Предложение об аренде.
     * @param tenantInitiated true — /tenant (арендатор просит), принимает владелец;
     *                        false — /rent (владелец предлагает), принимает арендатор.
     */
    public boolean createRent(Player initiator, String targetName, double price,
                              long periodMillis, World world, ProtectedRegion region,
                              boolean tenantInitiated) {
        if (!enabled()) {
            return false;
        }
        if (activeOn(world, region) != null) {
            return false;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (target.getName() == null) {
            return false;
        }
        Offer o = new Offer(UUID.randomUUID(), Offer.Kind.RENT);
        o.world = world.getName();
        o.region = region.getId();
        o.price = price;
        o.periodMillis = periodMillis;
        o.created = System.currentTimeMillis();
        if (tenantInitiated) {
            o.createdBy = "TENANT";
            o.tenant = initiator.getUniqueId();
            o.owner = firstOwner(world, region);
            if (o.owner == null) {
                return false;
            }
        } else {
            o.createdBy = "OWNER";
            o.owner = initiator.getUniqueId();
            o.tenant = target.getUniqueId();
        }
        offers.add(o);
        save();
        return true;
    }

    private static UUID firstOwner(World world, ProtectedRegion region) {
        for (UUID u : region.getOwners().getUniqueIds()) {
            return u;
        }
        return null;
    }

    // ---------- принятие / отмена / отклонение ----------

    /**
     * Принять предложение. Валидирует, что принимает именно контрагент.
     * @return код результата: ok / not-you / no-money / done / no-region
     */
    public String accept(Offer o, Player p) {
        if (o.status != Offer.Status.PENDING) {
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
            if (!economy.has(o.buyer, o.price)) {
                return "no-money";
            }
            economy.withdraw(o.buyer, o.price);
            economy.deposit(o.seller, o.price);
            plugin.wg().transferOwnership(w, r, o.buyer, o.seller);
            o.status = Offer.Status.DONE;
            save();
            notifyBoth(o, "market.sale-done-buyer", "market.sale-done-seller",
                    o.buyer, o.seller);
            return "ok";
        }
        // RENT
        Config.MarketOptions m = plugin.config().market();
        if (m.rentCharge == Config.MarketOptions.RentCharge.ONCE
                || m.rentCharge == Config.MarketOptions.RentCharge.PERIOD) {
            if (!economy.has(o.tenant, o.price)) {
                return "no-money";
            }
            economy.withdraw(o.tenant, o.price);
            economy.deposit(o.owner, o.price);
        }
        long now = System.currentTimeMillis();
        boolean asOwner = m.rentGrant == Config.MarketOptions.RentGrant.OWNER;
        plugin.wg().addPlayer(w, r, o.tenant, asOwner);
        o.until = now + o.periodMillis;
        o.lastCharge = now;
        o.status = Offer.Status.ACTIVE;
        save();
        notifyBoth(o, "market.rent-started-tenant", "market.rent-started-owner",
                o.tenant, o.owner);
        return "ok";
    }

    private boolean mayAccept(Offer o, Player p) {
        UUID u = p.getUniqueId();
        if (o.kind == Offer.Kind.SALE) {
            if ("SELLER".equals(o.createdBy)) {
                return u.equals(o.buyer);
            }
            return u.equals(o.seller);
        }
        if ("OWNER".equals(o.createdBy)) {
            return u.equals(o.tenant);
        }
        return u.equals(o.owner);
    }

    /** Создатель предложения отзывает его (PENDING или активную аренду). */
    public String cancel(Offer o, Player p) {
        if (!isInitiator(o, p)) {
            return "not-you";
        }
        if (o.status == Offer.Status.ACTIVE && o.kind == Offer.Kind.RENT) {
            endRental(o, true);
            return "ok";
        }
        o.status = Offer.Status.CANCELLED;
        save();
        return "ok";
    }

    /** Контрагент отклоняет предложение. */
    public String decline(Offer o, Player p) {
        if (o.status != Offer.Status.PENDING) {
            return "not-ready";
        }
        if (!mayAccept(o, p)) {
            return "not-you";
        }
        o.status = Offer.Status.DECLINED;
        save();
        return "ok";
    }

    private boolean isInitiator(Offer o, Player p) {
        UUID u = p.getUniqueId();
        if (o.kind == Offer.Kind.SALE) {
            return ("SELLER".equals(o.createdBy) && u.equals(o.seller))
                    || ("BUYER".equals(o.createdBy) && u.equals(o.buyer));
        }
        return ("OWNER".equals(o.createdBy) && u.equals(o.owner))
                || ("TENANT".equals(o.createdBy) && u.equals(o.tenant));
    }

    // ---------- тик аренды ----------

    public void tick() {
        long now = System.currentTimeMillis();
        for (Offer o : new ArrayList<>(offers)) {
            if (o.status != Offer.Status.ACTIVE || o.kind != Offer.Kind.RENT) {
                continue;
            }
            if (now >= o.until) {
                endRental(o, false);
                continue;
            }
            Config.MarketOptions m = plugin.config().market();
            if (m.rentCharge == Config.MarketOptions.RentCharge.PERIOD
                    && now - o.lastCharge >= m.periodMillis) {
                if (economy.has(o.tenant, o.price)) {
                    economy.withdraw(o.tenant, o.price);
                    economy.deposit(o.owner, o.price);
                    o.lastCharge = now;
                    save();
                    notifyBoth(o, "market.rent-renew-tenant", "market.rent-renew-owner",
                            o.tenant, o.owner);
                } else {
                    endRental(o, true);
                    notifyBoth(o, "market.rent-unpaid-tenant", "market.rent-unpaid-owner",
                            o.tenant, o.owner);
                }
            }
        }
    }

    /** Завершить аренду: убрать доступ арендатору, пометить оффер. */
    private void endRental(Offer o, boolean cancelled) {
        World w = Bukkit.getWorld(o.world);
        ProtectedRegion r = w == null ? null : plugin.wg().byName(w, o.region);
        if (w != null && r != null) {
            Config.MarketOptions m = plugin.config().market();
            boolean asOwner = m.rentGrant == Config.MarketOptions.RentGrant.OWNER;
            plugin.wg().removePlayer(w, r, o.tenant, asOwner);
        }
        o.status = cancelled ? Offer.Status.CANCELLED : Offer.Status.DONE;
        o.until = System.currentTimeMillis();
        save();
    }

    // ---------- уведомления ----------

    private void notifyBoth(Offer o, String keyTenantOrBuyer, String keyOther, UUID first, UUID second) {
        player(first).ifPresent(p -> p.sendMessage(plugin.lang().compPrefixed(keyTenantOrBuyer,
                "region", o.region,
                "world", o.world,
                "price", economy().format(o.price))));
        player(second).ifPresent(p -> p.sendMessage(plugin.lang().compPrefixed(keyOther,
                "region", o.region,
                "world", o.world,
                "price", economy().format(o.price))));
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