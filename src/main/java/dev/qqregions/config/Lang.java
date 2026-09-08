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
    /** Встроенные переводы из jar — опора для пустых/битых значений файла. */
    private FileConfiguration defs;
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
        // Списки (lore кнопок и пр.): отсутствующие у игрока — из дефолтов.
        for (String key : defs.getKeys(true)) {
            if (!defs.isList(key)) {
                continue;
            }
            if (!cfg.isList(key)) {
                cfg.set(key, defs.getList(key));
            }
        }
        // Авто-обновление: проставляем актуальную версию lang.yml (новые
        // переводы уже подтянулись через defaults выше; пользовательские
        // непустые значения сохраняются).
        int jarVer = dev.qqregions.util.Yml.version(defs);
        if (jarVer > 0 && dev.qqregions.util.Yml.version(loaded) < jarVer) {
            cfg.set(dev.qqregions.util.Yml.VERSION_KEY, jarVer);
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
        p.sendMessage(compPrefixed(key, kv));
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
        sender.sendMessage(Msg.color(fmt(key, kv)));
    }

    /**
     * Отправить уже-отформатированный текст, как если бы это было значение
     * lang.yml: без префикса плагина, но с поддержкой "actionbar:N!".
     * Для текстов, которые собираются в другом месте (например, RaidManager).
     */
    public void sendRaw(Player p, String text) {
        if (text == null) {
            return;
        }
        dispatch(p, text);
    }

    private void dispatch(Player p, String text) {
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

    /** Экшнбар, обновляемый в течение N секунд (раз в 20 тиков). */
    public void sendActionbar(Player p, String raw, int seconds) {
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