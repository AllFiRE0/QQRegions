package dev.qqregions.config;

import dev.qqregions.QQRegions;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * replace.yml — замена значений заполнителей WorldGuard/WGEFP на
 * локализованный текст. Ключ — имя заполнителя, внутри список
 * {placeholder, replacement}, спецзначение placeholder = ELSE — замена
 * по умолчанию.
 */
public class ReplaceManager {

    private final QQRegions plugin;
    private FileConfiguration cfg;

    public ReplaceManager(QQRegions plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "replace.yml");
        if (!file.exists()) {
            plugin.saveResource("replace.yml", false);
        }
        cfg = YamlConfiguration.loadConfiguration(file);
        if (!cfg.contains("_")) {
            cfg.set("_comment-internal", "keep");
        }
    }

    /**
     * Пытается заменить rawValue. returns исходное значение, если замен нет.
     */
    public String resolve(String placeholder, String rawValue) {
        if (placeholder == null || cfg == null || rawValue == null) {
            return rawValue;
        }
        List<?> list = listOf(placeholder);
        if (list == null) {
            // пробуем по ключу, где "<FlagName>" заменён на конкретный флаг — маловероятно, пропускаем
            return rawValue;
        }
        String fallback = null;
        for (Object o : list) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) o;
            String ph = m.get("placeholder") == null ? null : String.valueOf(m.get("placeholder"));
            String rep = m.get("replacement") == null ? rawValue : String.valueOf(m.get("replacement"));
            if (rep != null) {
                rep = rep.replace("{value}", rawValue);
            }
            if (ph == null) {
                continue;
            }
            if (ph.equalsIgnoreCase("ELSE")) {
                fallback = rep;
            } else if (ph.equalsIgnoreCase(rawValue)) {
                return rep;
            }
        }
        return fallback == null ? rawValue : fallback;
    }

    public String resolveFlex(String placeholderTemplate, String flag, String rawValue) {
        if (placeholderTemplate == null) {
            return rawValue;
        }
        String key = placeholderTemplate.replace("<FlagName>", flag);
        return resolve(key, rawValue);
    }

    private List<?> listOf(String key) {
        Object o = null;
        // keys могут содержать % и ': ', обращаемся по upmost section
        for (String root : cfg.getKeys(false)) {
            if (root.equalsIgnoreCase(key)) {
                o = cfg.get(root);
                break;
            }
        }
        if (!(o instanceof List)) {
            return null;
        }
        return (List<?>) o;
    }

    public ConfigurationSection root() {
        return cfg;
    }
}