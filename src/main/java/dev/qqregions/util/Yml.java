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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // ---------- сохранение с переносом комментариев ----------

    private static final Pattern KEY_LINE =
            Pattern.compile("^(\\s*)([A-Za-z0-9_.\\-]+)\\s*:.*$");

    /** Сохранить конфиг, перенеся комментарии из старой версии файла на те же
     *  ключи (новые ключи пишутся без комментариев). Нужен там, где файл может
     *  переписываться (добавление новых ключей при обновлении) — чтобы не
     *  сбрасывать комментарии разработчика сервера. */
    public static void savePreserving(File file, FileConfiguration cfg) throws IOException {
        String nv = cfg.saveToString();
        if (!file.exists()) {
            Files.write(file.toPath(), nv.getBytes(StandardCharsets.UTF_8));
            return;
        }
        String old = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Files.write(file.toPath(), preserveComments(old, nv).getBytes(StandardCharsets.UTF_8));
    }

    /** Ключ строки (маппинг) из дампа: только строки вида " indent key:".
     *  Списки ("- ...") и прочее игнорируются — их строки проходят как есть. */
    private static boolean parseKeyLine(String line, List<String> keys, List<Integer> inds) {
        Matcher m = KEY_LINE.matcher(line);
        if (!m.matches()) {
            return false;
        }
        int indent = m.group(1).length();
        while (!inds.isEmpty() && inds.get(inds.size() - 1) >= indent) {
            inds.remove(inds.size() - 1);
            keys.remove(keys.size() - 1);
        }
        String key = m.group(2);
        keys.add(key);
        inds.add(indent);
        return true;
    }

    /** Индекс начала инлайнового комментария " #..." ВНЕ кавычек (позиция
     *  пробела перед '#'); -1, если комментария нет. Работает и со значением
     *  типа "" или 'text' — маркеры кавычек отслеживаются, '#' внутри кавычек
     *  (например &#000000 в строке) комментарием не считается. */
    private static int inlineIndex(String raw) {
        boolean inS = false, inD = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\'' && !inD) {
                inS = !inS;
            } else if (c == '"' && !inS) {
                inD = !inD;
            } else if (c == '#' && !inS && !inD && i > 0 && raw.charAt(i - 1) == ' ') {
                return i - 1;
            }
        }
        return -1;
    }

    /** Инлайновые комментарии старых ключей (дот-путь -> " #..." без ведущей
     *  части строки). Для пустых значений вида `key: "" #"пояснение"` — чтобы
     *  при перезаписи пояснение не терялось, а значение оставалось пустым. */
    private static Map<String, String> collectInline(String old) {
        Map<String, String> out = new HashMap<>();
        List<String> keys = new ArrayList<>();
        List<Integer> inds = new ArrayList<>();
        for (String line : old.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#") || t.startsWith("-")) {
                continue;
            }
            if (!parseKeyLine(line, keys, inds)) {
                continue;
            }
            int idx = inlineIndex(line);
            if (idx >= 0) {
                String tail = line.substring(idx);
                if (!tail.isEmpty()) {
                    out.putIfAbsent(pathOf(keys), tail);
                }
            }
        }
        return out;
    }

    /** Дот-путь строго текущего ключа (keys уже содержит его):
     *  например ["select", "interactive-help"] -> "select.interactive-help". */
    private static String pathOf(List<String> keys) {
        if (keys.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(keys.get(i));
        }
        return sb.toString();
    }

    /** Старые комментарии по ключам: ключ -> строки-комментарии (как в файле,
     *  с оригинальными отступами), стоящие НЕПОСРЕДСТВЕННО над этим ключом. */
    private static Map<String, List<String>> collectComments(String old) {
        Map<String, List<String>> out = new HashMap<>();
        List<String> keys = new ArrayList<>();
        List<Integer> inds = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (String line : old.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#")) {
                pending.add(line);
                continue;
            }
            if (t.startsWith("-")) {
                continue;
            }
            if (!parseKeyLine(line, keys, inds)) {
                continue;
            }
            if (!pending.isEmpty()) {
                String path = pathOf(keys);
                out.putIfAbsent(path, new ArrayList<>());
                out.get(path).addAll(pending);
            }
            pending.clear();
        }
        return out;
    }

    /** Перекинуть сохранённые комментарии в новый дамп по совпадающим ключам. */
    private static String preserveComments(String old, String nv) {
        Map<String, List<String>> comments = collectComments(old);
        Map<String, String> inline = collectInline(old);
        if (comments.isEmpty() && inline.isEmpty()) {
            return nv;
        }
        Set<String> placed = new HashSet<>();
        StringBuilder out = new StringBuilder(nv.length() + 512);
        List<String> keys = new ArrayList<>();
        List<Integer> inds = new ArrayList<>();
        for (String line : nv.split("\n")) {
            String t = line.trim();
            if (t.startsWith("-") || !parseKeyLine(line, keys, inds)) {
                out.append(line).append('\n');
                continue;
            }
            String path = pathOf(keys);
            if (!placed.contains(path)) {
                placed.add(path);
                List<String> cs = comments.get(path);
                if (cs != null) {
                    for (String c : cs) {
                        out.append(c).append('\n');
                    }
                }
            }
            String tail = inline.get(path);
            if (tail != null && line.indexOf('#') < 0) {
                out.append(line).append(tail).append('\n');
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }
}