package dev.qqregions.shop;

import dev.qqregions.QQRegions;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Магазин флагов и расширений (меню flagshop/blocks, кнопки @shop-buy).
 * Настройки — shop.yml (цены флагов, пакеты территории «+блоки» и пакеты
 * регионов «+регионы»). Покупки — data.yml, НА ИГРОКА НАВСЕГДА (действуют
 * на все его регионы): список флагов, купленные пакеты площади и число
 * купленных пакетов регионов.
 *
 * Списание через экономику рынка (Vault, как и /region sell). Если Vault/
 * экономики нет — покупка отвечает "no-economy".
 */
public final class ShopManager {

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

    // ---------- цены ----------

    /** Цена флага: из flags.prices или default-price (0 = не продаётся). */
    public double priceOf(String flagId) {
        String key = flagId == null ? "" : flagId.toLowerCase(Locale.ROOT);
        if (shop.isDouble("flags.prices." + key) || shop.isInt("flags.prices." + key)) {
            return shop.getDouble("flags.prices." + key);
        }
        return shop.getDouble("flags.default-price", 0);
    }

    // ---------- пакеты ----------

    public record Pack(String id, String name, int amount, double price) {
    }

    public List<Pack> areaPacks() {
        return packs("area-packs");
    }

    public List<Pack> regionPacks() {
        return packs("region-packs");
    }

    private List<Pack> packs(String section) {
        List<Pack> out = new ArrayList<>();
        ConfigurationSection sc = shop.getConfigurationSection(section);
        if (sc == null) {
            return out;
        }
        for (String id : sc.getKeys(false)) {
            String base = section + "." + id + ".";
            String name = shop.getString(base + "name", id);
            int amount = Math.max(1, shop.getInt(base + "amount", 1));
            out.add(new Pack(id, name, amount, shop.getDouble(base + "price", 0)));
        }
        return out;
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

    /** Множитель площади: максимальные блоки среди купленных пакетов. */
    public int maxBlocksExtension(UUID uuid) {
        Set<String> owned = ownedAreaPacks(uuid);
        if (owned.isEmpty()) {
            return 0;
        }
        String prefix = player(uuid) + ".area-packs";
        int best = 0;
        for (String id : owned) {
            int blocks = packAmount("area-packs", id);
            if (blocks > best) {
                best = blocks;
            }
        }
        return best;
    }

    /** Число доп. регионов от купленных пакетов «+регион». */
    public int extraRegions(UUID uuid) {
        String base = player(uuid) + ".region-packs.";
        int total = 0;
        for (Pack p : regionPacks()) {
            int count = data.getInt(base + p.id(), 0);
            total += count * p.amount();
        }
        return total;
    }

    private int packAmount(String section, String id) {
        ConfigurationSection sc = shop.getConfigurationSection(section + "." + id);
        return sc == null ? 0 : Math.max(1, sc.getInt("amount", 1));
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

    /** Код результата: ok / already / no-economy / not-found / no-money. */
    public String buyAreaPack(UUID uuid, String packId) {
        if (areaPacks().stream().noneMatch(p -> p.id().equalsIgnoreCase(packId))) {
            return "not-found";
        }
        if (ownedAreaPacks(uuid).contains(packId)) {
            return "already";
        }
        return buyPack(uuid, "area-packs", packId);
    }

    /** Код результата: ok / no-economy / not-found / no-money (повторяемый). */
    public String buyRegionPack(UUID uuid, String packId) {
        if (regionPacks().stream().noneMatch(p -> p.id().equalsIgnoreCase(packId))) {
            return "not-found";
        }
        return buyPack(uuid, "region-packs", packId);
    }

    private String buyPack(UUID uuid, String section, String packId) {
        if (!economyEnabled()) {
            return "no-economy";
        }
        Pack pack = null;
        for (Pack p : packs(section)) {
            if (p.id().equalsIgnoreCase(packId)) {
                pack = p;
                break;
            }
        }
        if (pack == null || pack.price() <= 0) {
            return "not-found";
        }
        if (!plugin.market().economy().has(uuid, pack.price())) {
            return "no-money";
        }
        if (!plugin.market().economy().withdraw(uuid, pack.price())) {
            return "no-economy";
        }
        String base = player(uuid) + "." + section;
        if ("area-packs".equals(section)) {
            List<String> packs = new ArrayList<>(data.getStringList(base));
            packs.add(pack.id());
            data.set(base, packs);
        } else {
            data.set(base + "." + pack.id(), data.getInt(base + "." + pack.id(), 0) + 1);
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