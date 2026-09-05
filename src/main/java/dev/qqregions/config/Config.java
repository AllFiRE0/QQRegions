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

    private int wheelStep;
    private boolean invertWheel;
    private int lookAngle;
    private final Map<String, Material> buttonMaterials = new HashMap<>();

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

        wheelStep = cfg.getInt("interactive.wheel-step", 1);
        invertWheel = cfg.getBoolean("interactive.invert-wheel", false);
        lookAngle = cfg.getInt("interactive.look-angle-for-vertical", 60);

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

    public int wheelStep() {
        return wheelStep;
    }

    public boolean invertWheel() {
        return invertWheel;
    }

    public int lookAngle() {
        return lookAngle;
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

    private static BarColor barColor(String name) {
        try {
            return BarColor.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return BarColor.WHITE;
        }
    }
}