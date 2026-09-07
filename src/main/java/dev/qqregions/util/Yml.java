package dev.qqregions.util;

import dev.qqregions.QQRegions;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Утилиты конфигов: автоматическое обновление файлов плагина (config.yml,
 * lang.yml, меню и т.п.) через ключ {@code config-version}. При смене версии
 * файла в jar дефолтный файл копируется заново (старая версия сохраняется
 * рядом как {@code <имя>.old}), либо недостающие ключи дополняются в памяти.
 */
public final class Yml {

    public static final String VERSION_KEY = "config-version";

    private Yml() {
    }

    /** Загрузить ресурс из jar как YamlConfiguration (null, если нет/битый). */
    public static YamlConfiguration jar(QQRegions plugin, String path) {
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось прочитать ресурс " + path + ": " + t.getMessage());
            return null;
        }
    }

    /** Версия конфига ({@code config-version}; отсутствует/битая = 0). */
    public static int version(FileConfiguration cfg) {
        if (cfg == null || !cfg.contains(VERSION_KEY)) {
            return 0;
        }
        Object v = cfg.get(VERSION_KEY);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Авто-обновление файла из jar: если версия файла ниже версии ресурса —
     * дефолт копируется заново (старый файл — бэкап {@code <имя>.old}),
     * новые значения подхватываются при следующей перезагрузке.
     */
    public static void upgrade(QQRegions plugin, String resource, File file) {
        if (!file.exists()) {
            plugin.saveResource(resource, false);
            return;
        }
        YamlConfiguration jar = jar(plugin, resource);
        if (jar == null) {
            return;
        }
        int jv = version(jar);
        int dv;
        try {
            dv = version(YamlConfiguration.loadConfiguration(file));
        } catch (Exception e) {
            // Битый YAML на диске (например после ручной правки): не даём
            // плагину упасть при старте — считаем версию 0 и перезаписываем
            // файл из jar (бэкап битой копии остаётся рядом).
            plugin.getLogger().warning("Повреждённый " + resource
                    + " на диске — будет восстановлен из jar: " + e.getMessage());
            dv = 0;
        }
        if (jv <= dv) {
            return;
        }
        try {
            File bak = new File(file.getParentFile(), file.getName() + ".old");
            Files.copy(file.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось создать бэкап " + file.getName() + ": " + e.getMessage());
        }
        try {
            plugin.saveResource(resource, true);
            plugin.getLogger().info("Конфиг " + resource + " обновлён с версии " + dv + " до " + jv
                    + " (старая копия: " + file.getName() + ".old)");
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось обновить " + resource + ": " + t.getMessage());
        }
    }

    /** Дописать в cfg недостающие ключи из дефолтов jar (существующие значения
     *  игрока не трогаются — подходят для ручных переводов/цен). */
    public static void mergeDefaults(FileConfiguration cfg, YamlConfiguration defs) {
        if (cfg == null || defs == null) {
            return;
        }
        for (String key : defs.getKeys(true)) {
            if (!cfg.contains(key, true)) {
                cfg.set(key, defs.get(key));
            }
        }
    }
}