package dev.qqregions.config;

import dev.qqregions.QQRegions;
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

    private ParticleOptions particles;
    private BossBarOptions bossbar;

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

    public float viewBlockScale() {
        return viewBlockScale;
    }

    /** Желаемое число точек-кубиков на ОДНО ребро в BLOCKS-режиме (шаг растёт с длиной). */
    public int viewDotsPerEdge() {
        return viewDotsPerEdge;
    }

    public boolean commandSelectionView() {
        return commandSelectionView;
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
            highlight = hexColor(s.getString("highlight"), defaultColor);
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
            enabled = s.getBoolean("enabled", true);
            updateTicks = Math.max(1, s.getInt("update-ticks", 10));
            particleName = s.getString("particle", "DUST");
            dustColor = hexColor(s.getString("dust-color", "#00ff00"));
            dustSize = (float) s.getDouble("dust-size", 0.6);
            amount = s.getInt("amount", 1);
            speed = s.getDouble("speed", 0);
            density = s.getInt("point-density", 2);
            maxPoints = s.getInt("max-points", 4000);
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
            normalColor = barColor(s.getString("normal.color", "WHITE"));
            normalText = s.getString("normal.text", "&8[{current}&8/&8{max}&8] &7блоков");
            fullColor = barColor(s.getString("full.color", "RED"));
            fullText = s.getString("full.text", "&cМаксимум блоков достигнут!");
            conflictColor = barColor(s.getString("conflict.color", "YELLOW"));
            conflictText = s.getString("conflict.text", "&eВыделение пересекает чужой регион!");
        }
    }

    private static Color hexColor(String hex) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            int rgb = Integer.parseInt(h, 16);
            return Color.fromRGB(rgb);
        } catch (Exception e) {
            return Color.GREEN;
        }
    }

    private static Color hexColor(String hex, Color def) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            if (h.isEmpty()) {
                return def;
            }
            int rgb = Integer.parseInt(h, 16);
            return Color.fromRGB(rgb);
        } catch (Exception e) {
            return def;
        }
    }

    private static BarColor barColor(String name) {
        try {
            return BarColor.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return BarColor.WHITE;
        }
    }
}