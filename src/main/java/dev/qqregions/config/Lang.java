package dev.qqregions.config;

import dev.qqregions.QQRegions;
import dev.qqregions.util.Msg;
import dev.qqregions.util.Papi;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Языковой файл lang.yml. Все сообщения плагина хранятся здесь в
 * формате "ключ: перевод". Заполнители {имя} подставляются через
 * {@link #fmt(String, String...)}.
 */
public class Lang {

    private final QQRegions plugin;
    private FileConfiguration cfg;
    /** Встроенные переводы из jar — опора для отсутствующих ключей файла. */
    private FileConfiguration defs;
    /** Ключи, реально присутствующие в файле игрока: '' в файле = «выключено»,
     *  и не подменяется дефолтом. Заполняется ДО наложения defaults на cfg. */
    private Set<String> fileKeys;
    /** Активные задачи экшнбаров (уникальный UUID игрока). */
    private final Map<UUID, Integer> actionbarTasks = new HashMap<>();

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
            this.fileKeys = new java.util.HashSet<>();
            return;
        }
        // Снапшот ключей ДО наложения дефолтов: пустое значение в файле
        // остаётся пустым ('' = выключено), к отсутствующим подтянутся дефолты.
        this.fileKeys = new java.util.HashSet<>(loaded.getKeys(true));
        cfg = loaded;
        cfg.setDefaults(defs);
        cfg.options().copyDefaults(true);
        // НЕ восстанавливаем дефолты для пустых значений игрока: '' в lang.yml
        // означает «сообщение выключено» (и не перезаписывается при релоаде).
        // Отсутствующие ключи и так подтягиваются из дефолтов (copyDefaults).
        // Списки (lore кнопок и пр.): отсутствующие у игрока — из дефолтов.
        for (String key : defs.getKeys(true)) {
            if (!defs.isList(key)) {
                continue;
            }
            if (!cfg.isList(key)) {
                cfg.set(key, defs.getList(key));
            }
        }
        // Сохраняем ТОЛЬКО если реально что-то изменилось (появились новые
        // ключи из jar / поднялся config-version): иначе файл игрока — вместе
        // с его комментариями — на релоаде НЕ трогаем. Перезапись идёт через
        // savePreserving, переносящий комментарии разработчика сервера.
        int jarVer = dev.qqregions.util.Yml.version(defs);
        this.defs = defs;
        boolean changed = jarVer > 0 && dev.qqregions.util.Yml.version(loaded) < jarVer;
        if (!changed) {
            for (String key : defs.getKeys(true)) {
                if (!fileKeys.contains(key)) {
                    changed = true;
                    break;
                }
            }
        }
        if (!changed) {
            return;
        }
        if (jarVer > 0) {
            cfg.set(dev.qqregions.util.Yml.VERSION_KEY, jarVer);
        }
        try {
            dev.qqregions.util.Yml.savePreserving(file, cfg);
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
        if (fileKeys != null && fileKeys.contains(key)) {
            // Ключ есть в файле игрока: '' = «выключено» (не подменяем дефолтом);
            // значение возвращается как есть.
            String v = cfg.getString(key);
            return v == null ? "" : v;
        }
        // Ключ отсутствует в файле игрока — дефолт из jar ('' если и его нет).
        if (defs != null && defs.isString(key)) {
            return defs.getString(key, "");
        }
        String v = cfg.getString(key);
        return v == null ? "" : v;
    }

    public boolean has(String key) {
        return cfg.isString(key);
    }

    /** Ключ сообщения об установке точки: pos-set-<т> (цветные координаты),
     *  с фолбэком на pos-set, если на сервере старый lang.yml без новых ключей. */
    public String posSetKey(int which) {
        return has("select.pos-set-" + which) ? "select.pos-set-" + which : "select.pos-set";
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

    /**
     * Отправить сообщение игроку, учитывая спец-префикс "actionbar:N!" в конце
     * перевода: тогда текст идёт в экшнбар и повторяется N секунд (каждые 20
     * тиков), без [префикса] плагина. Иначе — обычное сообщение с префиксом.
     */
    private static final Pattern ACTIONBAR_PREFIX =
            Pattern.compile("^actionbar:(\\d+)!(.*)$", Pattern.DOTALL);

    public void send(Player p, String key, String... kv) {
        String msg = fmt(key, kv);
        Matcher m = ACTIONBAR_PREFIX.matcher(msg);
        if (m.matches()) {
            sendActionbar(p, m.group(2), parseIntSafe(m.group(1)));
            return;
        }
        if (msg.isBlank()) {
            // '' = выключено: ничего не выводим (даже пустую строку с префиксом).
            return;
        }
        p.sendMessage(compPrefixed(key, kv));
    }

    /** Префиксная отправка для консоли/командного отправителя: без actionbar
     *  (он только у Player), но с той же семантикой '' = выключено. */
    public void send(CommandSender sender, String key, String... kv) {
        if (sender instanceof Player p) {
            send(p, key, kv);
            return;
        }
        String msg = fmt(key, kv);
        if (msg == null || msg.isBlank()) {
            return;
        }
        sender.sendMessage(Msg.color(get("prefix") + msg));
    }

    /**
     * Отправить сообщение БЕЗ префикса плагина (как «сырой» comp), но с
     * поддержкой спец-префикса "actionbar:N!": если перевод начинается с
     * него — текст уходит в экшнбар на N секунд. Используется там, где
     * раньше был прямой sendMessage(lang().comp(...)) — теперь любой такой
     * ключ можно перевести в экшнбар прямо из lang.yml.
     */
    public void sendMsg(Player p, String key, String... kv) {
        dispatch(p, fmt(key, kv));
    }

    /** Для консоли/CommandSender: без actionbar (он только у Player), как comp. */
    public void sendMsg(CommandSender sender, String key, String... kv) {
        if (sender instanceof Player p) {
            dispatch(p, fmt(key, kv));
            return;
        }
        String msg = fmt(key, kv);
        if (msg == null || msg.isBlank()) {
            return;
        }
        sender.sendMessage(Msg.color(msg));
    }

    /**
     * Отправить уже-отформатированный текст, как если бы это было значение
     * lang.yml: без префикса плагина, но с поддержкой "actionbar:N!".
     * Для текстов, которые собираются в другом месте (например, RaidManager).
     */
    public void sendRaw(Player p, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        dispatch(p, text);
    }

    private void dispatch(Player p, String text) {
        if (text == null || text.isBlank()) {
            // '' = выключено: не выводим даже пустую строку.
            return;
        }
        Matcher m = ACTIONBAR_PREFIX.matcher(text);
        if (m.matches()) {
            sendActionbar(p, m.group(2), parseIntSafe(m.group(1)));
            return;
        }
        p.sendMessage(Msg.color(text));
    }

    /** Короткое отображение минут как «7д 3ч» / «5ч 20м» / «45м». Единицы «д/ч/м»
     *  берутся из lang.yml (menu.time-short-*), чтобы админ мог подстроить формат. */
    public String shortTime(long minutes) {
        String d = get("menu.time-short-day");
        String h = get("menu.time-short-hour");
        String m = get("menu.time-short-min");
        if (minutes >= 1440) {
            long days = minutes / 1440;
            long hr = (minutes % 1440) / 60;
            return hr > 0 ? days + d + " " + hr + h : days + d;
        }
        if (minutes >= 60) {
            long hr = minutes / 60;
            long mi = minutes % 60;
            return mi > 0 ? hr + h + " " + mi + m : hr + h;
        }
        return minutes + m;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 1;
        }
    }

    /** Экшнбар, обновляемый в течение N секунд (раз в 20 тиков). '' = выключено. */
    public void sendActionbar(Player p, String raw, int seconds) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        UUID id = p.getUniqueId();
        Integer prev = actionbarTasks.remove(id);
        if (prev != null) {
            plugin.getServer().getScheduler().cancelTask(prev);
        }
        final Component comp = Msg.color(Papi.set(p, raw));
        final long until = System.currentTimeMillis() + (long) Math.max(0, seconds) * 1000L;
        final int[] tid = new int[1];
        tid[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                if (!p.isOnline() || System.currentTimeMillis() >= until) {
                    plugin.getServer().getScheduler().cancelTask(tid[0]);
                    actionbarTasks.remove(id);
                    return;
                }
                p.sendActionBar(comp);
            }
        }, 0L, 20L).getTaskId();
        actionbarTasks.put(id, tid[0]);
    }
}