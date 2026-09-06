package dev.qqregions.config;

import dev.qqregions.QQRegions;
import dev.qqregions.util.Colors;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Обёртка над config.yml с типизированными геттерами.
 */
public class Config {

    private final QQRegions plugin;
    private FileConfiguration cfg;

    private String commandName;
    private List<String> aliases = new ArrayList<>();
    private Set<String> disabledWorlds = new HashSet<>();
    private Set<String> bannedRegions = new HashSet<>();
    private Pattern namePattern;
    private boolean forceLowercase;

    private final List<SelectionTemplate> templates = new ArrayList<>();

    private int wheelSlots;
    private double wheelDistance;
    private int wheelShiftSpeed;
    private boolean invertWheel;
    private final Map<String, Material> buttonMaterials = new HashMap<>();
    private List<String> blockedCommands = new ArrayList<>();
    private boolean syncWorldEdit = true;
    private boolean debug;
    private PointStyle point1;
    private PointStyle point2;
    private String viewMode = "PARTICLES";
    private int viewDistance = 200;
    private int viewMaxBlocks = 500;
    private float viewBlockScale = 0.35f;
    private int viewDotsPerEdge = 16;
    private boolean commandSelectionView = true;
    private int viewHideAfter = 5;
    private int viewHideDistance = 0;

    private ParticleOptions particles;
    private BossBarOptions bossbar;
    private HighlightOptions highlight;
    private MarketOptions market;
    private RaidOptions raid;

    /** flags-menu.whitelist: флаги, доступные всем бесплатно (пусто = прежнее поведение). */
    private Set<String> flagsMenuWhitelist = new HashSet<>();
    /** flags-menu.shop-ignore: флаги, скрытые из магазина (только по праву). */
    private Set<String> flagsShopIgnore = new HashSet<>();
    /** flags-names: пользовательские названия флагов (key = id флага, value = название). */
    private final Map<String, String> flagNameReplace = new HashMap<>();
    /** regions.max-regions: лимит регионов на игрока (0 = без лимита). */
    private int maxRegions;

    public Config(QQRegions plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        cfg = plugin.getConfig();

        commandName = cfg.getString("command.name", "region");
        aliases = new ArrayList<>(cfg.getStringList("command.aliases"));

        disabledWorlds = new HashSet<>(lower(cfg.getStringList("restrictions.disabled-worlds")));
        bannedRegions = new HashSet<>(lower(cfg.getStringList("restrictions.banned-regions")));

        namePattern = Pattern.compile(cfg.getString("region-name.regex", "[A-Za-zА-Яа-я0-9_-]{3,32}"));
        forceLowercase = cfg.getBoolean("region-name.force-lowercase", true);

        templates.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("selection-templates");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection t = sec.getConfigurationSection(key);
                if (t == null) {
                    continue;
                }
                templates.add(SelectionTemplate.fromMap(key, t.getValues(false)));
            }
        }
        templates.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));

        wheelSlots = Math.max(1, cfg.getInt("interactive.wheel-slots", 2));
        wheelDistance = Math.max(0.1, cfg.getDouble("interactive.wheel-distance", 1));
        wheelShiftSpeed = Math.max(1, cfg.getInt("interactive.wheel-shift-speed", 4));
        invertWheel = cfg.getBoolean("interactive.invert-wheel", false);
        blockedCommands = new ArrayList<>(lower(cfg.getStringList("interactive.blocked-commands")));
        syncWorldEdit = cfg.getBoolean("interactive.sync-worldedit", true);
        debug = cfg.getBoolean("debug", false);

        point1 = new PointStyle(cfg.getConfigurationSection("interactive.select-points.point-1"),
                Material.GRAY_STAINED_GLASS_PANE, Color.fromRGB(0x6b6b6b), Material.GRAY_CONCRETE);
        point2 = new PointStyle(cfg.getConfigurationSection("interactive.select-points.point-2"),
                Material.YELLOW_STAINED_GLASS_PANE, Color.fromRGB(0xffa500), Material.ORANGE_CONCRETE);

        viewMode = cfg.getString("interactive.view-mode", "PARTICLES").toUpperCase(java.util.Locale.ROOT);
        viewDistance = cfg.getInt("interactive.view-distance", 200);
        viewMaxBlocks = cfg.getInt("interactive.view-max-blocks", 500);
        viewBlockScale = (float) cfg.getDouble("interactive.view-block-scale", 0.35);
        viewDotsPerEdge = Math.max(2, cfg.getInt("interactive.view-dots-per-edge", 16));
        commandSelectionView = cfg.getBoolean("interactive.command-selection-view", true);
        viewHideAfter = Math.max(0, cfg.getInt("interactive.view-hide-after", 5));
        viewHideDistance = Math.max(0, cfg.getInt("interactive.view-hide-distance", 0));

        buttonMaterials.clear();
        ConfigurationSection btns = cfg.getConfigurationSection("interactive.buttons");
        if (btns != null) {
            for (String key : btns.getKeys(false)) {
                String mat = btns.getString(key + ".material");
                if (mat != null) {
                    buttonMaterials.put(key, Material.matchMaterial(mat));
                }
            }
        }

        particles = new ParticleOptions(cfg.getConfigurationSection("particles"));
        bossbar = new BossBarOptions(cfg.getConfigurationSection("bossbar"));
        highlight = new HighlightOptions(cfg.getConfigurationSection("highlight"));
        market = new MarketOptions(cfg.getConfigurationSection("market"));
        raid = new RaidOptions(cfg.getConfigurationSection("raid"));

        flagsMenuWhitelist = new HashSet<>(lower(cfg.getStringList("flags-menu.whitelist")));
        flagsShopIgnore = new HashSet<>(lower(cfg.getStringList("flags-menu.shop-ignore")));
        flagNameReplace.clear();
        ConfigurationSection fn = cfg.getConfigurationSection("flags-names");
        if (fn != null) {
            for (String k : fn.getKeys(false)) {
                String v = fn.getString(k);
                if (v != null) {
                    flagNameReplace.put(k.toLowerCase(java.util.Locale.ROOT), v);
                }
            }
        }
        maxRegions = Math.max(0, cfg.getInt("regions.max-regions", 0));
    }

    private static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            out.add(s.toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }

    public String commandName() {
        return commandName;
    }

    public List<String> aliases() {
        return aliases;
    }

    public boolean isWorldDisabled(String world) {
        return disabledWorlds.contains(world.toLowerCase(java.util.Locale.ROOT));
    }

    public boolean isWorldDisabled(World world) {
        return isWorldDisabled(world.getName());
    }

    public boolean isBannedRegion(String name) {
        return bannedRegions.contains(name.toLowerCase(java.util.Locale.ROOT));
    }

    public Set<String> bannedRegions() {
        return bannedRegions;
    }

    public Pattern namePattern() {
        return namePattern;
    }

    public boolean forceLowercase() {
        return forceLowercase;
    }

    public String normalizeName(String name) {
        return forceLowercase ? name.toLowerCase(java.util.Locale.ROOT) : name;
    }

    /** Шаблон прав с наибольшим приоритетом, которому игрок удовлетворяет. */
    public SelectionTemplate templateFor(OfflinePlayer player) {
        for (SelectionTemplate t : templates) {
            if (t.matches(player)) {
                return t;
            }
        }
        return templates.isEmpty() ? new SelectionTemplate("default", 1, 10000, 100, 16, "", "") : templates.get(templates.size() - 1);
    }

    public int wheelSlots() {
        return wheelSlots;
    }

    public double wheelDistance() {
        return wheelDistance;
    }

    public int wheelShiftSpeed() {
        return wheelShiftSpeed;
    }

    public boolean invertWheel() {
        return invertWheel;
    }

    public List<String> blockedCommands() {
        return blockedCommands;
    }

    public boolean syncWorldEdit() {
        return syncWorldEdit;
    }

    public boolean debug() {
        return debug;
    }

    public PointStyle pointStyle(int point) {
        return point == 1 ? point1 : point2;
    }

    public String viewMode() {
        return viewMode;
    }

    public boolean blockView() {
        return "BLOCKS".equals(viewMode);
    }

    public int viewDistance() {
        return viewDistance;
    }

    public int viewMaxBlocks() {
        return viewMaxBlocks;
    }

    public int viewDotsPerEdge() {
        return viewDotsPerEdge;
    }

    public float viewBlockScale() {
        return viewBlockScale;
    }

    public boolean commandSelectionView() {
        return commandSelectionView;
    }

    /** Секунд без изменений, после которых подсветка скрывается (0 = держать всегда). */
    public int viewHideAfter() {
        return viewHideAfter;
    }

    /** Дистанция в блоках, дальше которой подсветка скрывается (0 = не использовать). */
    public int viewHideDistance() {
        return viewHideDistance;
    }

    /** Настройки рынка / аренды (Vault + sell/rent/buy). */
    public MarketOptions market() {
        return market;
    }

    /** viewHideAfter в «вызовах» тика плагина (тик раз в 5 серверных тиков). */
    public int viewHideAfterCalls() {
        return viewHideAfter <= 0 ? 0 : viewHideAfter * 4;
    }

    public Material buttonMaterial(String id) {
        Material m = buttonMaterials.get(id);
        return m == null ? Material.BARRIER : m;
    }

    public ParticleOptions particles() {
        return particles;
    }

    public BossBarOptions bossbar() {
        return bossbar;
    }

    /** Настройки подсветки регионов (команды /region visible и флаг territory-visible). */
    public HighlightOptions highlight() {
        return highlight;
    }

    /** Настройки рейда клана «Воришка» (кнопка в меню info, шаблон other). */
    public RaidOptions raid() {
        return raid;
    }

    /** Флаги, видимые всем без права (пустой список = как раньше, по правам). */
    public Set<String> flagsMenuWhitelist() {
        return flagsMenuWhitelist;
    }

    /** Флаги, скрытые из магазина и видимые только по праву <prefix><флаг>. */
    public Set<String> flagsShopIgnore() {
        return flagsShopIgnore;
    }

    /** Название флага из config.yml flags-names или исходный id, если замены нет. */
    public String flagName(String id) {
        if (id == null) {
            return "";
        }
        String mapped = flagNameReplace.get(id.toLowerCase(java.util.Locale.ROOT));
        return mapped == null ? id : mapped;
    }

    /** Лимит регионов на игрока (0 = без лимита). */
    public int maxRegions() {
        return maxRegions;
    }

    // ---------------- вложенные опции ----------------

    /** Стиль точки выделения: панель хотбара, цвет частиц/свечения, блок-дисплей. */
    public static class PointStyle {
        public final Material pane;
        public final Color highlight;
        public final Material block;

        PointStyle(ConfigurationSection s, Material defaultPane, Color defaultColor, Material defaultBlock) {
            if (s == null) {
                pane = defaultPane;
                highlight = defaultColor;
                block = defaultBlock;
                return;
            }
            String mat = s.getString("pane");
            pane = materialOr(mat, defaultPane);
            highlight = Colors.bukkit(s.getString("highlight"), defaultColor);
            block = materialOr(s.getString("block"), defaultBlock);
        }

        private static Material materialOr(String name, Material def) {
            if (name == null) {
                return def;
            }
            Material m = Material.matchMaterial(name);
            return m == null ? def : m;
        }
    }

    public static class ParticleOptions {
        public final boolean enabled;
        public final int updateTicks;
        public final String particleName;
        public final Color dustColor;
        public final float dustSize;
        public final int amount;
        public final double speed;
        public final int density;
        public final int maxPoints;

        ParticleOptions(ConfigurationSection s) {
            enabled = s == null ? true : s.getBoolean("enabled", true);
            updateTicks = Math.max(1, s == null ? 10 : s.getInt("update-ticks", 10));
            particleName = s == null ? "DUST" : s.getString("particle", "DUST");
            dustColor = Colors.bukkit(s == null ? "#00ff00" : s.getString("dust-color", "#00ff00"), Color.fromRGB(0x00FF00));
            dustSize = (float) (s == null ? 0.6 : s.getDouble("dust-size", 0.6));
            amount = s == null ? 1 : s.getInt("amount", 1);
            speed = s == null ? 0 : s.getDouble("speed", 0);
            density = s == null ? 2 : s.getInt("point-density", 2);
            maxPoints = s == null ? 4000 : s.getInt("max-points", 4000);
        }
    }

/**
     * Подсветка регионов: /region visible + флаг territory-visible.
     * Контур региона рисуется окном showMillis, потом гаснет сам; повторное
     * срабатывание флага — не чаще cooldownMillis на игрока и регион.
     */
    public static class HighlightOptions {
        public final boolean enabled;
        public final boolean flagEnabled;
        public final String type;
        public final int showSeconds;
        public final long showMillis;
        public final int scanTicks;
        public final long cooldownMillis;
        public final float blockScale;
        public final Material block;
        /** Скрывать подсветку при выходе игрока из региона (вход/выход по флагу). */
        public final boolean hideOnExit;
        public final ParticleOptions particles;

        HighlightOptions(ConfigurationSection s) {
            if (s == null) {
                enabled = true;
                flagEnabled = true;
                type = "PARTICLES";
                showSeconds = 10;
                showMillis = 10_000L;
                scanTicks = 20;
                cooldownMillis = 10_000L;
                blockScale = 0.35f;
                block = Material.GLASS;
                hideOnExit = true;
                particles = new ParticleOptions(null);
                return;
            }
            enabled = s.getBoolean("enabled", true);
            flagEnabled = s.getBoolean("flag-enabled", true);
            type = s.getString("type", "PARTICLES").toUpperCase(java.util.Locale.ROOT);
            showSeconds = Math.max(1, s.getInt("show-seconds", 10));
            showMillis = showSeconds * 1000L;
            scanTicks = Math.max(1, s.getInt("scan-ticks", 20));
            cooldownMillis = Math.max(0, s.getInt("cooldown-seconds", 10)) * 1000L;
            blockScale = (float) s.getDouble("block-scale", 0.35);
            String mat = s.getString("block", "GLASS");
            Material m = Material.matchMaterial(mat);
            block = m == null ? Material.GLASS : m;
            hideOnExit = s.getBoolean("hide-on-exit", true);
            particles = new ParticleOptions(s.getConfigurationSection("particles"));
        }
    }

    public static class BossBarOptions {
        public final boolean enabled;
        public final String mode;
        public final int updateTicks;
        public final BarStyle style;
        public final BarColor normalColor;
        public final String normalText;
        public final BarColor fullColor;
        public final String fullText;
        public final BarColor conflictColor;
        public final String conflictText;
        /** Цвет-заполнитель перед {current} ({value-color}): &f в норме, &c на лимите. */
        public final String valueColor;

        BossBarOptions(ConfigurationSection s) {
            enabled = s.getBoolean("enabled", true);
            mode = s.getString("mode", "BOSSBAR").toUpperCase(java.util.Locale.ROOT);
            updateTicks = Math.max(1, s.getInt("update-ticks", 5));
            BarStyle st;
            try {
                st = BarStyle.valueOf(s.getString("style", "SEGMENTED_10"));
            } catch (IllegalArgumentException e) {
                st = BarStyle.SEGMENTED_10;
            }
            style = st;
            normalColor = Colors.bar(s.getString("normal.color", "WHITE"), BarColor.WHITE);
            normalText = s.getString("normal.text", "&8[{current}&8/&8{max}&8] &7блоков");
            fullColor = Colors.bar(s.getString("full.color", "RED"), BarColor.RED);
            fullText = s.getString("full.text", "&c{value-color}{current}&8/&8{max}&c — максимум блоков!");
            conflictColor = Colors.bar(s.getString("conflict.color", "YELLOW"), BarColor.YELLOW);
            conflictText = s.getString("conflict.text", "&eВыделение пересекает чужой регион!");
            valueColor = s.getString("value-color", "&f");
        }
    }

    /**
     * Рынок: продажа и аренда регионов через Vault.
     *   economy.enabled        — использовать Vault
     *   economy.symbol         — знак валюты (например ₽, $)
     *   economy.symbol-position — BEFORE | AFTER
     *   economy.decimal-places — сколько знаков после запятой (0-2)
     *   economy.grouping       — группировать разряды (1000 -> 1 000)
     *   economy.group-separator — разделитель разрядов ("," / "." / " ")
     *   economy.decimal-separator — разделитель дробной части ("." / ",")
     *   rent.grant             — MEMBER (арендатор участник) | OWNER (владелец)
     *   rent.charge            — ONCE (платёж один раз за срок) | PERIOD (списывать каждый период)
     *   rent.period-minutes    — период списания/перепроверки (при PERIOD)
     */
    public static class MarketOptions {
        public final boolean enabled;
        public final boolean economyEnabled;
        public final String symbol;
        public final SymbolPosition symbolPosition;
        public final int decimalPlaces;
        public final boolean grouping;
        public final String groupSeparator;
        public final String decimalSeparator;
        public final RentGrant rentGrant;
        public final RentCharge rentCharge;
        public final long periodMillis;

        public enum SymbolPosition { BEFORE, AFTER }

        public enum RentGrant { MEMBER, OWNER }

        public enum RentCharge { ONCE, PERIOD }

        MarketOptions(ConfigurationSection s) {
            if (s == null) {
                enabled = false;
                economyEnabled = true;
                symbol = "₽";
                symbolPosition = SymbolPosition.AFTER;
                decimalPlaces = 0;
                grouping = true;
                groupSeparator = ",";
                decimalSeparator = ".";
                rentGrant = RentGrant.MEMBER;
                rentCharge = RentCharge.PERIOD;
                periodMillis = 1440L * 60_000L;
                return;
            }
            enabled = s.getBoolean("enabled", true);
            ConfigurationSection e = s.getConfigurationSection("economy");
            economyEnabled = e == null || e.getBoolean("enabled", true);
            symbol = e == null ? "₽" : e.getString("symbol", "₽");
            SymbolPosition sp;
            try {
                sp = SymbolPosition.valueOf(e == null ? "AFTER" : e.getString("symbol-position", "AFTER"));
            } catch (IllegalArgumentException ex) {
                sp = SymbolPosition.AFTER;
            }
            symbolPosition = sp;
            decimalPlaces = e == null ? 0 : Math.max(0, Math.min(2, e.getInt("decimal-places", 0)));
            grouping = e == null || e.getBoolean("grouping", true);
            groupSeparator = e == null ? "," : e.getString("group-separator", ",");
            decimalSeparator = e == null ? "." : e.getString("decimal-separator", ".");

            ConfigurationSection r = s.getConfigurationSection("rent");
            RentGrant rg;
            try {
                rg = RentGrant.valueOf(r == null ? "MEMBER" : r.getString("grant", "MEMBER"));
            } catch (IllegalArgumentException ex) {
                rg = RentGrant.MEMBER;
            }
            rentGrant = rg;
            RentCharge rc;
            try {
                rc = RentCharge.valueOf(r == null ? "PERIOD" : r.getString("charge", "PERIOD"));
            } catch (IllegalArgumentException ex) {
                rc = RentCharge.PERIOD;
            }
            rentCharge = rc;
            periodMillis = Math.max(1, r == null ? 1440 : r.getInt("period-minutes", 1440)) * 60_000L;
        }
    }

    // ---------------- рейд клана «Воришка» (JustTeams) ----------------

    /**
     * Рейд клана на чужой регион (кнопка в info-меню у роли other).
     *   min-attackers       — мин. число нападающих (членов клана) в регионе для старта;
     *   online-percent      — мин. % ОНЛАЙН-членов клана, которые должны быть в регионе
     *                         (трактовка значения: < 1 = доля (0.5 = 50%), >= 1 = проценты (50 = 50%));
     *   capture-time        — секунды фазы захвата (все нападающие должны ПРОДЕРЖАТЬСЯ в регионе,
     *                         выход любого — сброс);
     *   thief-time          — секунды, которые «вор» держит доступ к региону;
     *   cooldown-time       — секунды кулдауна региона после завершения рейда;
     *   blacklist           — регионы (id), которые нельзя рейдить;
     *   owners-offline-required — владельцы/участники региона должны быть офлайн для старта;
     *   abort-on-owner-online  — сорвать рейд, если владелец/участник региона зашёл во время захвата;
     *   economy             — списание монет (PLAYER = баланс инициатора, CLAN = банк клана).
     */
    public static class RaidOptions {
        public final boolean enabled;
        public final int minAttackers;
        public final double onlinePercent;
        public final int captureSeconds;
        public final int thiefSeconds;
        public final int cooldownSeconds;
        public final Set<String> blacklist;
        public final boolean ownersOfflineRequired;
        public final boolean abortOnOwnerOnline;
        public final RaidEconomy economy;
        public final RaidDisplay display;
        public final RaidNotify notifyStart;
        public final RaidNotify notifyThief;
        public final RaidNotify notifyEnd;
        public final RaidNotify notifyReset;

        public enum RaidSource { PLAYER, CLAN }

        RaidOptions(ConfigurationSection s) {
            if (s == null) {
                enabled = false;
                minAttackers = 2;
                onlinePercent = 50;
                captureSeconds = 60;
                thiefSeconds = 60;
                cooldownSeconds = 300;
                blacklist = Set.of();
                ownersOfflineRequired = true;
                abortOnOwnerOnline = true;
                economy = new RaidEconomy(null);
                display = new RaidDisplay(null);
                RaidNotify def = new RaidNotify("", List.of());
                notifyStart = def;
                notifyThief = def;
                notifyEnd = def;
                notifyReset = def;
                return;
            }
            enabled = s.getBoolean("enabled", false);
            minAttackers = Math.max(1, s.getInt("min-attackers", 2));
            onlinePercent = s.getDouble("online-percent", 50);
            captureSeconds = Math.max(1, s.getInt("capture-time", 60));
            thiefSeconds = Math.max(1, s.getInt("thief-time", 60));
            cooldownSeconds = Math.max(0, s.getInt("cooldown-time", 300));
            blacklist = new HashSet<>(lower(s.getStringList("blacklist")));
            ownersOfflineRequired = s.getBoolean("owners-offline-required", true);
            abortOnOwnerOnline = s.getBoolean("abort-on-owner-online", true);
            economy = new RaidEconomy(s.getConfigurationSection("economy"));
            display = new RaidDisplay(s.getConfigurationSection("display"));
            notifyStart = new RaidNotify(s.getConfigurationSection("notify.start"));
            notifyThief = new RaidNotify(s.getConfigurationSection("notify.thief"));
            notifyEnd = new RaidNotify(s.getConfigurationSection("notify.end"));
            notifyReset = new RaidNotify(s.getConfigurationSection("notify.reset"));
        }

        public boolean isBlacklisted(String region) {
            return blacklist.contains(region.toLowerCase(java.util.Locale.ROOT));
        }

        /**
         * Списание при выборе «вора»; PLAYER вычитает процент с баланса нападающего
         * (инициатора), CLAN — процент с банка клана (JustTeams).
         */
        public static class RaidEconomy {
            public final boolean enabled;
            public final RaidSource source;
            public final double percent;

            RaidEconomy(ConfigurationSection s) {
                if (s == null) {
                    enabled = false;
                    source = RaidSource.CLAN;
                    percent = 10;
                    return;
                }
                enabled = s.getBoolean("enabled", false);
                RaidSource src;
                try {
                    src = RaidSource.valueOf(s.getString("source", "CLAN"));
                } catch (IllegalArgumentException e) {
                    src = RaidSource.CLAN;
                }
                source = src;
                percent = s.getDouble("percent", 10);
            }
        }

        /** Боссбар/экшнбар процесса рейда. */
        public static class RaidDisplay {
            public final String mode;
            public final int updateTicks;
            public final BarColor color;
            public final BarStyle style;
            public final String text;

            RaidDisplay(ConfigurationSection s) {
                if (s == null) {
                    mode = "ACTIONBAR";
                    updateTicks = 20;
                    color = BarColor.RED;
                    style = BarStyle.SEGMENTED_10;
                    text = "&cЗахват {region}: &f{time}&c сек • нападающих &f{count}&c/&f{total}";
                    return;
                }
                mode = s.getString("mode", "ACTIONBAR").toUpperCase(java.util.Locale.ROOT);
                updateTicks = Math.max(5, s.getInt("update-ticks", 20));
                BarColor c;
                try {
                    c = BarColor.valueOf(s.getString("color", "RED"));
                } catch (IllegalArgumentException e) {
                    c = BarColor.RED;
                }
                color = c;
                BarStyle st;
                try {
                    st = BarStyle.valueOf(s.getString("style", "SEGMENTED_10"));
                } catch (IllegalArgumentException e) {
                    st = BarStyle.SEGMENTED_10;
                }
                style = st;
                text = s.getString("text", "&cЗахват {region}: &f{time}&c сек • нападающих &f{count}&c/&f{total}");
            }
        }

        /** Оповещение стадии рейда. message — если не пусто, шлётся в чат всем
         *  (или приватно для notify.*); commands — исполняются как asConsole!/asPlayer!. */
        public static class RaidNotify {
            public final String message;
            public final List<String> commands;

            RaidNotify(String message, List<String> commands) {
                this.message = message;
                this.commands = commands == null ? List.of() : commands;
            }

            RaidNotify(ConfigurationSection s) {
                if (s == null) {
                    message = "";
                    commands = List.of();
                } else {
                    message = s.getString("message", "");
                    commands = new ArrayList<>(s.getStringList("commands"));
                }
            }
        }
    }
}