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
    /** Встроенные переводы из jar — опора для пустых/битых значений файла. */
    private FileConfiguration defs;

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
            cfg = defsLocal(defs);
            this.defs = defs;
            return;
        }
        cfg = loaded;
        cfg.setDefaults(defs);
        cfg.options().copyDefaults(true);
        // Лечим пустые значения: если в файле игрока строка пустая, а во
        // встроенном переводе не пустая — восстанавливаем (иначе в чате
        // оставался бы только префикс [QQRegions]).
        for (String key : defs.getKeys(true)) {
            if (!defs.isString(key)) {
                continue;
            }
            String dv = defs.getString(key, "");
            if (dv.isEmpty()) {
                continue;
            }
            String own = cfg.getString(key, null);
            if (own == null || own.isEmpty()) {
                cfg.set(key, dv);
            }
        }
        this.defs = defs;
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить lang.yml: " + e.getMessage());
        }
    }

    /** Копия дефолтов: get(..) может мутировать конфиг при copyDefaults. */
    private static FileConfiguration defsLocal(FileConfiguration in) {
        FileConfiguration copy = new YamlConfiguration();
        for (String key : in.getKeys(true)) {
            copy.set(key, in.get(key));
        }
        return copy;
    }

    public String get(String key) {
        String v = cfg.getString(key);
        if (v == null || v.isEmpty()) {
            // Файл игрока пуст/сломан — подстраховываемся встроенным переводом.
            if (defs != null) {
                String d = defs.getString(key, "");
                if (!d.isEmpty()) {
                    return d;
                }
            }
            plugin.dbg("no lang value for '" + key + "'");
        }
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