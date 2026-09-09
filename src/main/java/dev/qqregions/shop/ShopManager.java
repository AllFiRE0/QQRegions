package dev.qqregions.shop;

import dev.qqregions.QQRegions;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Магазин флагов и расширений (меню flagshop/blocks, кнопки @shop-buy).
 * Настройки — shop.yml (цены флагов, пакеты территории «+блоки», пакеты
 * регионов «+регионы» и пользовательские товары custom-items).
 * Покупки — data.yml, НА ИГРОКА НАВСЕГДА (действуют на все его регионы):
 * список флагов, купленные пакеты площади, число купленных пакетов
 * регионов и число покупок пользовательских товаров.
 *
 * Списание через экономику рынка (Vault, как и /region sell). Если Vault/
 * экономики нет — покупка отвечает "no-economy".
 */
public final class ShopManager {

    /**
     * Товар магазина (пакет площади, пакет регионов или пользовательский).
     *
     * @param kind          area | region | custom
     * @param id            id пакета/товара из shop.yml
     * @param name          название (ячейка shop.yml "name")
     * @param material      материал кнопки (материал из shop.yml или по умолчанию)
     * @param amount        количество (блоки/регионы/выдача)
     * @param lore          кастомный лор (пользовательские товары)
     * @param price         цена; 0/отрицательная — не продаётся
     * @param maxPurchases  лимит покупок (<= 0 = безлимит/повторяемый)
     * @param boughtDisplay RED_GLASS — красное стекло «Уже куплено», HIDE — скрыть
     * @param priority      порядок кнопок в меню (меньше — раньше)
     * @param commands      команды после покупки (без условий)
     * @param conditions    условия срабатывания allow/deny команд (AND)
     * @param allowCmds     команды при выполненных условиях
     * @param denyCmds      команды при невыполненных условиях
     */
    public record ShopProduct(
            String kind,
            String id,
            String name,
            String material,
            int amount,
            List<String> lore,
            double price,
            int maxPurchases,
            String boughtDisplay,
            int priority,
            List<String> commands,
            List<String> conditions,
            List<String> allowCmds,
            List<String> denyCmds) {
    }

    private final QQRegions plugin;
    private final File shopFile;
    private final File dataFile;
    private YamlConfiguration shop;
    private final YamlConfiguration data = new YamlConfiguration();

    public ShopManager(QQRegions plugin) {
        this.plugin = plugin;
        this.shopFile = new File(plugin.getDataFolder(), "shop.yml");
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        loadShop();
        loadData();
    }

    // ---------- загрузка ----------

    public void reload() {
        loadShop();
        loadData();
    }

    private void loadShop() {
        if (!shopFile.exists()) {
            plugin.saveResource("shop.yml", false);
        }
        shop = YamlConfiguration.loadConfiguration(shopFile);
        // Недостающие блоки shop.yml (например, новые пакеты) дополняются
        // из дефолтов без перезаписи пользовательских цен.
        dev.qqregions.util.Yml.mergeDefaults(shop, dev.qqregions.util.Yml.jar(plugin, "shop.yml"));
    }

    private void loadData() {
        if (dataFile.exists()) {
            try {
                data.load(dataFile);
            } catch (Exception e) {
                plugin.getLogger().warning("Не удалось прочитать data.yml: " + e.getMessage());
            }
        }
    }

    /** Включён ли магазин: настройка + экономика (Vault) доступна. */
    public boolean enabled() {
        return shop.getBoolean("enabled", true)
                && plugin.config().market().enabled
                && plugin.market().economy().enabled();
    }

    /** Экономика (Vault) доступна и подключена в настройках. */
    public boolean economyEnabled() {
        return plugin.market().economy().enabled();
    }

    // ---------- цены флагов ----------

    /** Цена флага: из flags.prices или default-price (0 = не продаётся). */
    public double priceOf(String flagId) {
        String key = flagId == null ? "" : flagId.toLowerCase(Locale.ROOT);
        if (shop.isDouble("flags.prices." + key) || shop.isInt("flags.prices." + key)) {
            return shop.getDouble("flags.prices." + key);
        }
        return shop.getDouble("flags.default-price", 0);
    }

    // ---------- товары ----------

    /** Пакеты площади (id -> товар). */
    public List<ShopProduct> areaPacks() {
        return products("area");
    }

    /** Пакеты регионов «+регионы». */
    public List<ShopProduct> regionPacks() {
        return products("region");
    }

    /** Пользовательские товары (custom-items). */
    public List<ShopProduct> customProducts() {
        return products("custom");
    }

    /** Все товары, отсортированные по priority (равные — по типу и id). */
    public List<ShopProduct> allProducts() {
        List<ShopProduct> out = new ArrayList<>();
        out.addAll(areaPacks());
        out.addAll(regionPacks());
        out.addAll(customProducts());
        out.sort(Comparator
                .comparingInt(ShopProduct::priority)
                .thenComparing(this::kindOrder)
                .thenComparing(p -> p.id().toLowerCase(Locale.ROOT)));
        return out;
    }

    private int kindOrder(ShopProduct p) {
        return switch (p.kind()) {
            case "area" -> 0;
            case "region" -> 1;
            default -> 2;
        };
    }

    /** Товар по типу иде (`area`/`region`/`custom`); null — нет такого. */
    public ShopProduct product(String kind, String id) {
        if (kind == null || id == null) {
            return null;
        }
        for (ShopProduct p : products(kind)) {
            if (p.id().equalsIgnoreCase(id)) {
                return p;
            }
        }
        return null;
    }

    public List<ShopProduct> products(String kind) {
        String section = switch (kind == null ? "" : kind.toLowerCase(Locale.ROOT)) {
            case "area" -> "area-packs";
            case "region" -> "region-packs";
            case "custom" -> "custom-items";
            default -> null;
        };
        if (section == null) {
            return List.of();
        }
        List<ShopProduct> out = new ArrayList<>();
        ConfigurationSection sc = shop.getConfigurationSection(section);
        if (sc == null) {
            return out;
        }
        for (String id : sc.getKeys(false)) {
            out.add(readProduct(section, id));
        }
        return out;
    }

    private ShopProduct readProduct(String section, String id) {
        String base = section + "." + id + ".";
        String kind = "region".equals(section) ? "region"
                : "area".equals(section) ? "area" : "custom";
        String name = shop.getString(base + "name", id);
        double price = shop.getDouble(base + "price", 0);
        int amount = Math.max(1, shop.getInt(base + "amount",
                Math.max(1, shop.getInt(base + "blocks",
                        Math.max(1, shop.getInt(base + "regions", 1))))));
        String material = shop.getString(base + "material",
                "custom".equals(kind) ? "EMERALD"
                        : "area".equals(kind) ? "GOLD_INGOT" : "EMERALD");
        List<String> lore = shop.getStringList(base + "lore");
        int defaultMax = "custom".equals(kind) || "area".equals(kind) ? 1 : 0;
        int max = shop.getInt(base + "max-purchases", defaultMax);
        String display = shop.getString(base + "bought-display", "HIDE").toUpperCase(Locale.ROOT);
        int priority = shop.getInt(base + "priority", 0);
        List<String> commands = shop.getStringList(base + "commands");
        List<String> conditions = shop.getStringList(base + "conditions");
        List<String> allow = shop.getStringList(base + "allow-cmds");
        List<String> deny = shop.getStringList(base + "deny-cmds");
        return new ShopProduct(kind, id, name, material, amount,
                lore == null || lore.isEmpty() ? null : lore,
                price, max, "RED_GLASS".equals(display) ? "RED_GLASS" : "HIDE", priority,
                commands == null || commands.isEmpty() ? null : commands,
                conditions == null || conditions.isEmpty() ? null : conditions,
                allow == null || allow.isEmpty() ? null : allow,
                deny == null || deny.isEmpty() ? null : deny);
    }

    // ---------- покупки игрока ----------

    /** Купленные игроком флаги (id в нижнем регистре). */
    public Set<String> ownedFlags(UUID uuid) {
        Set<String> out = new HashSet<>();
        for (String s : data.getStringList(player(uuid) + ".flags")) {
            if (s != null && !s.isEmpty()) {
                out.add(s.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    public Set<String> ownedFlags(OfflinePlayer p) {
        return p == null ? Set.of() : ownedFlags(p.getUniqueId());
    }

    /** Купленные пакеты площади (id). */
    public Set<String> ownedAreaPacks(UUID uuid) {
        return new HashSet<>(data.getStringList(player(uuid) + ".area-packs"));
    }

    /** Число покупок игроком товара по типу и id (area: 0/1, region/custom: счётчик). */
    public int purchasedCount(UUID uuid, String kind, String id) {
        if (uuid == null || id == null) {
            return 0;
        }
        String base = player(uuid);
        String k = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        switch (k) {
            case "area":
                return data.getStringList(base + ".area-packs").contains(id) ? 1 : 0;
            case "region":
                return data.getInt(base + ".region-packs." + id, 0);
            case "custom":
                return data.getInt(base + ".custom-items." + id, 0);
            default:
                return 0;
        }
    }

    /** Множитель площади: максимальные блоки среди купленных пакетов. */
    public int maxBlocksExtension(UUID uuid) {
        Set<String> owned = ownedAreaPacks(uuid);
        if (owned.isEmpty()) {
            return 0;
        }
        int best = 0;
        for (ShopProduct p : areaPacks()) {
            if (owned.contains(p.id()) && p.amount() > best) {
                best = p.amount();
            }
        }
        return best;
    }

    /** Число доп. регионов от купленных пакетов «+регион». */
    public int extraRegions(UUID uuid) {
        int total = 0;
        for (ShopProduct p : regionPacks()) {
            int count = data.getInt(player(uuid) + ".region-packs." + p.id(), 0);
            total += count * p.amount();
        }
        return total;
    }

    /** Красное стекло «Уже куплено» для купленных флагов в магазине флагов? */
    public boolean flagsBoughtRedGlass() {
        return "RED_GLASS".equalsIgnoreCase(shop.getString("flags.bought-display", "HIDE"));
    }

    // ---------- покупка ----------

    /**
     * Код результата: ok / already / no-economy / not-found / no-money.
     */
    public String buyFlag(UUID uuid, String flagId) {
        String key = flagId == null ? "" : flagId.toLowerCase(Locale.ROOT);
        if (key.isEmpty() || plugin.wg().flag(key) == null) {
            return "not-found";
        }
        if (!economyEnabled()) {
            return "no-economy";
        }
        if (ownedFlags(uuid).contains(key)) {
            return "already";
        }
        double price = priceOf(key);
        if (price <= 0) {
            return "not-found";
        }
        if (!plugin.market().economy().has(uuid, price)) {
            return "no-money";
        }
        if (!plugin.market().economy().withdraw(uuid, price)) {
            return "no-economy";
        }
        String base = player(uuid) + ".flags";
        List<String> flags = new ArrayList<>(data.getStringList(base));
        flags.add(key);
        data.set(base, flags);
        save();
        return "ok";
    }

    /**
     * Единая покупка товара (area/region/custom).
     * Код результата: ok / limit / no-economy / not-found / no-money.
     */
    public String buyProduct(UUID uuid, String kind, String id) {
        ShopProduct p = product(kind, id);
        if (p == null) {
            return "not-found";
        }
        if (!economyEnabled()) {
            return "no-economy";
        }
        if (p.price() <= 0) {
            return "not-found";
        }
        int count = purchasedCount(uuid, p.kind(), p.id());
        int max = p.maxPurchases();
        if (max > 0 && count >= max) {
            return count > 0 ? "limit" : "not-found";
        }
        if (!plugin.market().economy().has(uuid, p.price())) {
            return "no-money";
        }
        if (!plugin.market().economy().withdraw(uuid, p.price())) {
            return "no-economy";
        }
        String base = player(uuid);
        if ("area".equals(p.kind())) {
            List<String> packs = new ArrayList<>(data.getStringList(base + ".area-packs"));
            if (!packs.contains(p.id())) {
                packs.add(p.id());
            }
            data.set(base + ".area-packs", packs);
        } else {
            String key = base + "." + ("region".equals(p.kind()) ? "region-packs" : "custom-items") + "." + p.id();
            data.set(key, data.getInt(key, 0) + 1);
        }
        save();
        return "ok";
    }

    // ---------- хранение ----------

    private static String player(UUID u) {
        return "players." + u;
    }

    private void save() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить data.yml: " + e.getMessage());
        }
    }
}