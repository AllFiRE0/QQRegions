package dev.qqregions.config;

import dev.qqregions.QQRegions;
import dev.qqregions.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Языковой файл lang.yml. Все сообщения плагина хранятся здесь в
 * формате "ключ: перевод". Заполнители {имя} подставляются через
 * {@link #fmt(String, String...)}.
 */
public class Lang {

    private final QQRegions plugin;
    private FileConfiguration cfg;

    public Lang(QQRegions plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "lang.yml");
        if (!file.exists()) {
            plugin.saveResource("lang.yml", false);
        }
        FileConfiguration loaded = null;
        try {
            loaded = YamlConfiguration.loadConfiguration(file);
        } catch (Exception ex) {
            plugin.getLogger().severe("Не удалось прочитать lang.yml: " + ex.getMessage());
            plugin.getLogger().severe("Используются встроенные переводы. Исправьте файл и выполните /region reload.");
        }
        // базовые значения из jar на случай неполного/битого файла
        FileConfiguration defs = new YamlConfiguration();
        try (InputStream in = plugin.getResource("lang.yml")) {
            if (in != null) {
                defs = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось прочитать встроенный lang.yml: " + e.getMessage());
        } catch (Exception e) {
            plugin.getLogger().severe("Встроенный lang.yml повреждён: " + e.getMessage());
        }
        if (loaded == null || loaded.getKeys(false).isEmpty()) {
            // битый/пустой файл — работаем на встроенных переводах, чтобы
            // сообщения не были пустыми; файл игрока не перезаписываем.
            cfg = defs;
            return;
        }
        cfg = loaded;
        cfg.setDefaults(defs);
        cfg.options().copyDefaults(true);
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить lang.yml: " + e.getMessage());
        }
    }

    public String get(String key) {
        String v = cfg.getString(key);
        return v == null ? "" : v;
    }

    public boolean has(String key) {
        return cfg.isString(key);
    }

    public List<?> getList(String key) {
        return cfg.getList(key);
    }

    public List<String> stringList(String key) {
        return cfg.getStringList(key);
    }

    /**
     * Подстановка {заполнителей} (пары ключ/значение) и '&'-цветов.
     */
    public String fmt(String key, String... kv) {
        String msg = get(key);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] == null) {
                continue;
            }
            msg = msg.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return msg;
    }

    /** fmt + префикс плагина. */
    public String prefixed(String key, String... kv) {
        return get("prefix") + fmt(key, kv);
    }

    public Component comp(String key, String... kv) {
        return Msg.color(fmt(key, kv));
    }

    public Component compPrefixed(String key, String... kv) {
        return Msg.color(prefixed(key, kv));
    }
}