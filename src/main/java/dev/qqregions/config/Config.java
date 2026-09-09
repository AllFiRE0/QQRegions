package dev.qqregions.config;

import dev.qqregions.QQRegions;
import dev.qqregions.util.Colors;
import dev.qqregions.util.Yml;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
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
    /** Слот хотбара (0-8) для каждой кнопки сессии выделения. */
    private final Map<String, Integer> buttonSlots = new HashMap<>();
    private int selectCenterSlot = 4;
    private List<String> blockedCommands = new ArrayList<>();
    private boolean syncWorldEdit = true;
    private boolean debug;
    /** Главное меню /region: кнопка «Назад» в слоте 0 (выкл. по умолчанию). */
    private boolean mainMenuBackEnabled = false;
    /** Команда кнопки «Назад» в главном меню (выполняется от имени игрока). */
    private String mainMenuBackCommand = "@back";
    /** Показывать в таймерах (time-format.units) две старшие единицы (2д 5ч / 5ч 7м),
     *  а не только одну (2д / 5ч). Действует на все механики: боссбар рейда и т.п. */
    private boolean timeFmtTwoUnits = true;
    private PointStyle point1;
    private PointStyle point2;
    private String viewMode = "PARTICLES";
    private int viewDistance = 200;
    private int viewDotsPerEdge = 16;
    private int viewMaxBlocks = 500;
    private float viewBlockScale = 0.35f;
    private boolean commandSelectionView = true;
    private int viewHideAfter = 5;
    private int viewHideDistance = 0;
    /** Авто-скрытие командной подсветки выделения (сек; 0 = держать, пока есть). */
    private int cmdViewHideAfter = 0;
    /** Максимальная дальность луча установки точки (команды /select point и кнопки
     *  Точка 1/2): дальше не берём блок, точка = позиция игрока. 1000 = дефолт. */
    private int pointMaxDistance = 1000;

    private ParticleOptions particles;
    private BossBarOptions bossbar;
    private HighlightOptions highlight;
    private MarketOptions market;
    private RaidOptions raid;
    /** outline: общий контроль пунктира контура (выделение + подсветка регионов). */
    private OutlineOptions outline;
    /** select-status: экшнбар/боссбар статуса выделения для всех способов select. */
    private SelectStatusOptions selectStatus;
    /** guard: защита механик от лага (TPS/пинг), enable:false по умолчанию. */
    private GuardOptions guard;

    /** menu-update.ticks: глобальный дефолт автообновления меню (тики; 0 = выкл). */
    private int menuUpdateTicks = 20;
    /** menu-update.debounce-after-click: молча не перерисовывать меню раньше
     *  чем через menu-update.ticks после клика игрока (анти-автокликер).
     *  По умолчанию ВЫКЛ (false): клики перерисовывают меню сразу. */
    private boolean menuDebounce = false;

    /** flags-menu.whitelist: флаги, доступные всем бесплатно (пусто = прежнее поведение). */
    private Set<String> flagsMenuWhitelist = new HashSet<>();
    /** flags-menu.shop-ignore: флаги, скрытые из магазина (только по праву). */
    private Set<String> flagsShopIgnore = new HashSet<>();
    /** flags-names: пользовательские названия флагов (key = id флага, value = название). */
    private final Map<String, String> flagNameReplace = new HashMap<>();
    /** flag-groups: группы-шаблоны прав на флаги (конфиг) — право
     *  qqregions.flags.group.<имя> открывает сразу весь список флагов. */
    private final List<FlagGroup> flagGroups = new ArrayList<>();
    /** regions.max-regions: лимит регионов на игрока (0 = без лимита). */
    private int maxRegions;
    /** region-flags-on-create: автофлаги, ставящиеся при создании региона
     *  (формат строки: <флаг>:<значение>[:<группа>]). */
    private boolean regionFlagsOnCreate = false;
    private final List<String> regionFlagsOnCreateList = new ArrayList<>();

    public Config(QQRegions plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        Yml.upgrade(plugin, "config.yml", new File(plugin.getDataFolder(), "config.yml"));
        plugin.reloadConfig();
        cfg = plugin.getConfig();
        if (cfg.getKeys(false).isEmpty()) {
            // Битый config.yml на диске (YamlConfiguration молча вернул пустой):
            // перезаписываем дефолтом из jar и перечитываем ещё раз, чтобы
            // плагин не стартовал на пустых настройках тихо.
            plugin.getLogger().severe("config.yml не прочитался — восстанавливаем из jar.");
            plugin.saveResource("config.yml", true);
            plugin.reloadConfig();
            cfg = plugin.getConfig();
        }

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
        timeFmtTwoUnits = !"ONE".equalsIgnoreCase(cfg.getString("time-format.units", "TWO"));

        point1 = new PointStyle(cfg.getConfigurationSection("interactive.select-points.point-1"),
                Material.ORANGE_STAINED_GLASS_PANE, Color.fromRGB(0xff8c00), Material.ORANGE_TERRACOTTA);
        point2 = new PointStyle(cfg.getConfigurationSection("interactive.select-points.point-2"),
                Material.GREEN_STAINED_GLASS_PANE, Color.fromRGB(0x00a651), Material.GREEN_CONCRETE);

        viewMode = cfg.getString("interactive.view-mode", "PARTICLES").toUpperCase(java.util.Locale.ROOT);
        viewDistance = cfg.getInt("interactive.view-distance", 200);
        viewDotsPerEdge = Math.max(2, cfg.getInt("interactive.view-dots-per-edge", 16));
        viewMaxBlocks = cfg.getInt("interactive.view-max-blocks", 500);
        viewBlockScale = (float) cfg.getDouble("interactive.view-block-scale", 0.35);
        commandSelectionView = cfg.getBoolean("interactive.command-selection-view", true);
        viewHideAfter = Math.max(0, cfg.getInt("interactive.view-hide-after", 60));
        viewHideDistance = Math.max(0, cfg.getInt("interactive.view-hide-distance", 0));
        cmdViewHideAfter = Math.max(0, cfg.getInt("interactive.command-selection-hide-after", 60));
        pointMaxDistance = Math.max(10, cfg.getInt("interactive.select-point-max-distance", 1000));

        buttonMaterials.clear();
        buttonSlots.clear();
        ConfigurationSection btns = cfg.getConfigurationSection("interactive.buttons");
        if (btns != null) {
            for (String key : btns.getKeys(false)) {
                String mat = btns.getString(key + ".material");
                if (mat != null) {
                    buttonMaterials.put(key, Material.matchMaterial(mat));
                }
                int slot = btns.getInt(key + ".slot", -1);
                if (slot >= 0 && slot <= 8) {
                    buttonSlots.put(key, slot);
                }
            }
        }
        selectCenterSlot = Math.max(0, Math.min(8, cfg.getInt("interactive.select-center-slot", 4)));
        mainMenuBackEnabled = cfg.getBoolean("interactive.main-menu.back-enabled", false);
        mainMenuBackCommand = cfg.getString("interactive.main-menu.back-command", "@back");

        particles = new ParticleOptions(cfg.getConfigurationSection("particles"));
        bossbar = new BossBarOptions(cfg.getConfigurationSection("bossbar"));
        highlight = new HighlightOptions(cfg.getConfigurationSection("highlight"));
        market = new MarketOptions(cfg.getConfigurationSection("market"));
        raid = new RaidOptions(cfg.getConfigurationSection("raid"));
        outline = new OutlineOptions(cfg.getConfigurationSection("outline"));
        selectStatus = new SelectStatusOptions(cfg.getConfigurationSection("select-status"));
        guard = new GuardOptions(cfg.getConfigurationSection("guard"));
        menuUpdateTicks = Math.max(0, cfg.getInt("menu-update.ticks", 20));
        menuDebounce = cfg.getBoolean("menu-update.debounce-after-click", false);

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
        flagGroups.clear();
        ConfigurationSection fg = cfg.getConfigurationSection("flag-groups");
        if (fg != null) {
            for (String key : fg.getKeys(false)) {
                ConfigurationSection g = fg.getConfigurationSection(key);
                if (g == null) {
                    continue;
                }
                Set<String> flags = new HashSet<>(lower(g.getStringList("flags")));
                boolean all = flags.contains("*") || flags.contains("ALL");
                flagGroups.add(new FlagGroup(key, flags, all));
            }
        }
        maxRegions = Math.max(0, cfg.getInt("regions.max-regions", 0));

        regionFlagsOnCreate = cfg.getBoolean("region-flags-on-create.enabled", false);
        regionFlagsOnCreateList.clear();
        regionFlagsOnCreateList.addAll(cfg.getStringList("region-flags-on-create.flags"));
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

    public boolean mainMenuBackEnabled() {
        return mainMenuBackEnabled;
    }

    public String mainMenuBackCommand() {
        return mainMenuBackCommand;
    }

    /** Формат таймеров для всех механик (time-format.units): true = две старшие единицы. */
    public boolean timeFmtTwoUnits() {
        return timeFmtTwoUnits;
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

    /** Интервал автообновления открытых меню (тики; 0 = выключено). */
    public int menuUpdateTicks() {
        return menuUpdateTicks;
    }

    /** Молча откладывать перерисовку меню после клика (анти-автокликер). */
    public boolean menuDebounce() {
        return menuDebounce;
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

    /** Авто-скрытие подсветки командного выделения в секундах (0 = держать всегда).
     *  Не влияет на интерактивный select (у него свой view-hide-after). */
    public int cmdViewHideAfter() {
        return cmdViewHideAfter;
    }

    /** Максимальная дальность луча установки точки (команды/кнопки Точка 1/2). */
    public int pointMaxDistance() {
        return pointMaxDistance;
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

    /** Слот хотбара (0-8) кнопки сессии; дефолтная раскладка, если не задан. */
    public int buttonSlot(String id) {
        Integer s = buttonSlots.get(id);
        if (s != null) {
            return s;
        }
        switch (id) {
            case "create":
                return 0;
            case "point1":
                return 1;
            case "point2":
                return 2;
            case "select":
                return 3;
            case "reset":
                return 5;
            case "cancel":
                return 8;
            default:
                return 0;
        }
    }

    /** Слот (0-8), который удерживается по центру в select-режиме (SCM). */
    public int selectCenterSlot() {
        return selectCenterSlot;
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

    /** Общий контроль пунктира контура: выделение и подсветка регионов (outline). */
    public OutlineOptions outline() {
        return outline;
    }

    /** Настройки рейда клана «Воришка» (кнопка в меню info, шаблон other). */
    public RaidOptions raid() {
        return raid;
    }

    /** Экшнбар/боссбар статуса выделения для всех способов select. */
    public SelectStatusOptions selectStatus() {
        return selectStatus;
    }

    /** Защита механик меню от лага/высокого пинга. */
    public GuardOptions guard() {
        return guard;
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

    /** Включена ли автоустановка флагов при создании региона. */
    public boolean regionFlagsOnCreate() {
        return regionFlagsOnCreate;
    }

    /** Список «флаг:значение[:группа]», ставящихся автоматически при создании региона. */
    public List<String> regionFlagsOnCreateList() {
        return regionFlagsOnCreateList;
    }

    /** Есть ли у игрока группа-шаблон прав (config.yml flag-groups), открывающая
     *  флаг. Право группы: qqregions.flags.group.<имя>. Несколько групп
     *  суммируются; флаг "<имя>": ["*"] открывает ВСЕ флаги. Отдельные права
     *  qqregions.flags.use.<флаг>/qqregions.flags.<флаг> здесь НЕ проверяются —
     *  их смотрит Menu.canSeeFlag, а итоговый доступ — Menu.canUseFlag. */
    public boolean flagGroupAllows(Player player, String flagId) {
        if (player == null || flagId == null) {
            return false;
        }
        String key = flagId.toLowerCase(java.util.Locale.ROOT);
        for (FlagGroup g : flagGroups) {
            if (player.hasPermission(g.permission) && (g.all || g.flags.contains(key))) {
                return true;
            }
        }
        return false;
    }

    // ---------------- вложенные опции ----------------

    /** Группа-шаблон прав на флаги (config.yml flag-groups). Право
     *  qqregions.flags.group.<имя> открывает сразу весь список флагов —
     *  выдача «пачкой» вместо права на каждый флаг по отдельности. */
    public static class FlagGroup {
        /** Полное право группы (qqregions.flags.group.<имя>, id в нижнем регистре). */
        public final String permission;
        /** Ид флагов группы (нижний регистр). */
        public final Set<String> flags;
        /** true — "[*]" в списке: открывает все флаги (для не-админов). */
        public final boolean all;

        FlagGroup(String key, Set<String> flags, boolean all) {
            this.permission = "qqregions.flags.group."
                    + (key == null ? "" : key.toLowerCase(java.util.Locale.ROOT));
            this.flags = flags;
            this.all = all;
        }
    }

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
     * Общий контроль пунктира объёмного контура (выделение игрока и подсветка
     * регионов типов PARTICLES/BLOCKS). Заменяет разнокалиберные плотности:
     * каждая линия (рёбра + кольца) рисуется точками с шагом НЕ БОЛЬШЕ max-gap
     * блоков, поэтому у высоких областей (например, сгенерированный чанком
     * куб на всю высоту мира) нет «дыр» из сотен блоков. По высоте к рёбрам
     * добавляются горизонтальные «кольца» (прямоугольники по периметру) на
     * каждом ring-step-м уровне — широкие регионы видно даже из середины.
     * max-points — жёсткий потолок точек; при превышении контур прореживается
     * равномерно (рёбра/кольца не пропадают, но шаг может стать больше max-gap).
     */
    public static class OutlineOptions {
        /** Максимальный зазор между соседними точками линии (блоки). */
        public final int maxGap;
        /** Потолок суммарного числа точек контура (для частиц и блок-дисплеев). */
        public final int maxPoints;
        /** Рисовать ли горизонтальные «кольца» по высоте (труба из квадратов). */
        public final boolean ringsEnabled;
        /** Шаг колец по Y в блоках (<= 0 — только верх и низ, без колец). */
        public final int ringStep;
        /** Шаг сетки-«квадратов» на верхней/нижней ПЛОСКОСТЯХ региона (<=1 — сетки нет). */
        public final int gridStep;

        OutlineOptions(ConfigurationSection s) {
            maxGap = Math.max(1, s == null ? 5 : s.getInt("max-gap", 5));
            maxPoints = Math.max(48, s == null ? 3000 : s.getInt("max-points", 3000));
            ConfigurationSection r = s == null ? null : s.getConfigurationSection("rings");
            ringsEnabled = r == null || r.getBoolean("enabled", true);
            ringStep = Math.max(0, r == null ? 8 : r.getInt("step", 8));
            ConfigurationSection g = s == null ? null : s.getConfigurationSection("grid");
            boolean gridEnabled = g == null || g.getBoolean("enabled", true);
            int gs = g == null ? 25 : g.getInt("step", 25);
            gridStep = gridEnabled ? Math.max(0, gs) : 0;
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
        /** Радиус АВТО-показа «своих» регионов вокруг игрока (0 = только под ногами). */
        public final int autoShowRadius;
        /** Скрывать подсветку при выходе игрока из региона (вход/выход по флагу). */
        public final boolean hideOnExit;
        /** Показывать подсветку при выходе игрока из региона (как exit-флаг WG). */
        public final boolean showOnExit;
        /** Как часто пере-сканировать рельеф территории (сек) при показе TERRITORY. */
        public final int terrainCacheSeconds;
        /** Отображение TERRITORY: PARTICLES — частицы над блоками, BLOCKS — дисплей-«забор». */
        public final String terrainDisplay;
        /** Параметры «забора» (terrainDisplay: BLOCKS). */
        public final TerrainFenceOptions fence;
        /** Блоки рельефа, которые TERRITORY считает пустотой (игнорируются). */
        public final Set<Material> territoryIgnore;
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
                autoShowRadius = 16;
                hideOnExit = true;
                showOnExit = true;
                terrainCacheSeconds = 3;
                terrainDisplay = "PARTICLES";
                territoryIgnore = Set.of();
                fence = new TerrainFenceOptions(null);
                particles = new ParticleOptions(null);
                return;
            }
            enabled = s.getBoolean("enabled", true);
            flagEnabled = s.getBoolean("flag-enabled", true);
            type = s.getString("type", "PARTICLES").toUpperCase(java.util.Locale.ROOT);
            // Таймаут подсветки РЕГИОНА (territory-visible / /region visible):
            // отдельный ключ region-hide-seconds, старые auto-hide-seconds
            // и show-seconds читаются как fallback. На выделения не влияет
            // (у них свои ключи: interactive.view-hide-after и др.).
            showSeconds = Math.max(1, s.getInt("region-hide-seconds",
                    s.getInt("auto-hide-seconds", s.getInt("show-seconds", 60))));
            showMillis = showSeconds * 1000L;
            scanTicks = Math.max(1, s.getInt("scan-ticks", 20));
            cooldownMillis = Math.max(0, s.getInt("cooldown-seconds", 10)) * 1000L;
            // Радиус АВТО-показа «своих» регионов (владелец/участник) вокруг игрока;
            // 0 = только регионы, в которых игрок стоит. Чужие — только по флагу ALLOW.
            autoShowRadius = Math.max(0, s.getInt("auto-show-radius", 16));
            blockScale = (float) s.getDouble("block-scale", 0.35);
            String mat = s.getString("block", "GLASS");
            Material m = Material.matchMaterial(mat);
            block = m == null ? Material.GLASS : m;
            hideOnExit = s.getBoolean("hide-on-exit", true);
            showOnExit = s.getBoolean("show-on-exit", true);
            terrainCacheSeconds = Math.max(1, s.getInt("terrain-cache-seconds", 3));
            String td = s.getString("territory.display", "PARTICLES").toUpperCase(java.util.Locale.ROOT);
            terrainDisplay = ("BLOCKS".equals(td) || "PARTICLES".equals(td)) ? td : "PARTICLES";
            territoryIgnore = new HashSet<>();
            ConfigurationSection terr = s.getConfigurationSection("territory");
            if (terr != null) {
                for (String v : terr.getStringList("ignore-blocks")) {
                    Material im = Material.matchMaterial(v);
                    if (im != null) {
                        territoryIgnore.add(im);
                    }
                }
            }
            fence = new TerrainFenceOptions(s.getConfigurationSection("territory.fence"));
            particles = new ParticleOptions(s.getConfigurationSection("particles"));
        }
    }

    /**
     * Параметры блок-дисплеев TERRITORY (highlight.territory.fence, работает при
     * type: TERRITORY и territory.display: BLOCKS). Блок-дисплеи ставятся
     * ОТДЕЛЬНЫМИ «штакетинами»: размеры каждого — width вдоль границы,
     * height вверх, thickness поперёк; расстояние между ЦЕНТРАМИ соседей —
     * spacing; сдвиг вверх/вниз — offset.
     */
    public static class TerrainFenceOptions {
        public final Material material;
        /** Высота одного блока-дисплея (по Y, в блоках). */
        public final double height;
        /** Ширина вдоль границы (в блоках). */
        public final double width;
        /** Толщина поперёк границы (в блоках). */
        public final double thickness;
        /** Расстояние между центрами соседей вдоль границы (в блоках). */
        public final double spacing;
        /** Сдвиг вверх/вниз относительно вершины рельефа (в блоках). */
        public final double offset;
        /** Сдвиг ВДОЛЬ границы от центра колонки (в блоках); 0 = по центрам блоков. */
        public final double alongOffset;
        /** Сдвиг ПОПЕРЁК границы (в блоках); + = НАРУЖУ из региона, − = внутрь.
         *  Дефолт 0.5 = центр штакетины на ЛИНИИ границы региона (по контуру). */
        public final double acrossOffset;
        /** Светиться ли (glow) в цвет highlight.particles.dust-color. */
        public final boolean glow;
        /** Стиль забора для ЧУЖИХ регионов. */
        public final FenceRoleStyle foreign;
        /** Стиль забора для регионов, где игрок УЧАСТНИК. */
        public final FenceRoleStyle member;
        /** Стиль забора для регионов, где игрок ВЛАДЕЛЕЦ. */
        public final FenceRoleStyle owner;

        /** Материал + свечение забора конкретной роли (fallback на базовые). */
        public static final class FenceRoleStyle {
            public final Material material;
            public final boolean glow;

            FenceRoleStyle(Material material, boolean glow) {
                this.material = material;
                this.glow = glow;
            }
        }

        public FenceRoleStyle forRelation(boolean isOwner, boolean isMember) {
            if (isOwner) {
                return owner;
            }
            return isMember ? member : foreign;
        }

        TerrainFenceOptions(ConfigurationSection s) {
            String def = "OAK_PLANKS";
            String matName = (s == null || s.getString("material") == null) ? def : s.getString("material");
            Material m = Material.matchMaterial(matName);
            material = m == null ? Material.OAK_PLANKS : m;
            height = Math.max(0.05, s == null ? 1.0 : s.getDouble("height", 1.0));
            width = Math.max(0.05, s == null ? 0.3 : s.getDouble("width", 0.3));
            thickness = Math.max(0.05, s == null ? 0.3 : s.getDouble("thickness", 0.3));
            spacing = Math.max(0.1, s == null ? 1.0 : s.getDouble("spacing", 1.0));
            offset = s == null ? -0.5 : s.getDouble("offset", -0.5);
            alongOffset = s == null ? 0.0 : s.getDouble("along-offset", 0.0);
            // 0.5 = центр штакетины на ЛИНИИ границы региона (внешняя грань
            // пограничного блока): забор стоит ровно по контуру региона,
            // пол-внутрь/пол-наружу. 0.0 = центр ПЕЧАТНОГО БЛОКА, т.е.
            // на полблока ВНУТРЬ от контура (визуально «съезжает»).
            acrossOffset = s == null ? 0.5 : s.getDouble("across-offset", 0.5);
            glow = s == null || s.getBoolean("glow", true);
            // Роли: вложенные секции owner/member/foreign с собственными
            // material/glow; при отсутствии своих значений берется базовая пара.
            foreign = style(s, "foreign", material, glow);
            member = style(s, "member", material, glow);
            owner = style(s, "owner", material, glow);
        }

        private static FenceRoleStyle style(ConfigurationSection s, String role,
                                            Material baseMat, boolean baseGlow) {
            ConfigurationSection r = s == null ? null : s.getConfigurationSection(role);
            if (r == null) {
                return new FenceRoleStyle(baseMat, baseGlow);
            }
            Material mat = baseMat;
            if (r.getString("material") != null) {
                Material mm = Material.matchMaterial(r.getString("material"));
                if (mm != null) {
                    mat = mm;
                }
            }
            return new FenceRoleStyle(mat, r.getBoolean("glow", baseGlow));
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
            if (s == null) {
                enabled = true;
                mode = "BOSSBAR";
                updateTicks = 5;
                style = BarStyle.SEGMENTED_10;
                normalColor = BarColor.WHITE;
                normalText = "&8[{current}&8/&8{max}&8] &7блоков";
                fullColor = BarColor.RED;
                fullText = "&8{value-color}{current}&8/&8{max}&8 — максимум блоков!";
                conflictColor = BarColor.YELLOW;
                conflictText = "&7Выделение пересекает чужую территорию!";
                valueColor = "&f";
                return;
            }
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
            fullText = s.getString("full.text", "&8{value-color}{current}&8/&8{max}&8 — максимум блоков!");
            conflictColor = Colors.bar(s.getString("conflict.color", "YELLOW"), BarColor.YELLOW);
            conflictText = s.getString("conflict.text", "&7Выделение пересекает чужую территорию!");
            valueColor = s.getString("value-color", "&f");
        }
    }

    /**
     * Экшнбар/боссбар статуса выделения для ВСЕХ способов select:
     *  - interactive-actionbar — обновляемый цветной текст в экшнбаре во время
     *    интерактивного select (тексты/цвета — из bossbar.normal/full/conflict);
     *  - command-show — экшнбар+боссбар после командных выделений (pos, point,
     *    max, chunk, expand, outset) на show-seconds;
     *  - info — отдельный экшнбар с доп. информацией (высоты, конфликты) для
     *    всех способов select, поверх основного статуса/боссбара.
     */
    public static class SelectStatusOptions {
        public final boolean enabled;
        public final boolean interactiveActionbar;
        public final boolean commandShow;
        /** Как показывать статус командных выделений: BOTH | ACTIONBAR | BOSSBAR. */
        public final String commandMode;
        public final int showSeconds;
        public final int updateTicks;
        public final InfoOptions info;

        public static class InfoOptions {
            public final boolean enabled;
            public final int showSeconds;
            public final int updateTicks;
            /** Плейсхолдеры: {height-top} {height-bottom} {conflict} {conflict-regions}
             *  {conflict-count} {current} {max} {percent} {player}. */
            public final String text;

            InfoOptions(ConfigurationSection s) {
                if (s == null) {
                    enabled = false;
                    showSeconds = 10;
                    updateTicks = 5;
                    text = "&7Высота: &a{height-top}&7/&a{height-bottom} &8• &7Количество конфликтов: &f{conflict-count} &8(&f{conflict-regions}&8)";
                    return;
                }
                enabled = s.getBoolean("enabled", false);
                showSeconds = Math.max(1, s.getInt("show-seconds", 10));
                updateTicks = Math.max(1, s.getInt("update-ticks", 5));
                text = s.getString("text",
                        "&7Высота: &a{height-top}&7/&a{height-bottom} &8• &7Конфликты: &f{conflict-count} &8(&f{conflict-regions}&8)");
            }
        }

        SelectStatusOptions(ConfigurationSection s) {
            if (s == null) {
                enabled = true;
                interactiveActionbar = true;
                commandShow = true;
                commandMode = "BOTH";
                showSeconds = 10;
                updateTicks = 5;
                info = new InfoOptions(null);
                return;
            }
            enabled = s.getBoolean("enabled", true);
            interactiveActionbar = s.getBoolean("interactive-actionbar", true);
            commandShow = s.getBoolean("command-show", true);
            String mm = s.getString("command-mode", "BOTH").toUpperCase(java.util.Locale.ROOT);
            if (!mm.equals("ACTIONBAR") && !mm.equals("BOSSBAR") && !mm.equals("BOTH")) {
                mm = "BOTH";
            }
            commandMode = mm;
            showSeconds = Math.max(0, s.getInt("show-seconds", 10));
            updateTicks = Math.max(1, s.getInt("update-ticks", 5));
            info = new InfoOptions(s.getConfigurationSection("info"));
        }
    }

    /**
     * Защита механик (главное — клики меню) от лага/высокого пинга:
     * при включённом guard действие отменяется, если TPS сервера ниже
     * min-tps или пинг игрока выше max-ping. По умолчанию выключено.
     * Предотвращает «дюп» предметов из кнопок меню при лагах.
     */
    public static class GuardOptions {
        public final boolean enabled;
        public final double minTps;
        public final int maxPing;

        GuardOptions(ConfigurationSection s) {
            if (s == null) {
                enabled = false;
                minTps = 15.0;
                maxPing = 5000;
                return;
            }
            enabled = s.getBoolean("enabled", false);
            minTps = Math.max(0, s.getDouble("min-tps", 15.0));
            maxPing = Math.max(0, s.getInt("max-ping", 5000));
        }

        /** Проверить серверный TPS (последняя 1-минутная выборка, Paper/Leaf API). */
        public static double tps() {
            try {
                double[] tps = org.bukkit.Bukkit.getTPS();
                return tps == null || tps.length == 0 ? 20.0 : tps[0];
            } catch (Throwable t) {
                return 20.0;
            }
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
        /** Сколько времени объявление об аренде живёт в маркете (на каждое
         *  автовозвращение на рынок; минуты из market.rent.list-duration-minutes). */
        public final long listDurationMillis;
        /** Мин. срок аренды в минутах (market.rent.min-duration-minutes).
         *  Меньше этого владелец не сможет выставить арендный срок (кнопка
         *  «Шифт+ПКМ» в его объявлении). */
        public final long rentMinMinutes;
        /** Макс. срок аренды днях из конфига → минуты (market.rent.max-duration-days). */
        public final long rentMaxMinutes;
        /** Автовозврат с автопродлением: после окончания аренды объявление
         *  автоматически снова выставляется в маркете (market.rent.auto-rent). */
        public final boolean autoRent;
        /** Телепорт «посмотреть в живую» (ЛКМ по СВОЕМУ объявлению во «Все»,
         *  по продаже во вкладке «Мои»): market.teleport-enabled. Если false —
         *  бесплатный телепорт к регионам из объявлений отключён совсем,
         *  чтобы предложения не работали как бесплатные точки /warp. */
        public final boolean teleportEnabled;
        /** Как распределять оплату при нескольких владельцах (market.multiowner). */
        public final MultiOwner multiowner;
        /** Комиссия сервера (market.commission). */
        public final CommissionOptions commission;
        /** Срок жизни ПРИВАТНОГО предложения в минутах (market.offer-timeout-minutes). */
        public final long offerTimeoutMillis;
        /** Что сделать с ПРИВАТНЫМ предложением по истечении срока
         *  (market.offer-timeout-action): RELIST — перевыставить публично;
         *  CANCEL — снять объявление с рынка. */
        public final OfferTimeoutAction offerTimeoutAction;
        /** Голограммы-вывески рынка (market.market-holo). */
        public final MarketHoloOptions marketHolo;

        public enum SymbolPosition { BEFORE, AFTER }

        public enum RentGrant { MEMBER, OWNER }

        public enum RentCharge { ONCE, PERIOD }

        public enum MultiOwner { SINGLE, SPLIT }

        public enum OfferTimeoutAction { RELIST, CANCEL }

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
                listDurationMillis = 7L * 24L * 60_000L;
                rentMinMinutes = 60L;
                rentMaxMinutes = 365L * 1440L;
                autoRent = true;
                teleportEnabled = true;
                multiowner = MultiOwner.SINGLE;
                commission = new CommissionOptions(null);
                offerTimeoutMillis = 60L * 60_000L;
                offerTimeoutAction = OfferTimeoutAction.RELIST;
                marketHolo = new MarketHoloOptions(null);
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
            listDurationMillis = Math.max(1, r == null ? 10080 : r.getInt("list-duration-minutes", 10080)) * 60_000L;
            rentMinMinutes = Math.max(1, r == null ? 60 : r.getInt("min-duration-minutes", 60));
            rentMaxMinutes = Math.max(rentMinMinutes,
                    (r == null ? 365 : Math.max(1, r.getInt("max-duration-days", 365))) * 1440L);
            autoRent = r == null || r.getBoolean("auto-rent", true);
            MultiOwner mo;
            try {
                mo = MultiOwner.valueOf(s.getString("multiowner", "SINGLE").toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                mo = MultiOwner.SINGLE;
            }
            multiowner = mo;
            teleportEnabled = s.getBoolean("teleport-enabled", true);
            commission = new CommissionOptions(s.getConfigurationSection("commission"));
            offerTimeoutMillis = Math.max(0, s.getInt("offer-timeout-minutes", 60)) * 60_000L;
            OfferTimeoutAction ota;
            try {
                ota = OfferTimeoutAction.valueOf(
                        s.getString("offer-timeout-action", "RELIST").toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                ota = OfferTimeoutAction.RELIST;
            }
            offerTimeoutAction = ota;
            marketHolo = new MarketHoloOptions(s.getConfigurationSection("market-holo"));
        }
    }

    /** Комиссия сервера (market.commission): доля от цены, списывается с получателя. */
    public static class CommissionOptions {
        public final boolean enable;
        /** Доля от суммы (1.0 = 100%, 0.01 = 1%). */
        public final double rate;

        CommissionOptions(ConfigurationSection s) {
            enable = s != null && s.getBoolean("enable", false);
            rate = s == null ? 0.0 : Math.max(0.0, Math.min(1.0, s.getDouble("rate", 0.01)));
        }
    }

    /**
     * Голограмма-вывеска рынка (market.market-holo): ОДНА плавающая голограмма
     * возле периметра региона, которая «облетает» границы следом за игроком и
     * всегда смотрит на него. Видна любому игроку в радиусе view-distance,
     * чьи координаты по Y внутри высот региона.
     */
    public static class MarketHoloOptions {
        public final boolean enabled;
        /** Сдвиг голограммы вверх/вниз относительно уровня Y ИГРОКА (в блоках). */
        public final double yOffset;
        /** Радиус (по X/Z), внутри которого голограмма начинает вести игрока. */
        public final double viewDistance;
        /** Множитель размера текста голограммы. */
        public final double scale;
        /** Длина строки в блоках. */
        public final int lineWidth;

        MarketHoloOptions(ConfigurationSection s) {
            enabled = s == null || s.getBoolean("enabled", true);
            yOffset = s == null ? 1.5 : s.getDouble("y-offset", 1.5);
            viewDistance = s == null ? 24 : Math.max(1, s.getDouble("view-distance", 24));
            scale = s == null ? 1.0 : Math.max(0.05, s.getDouble("scale", 1.0));
            lineWidth = s == null ? 200 : Math.max(10, s.getInt("line-width", 200));
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
     *   economy             — списание монет (PLAYER = баланс инициатора, CLAN = банк клана);
     *   clan-currency       — своя валюта банка клана (символ/формат), показывается,
     *                         когда economy.source = CLAN (не путать с Vault);
     *   display.thief-bar   — персональный боссбар таймера вора (виден только вору);
     *   notify.thief-end    — приватное уведомление вору об истечении таймера.
     *
     * Время в плейсхолдерах {time} боссбаров/уведомлений форматируется правилом
     * time-format.units (единицы надписей — из lang.yml menu.time-*).
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
        /** Валюта банка клана (raid.clan-currency) — отображается, когда
         *  economy.source = CLAN. НЕ путать с Vault-валютой рынка. */
        public final ClanCurrency clanCurrency;
        public final RaidNotify notifyStart;
        public final RaidNotify notifyThief;
        public final RaidNotify notifyThiefEnd;
        public final RaidNotify notifyEnd;
        public final RaidNotify notifyReset;

        public enum RaidSource { PLAYER, CLAN }

        RaidOptions(ConfigurationSection s) {
            if (s == null) {
                enabled = false;
                minAttackers = 2;
                onlinePercent = 50;
                captureSeconds = 60;
                thiefSeconds = 300;
                cooldownSeconds = 300;
                blacklist = Set.of();
                ownersOfflineRequired = true;
                abortOnOwnerOnline = true;
                economy = new RaidEconomy(null);
                display = new RaidDisplay(null);
                clanCurrency = new ClanCurrency(null);
                RaidNotify def = new RaidNotify("", List.of());
                notifyStart = def;
                notifyThief = def;
                notifyThiefEnd = def;
                notifyEnd = def;
                notifyReset = def;
                return;
            }
            enabled = s.getBoolean("enabled", false);
            minAttackers = Math.max(1, s.getInt("min-attackers", 2));
            onlinePercent = s.getDouble("online-percent", 50);
            captureSeconds = Math.max(1, s.getInt("capture-time", 60));
            thiefSeconds = Math.max(1, s.getInt("thief-time", 300));
            cooldownSeconds = Math.max(0, s.getInt("cooldown-time", 300));
            blacklist = new HashSet<>(lower(s.getStringList("blacklist")));
            ownersOfflineRequired = s.getBoolean("owners-offline-required", true);
            abortOnOwnerOnline = s.getBoolean("abort-on-owner-online", true);
            economy = new RaidEconomy(s.getConfigurationSection("economy"));
            display = new RaidDisplay(s.getConfigurationSection("display"));
            clanCurrency = new ClanCurrency(s.getConfigurationSection("clan-currency"));
            notifyStart = new RaidNotify(s.getConfigurationSection("notify.start"));
            notifyThief = new RaidNotify(s.getConfigurationSection("notify.thief"));
            notifyThiefEnd = new RaidNotify(s.getConfigurationSection("notify.thief-end"));
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

        /** Валюта банка клана (raid.clan-currency). Отдельный формат и символ —
         *  это СВОЯ валюта кланов (для рейдов/баланса клана), она не связана с
         *  Vault-балансом игроков. Используется, когда economy.source = CLAN. */
        public static class ClanCurrency {
            public final String symbol;
            public final boolean grouping;
            public final String groupSeparator;
            public final String decimalSeparator;
            public final int decimalPlaces;

            ClanCurrency(ConfigurationSection s) {
                if (s == null) {
                    symbol = "⚔";
                    grouping = true;
                    groupSeparator = " ";
                    decimalSeparator = ".";
                    decimalPlaces = 0;
                    return;
                }
                symbol = s.getString("symbol", "⚔");
                grouping = s.getBoolean("grouping", true);
                groupSeparator = s.getString("group-separator", " ");
                decimalSeparator = s.getString("decimal-separator", ".");
                decimalPlaces = Math.max(0, Math.min(4, s.getInt("decimal-places", 0)));
            }

            /** Число БЕЗ символа (формат как у рынка, но со своим разделителем). */
            public String format(double amount) {
                String gs = (groupSeparator == null || groupSeparator.isEmpty()) ? " " : groupSeparator;
                String ds = (decimalSeparator == null || decimalSeparator.isEmpty()) ? "." : decimalSeparator;
                if (gs.equals(".") || gs.equals(ds)) {
                    gs = " ";
                }
                java.text.DecimalFormatSymbols sym = new java.text.DecimalFormatSymbols(java.util.Locale.ROOT);
                sym.setGroupingSeparator(gs.charAt(0));
                sym.setDecimalSeparator(ds.charAt(0));
                StringBuilder pat = new StringBuilder(grouping ? "#,##0" : "#0");
                if (decimalPlaces > 0) {
                    pat.append('.').append("0".repeat(decimalPlaces));
                }
                return new java.text.DecimalFormat(pat.toString(), sym).format(amount);
            }
        }

        /** Боссбар/экшнбар процесса рейда. */
        public static class RaidDisplay {
            public final String mode;
            public final int updateTicks;
            public final BarColor color;
            public final BarStyle style;
            public final String text;
            /** Полоса фазы «вора» (после захвата): {thief} {time}. */
            public final String thiefText;
            public final BarColor thiefColor;
            /** Отдельный персональный боссбар ТОЛЬКО для вора (таймер доступа). */
            public final ThiefBar thiefBar;

            RaidDisplay(ConfigurationSection s) {
                if (s == null) {
                    mode = "ACTIONBAR";
                    updateTicks = 20;
                    color = BarColor.RED;
                    style = BarStyle.SEGMENTED_10;
                    text = "&8Захват #59806f{region}&8: &f{time} • нападающих &f{count}&8/&f{total}";
                    thiefText = "&7Вор &f{thief}&7: &f{time}";
                    thiefColor = BarColor.GREEN;
                    thiefBar = new ThiefBar(null);
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
                text = s.getString("text", "&8Захват #59806f{region}&8: &f{time} • нападающих &f{count}&8/&f{total}");
                thiefText = s.getString("thief-text", "&7Вор &f{thief}&7: &f{time}");
                BarColor tc;
                try {
                    tc = BarColor.valueOf(s.getString("thief-color", "GREEN"));
                } catch (IllegalArgumentException e) {
                    tc = BarColor.GREEN;
                }
                thiefColor = tc;
                thiefBar = new ThiefBar(s.getConfigurationSection("thief-bar"));
            }

            /** Персональный боссбар таймера вора (display.thief-bar): виден только
             *  вору, полоса отсчитывает от полной к пустой за thief-time. */
            public static class ThiefBar {
                public final boolean enabled;
                public final BarColor color;
                public final BarStyle style;
                /** Плейсхолдеры: {thief} {time}. */
                public final String text;

                ThiefBar(ConfigurationSection s) {
                    if (s == null) {
                        enabled = true;
                        color = BarColor.RED;
                        style = BarStyle.SOLID;
                        text = "&8Время нахождения на территории: &f{time}";
                        return;
                    }
                    enabled = s.getBoolean("enabled", true);
                    BarColor c;
                    try {
                        c = BarColor.valueOf(s.getString("color", "RED"));
                    } catch (IllegalArgumentException e) {
                        c = BarColor.RED;
                    }
                    color = c;
                    BarStyle st;
                    try {
                        st = BarStyle.valueOf(s.getString("style", "SOLID"));
                    } catch (IllegalArgumentException e) {
                        st = BarStyle.SOLID;
                    }
                    style = st;
                    text = s.getString("text", "&8Время нахождения на территории: &f{time}");
                }
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