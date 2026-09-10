package dev.qqregions.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.qqregions.QQRegions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Кэш скинов игроков для голов PLAYER_HEAD + медленный последовательный
 * дофетч с session-сервера Mojang.
 *
 * Прямой SkullMeta#setOwningPlayer(OfflinePlayer) делает HTTP-запрос к Mojang
 * на КАЖДОГО игрока на КАЖДУЮ перерисовку меню: список участников с 50+ головами
 * и автообновлением раз в секунду (update_interval) давал тысячи запросов и
 * рейт-лимит 429 (спам в консоль "Couldn't look up profile properties").
 * Здесь:
 *  - онлайн-игрок берётся сразу из своего профиля (без сети); при его входе
 *    кэш сбрасывается, чтобы новый скин применялся сразу;
 *  - оффлайн: скин запрашивается один раз, строго по одному (пауза между
 *    запросами, долгий откат при 429) и кладётся в кэш. Старившийся скин
 *    перепроверяется не чаще раза в config player-search.texture-refresh-seconds
 *    (по умолчанию 300; 0 = не перепроверять) при показе кнопки;
 *  - пока скин неизвестен — голова без скина (сборка меню не блокируется,
 *    следующая перерисовка подхватит скин из кэша).
 *
 * Дофетч идёт напрямую по HTTP к sessionserver.mojang.com (тот же endpoint,
 * что использует setOwningPlayer): ответ парсится регуляркой, из properties
 * забирается Base64-текстура ("value") без зависимостей от профильных API.
 * Установка скина на голову — paper-профиль (MenuItem.applyHeadTexture):
 * createProfile(uuid) + setProperty("textures", base64) + setPlayerProfile,
 * рефлексия OBC — только запасной вариант.
 */
public final class SkullResolver implements Listener {

    /** Пауза между запросами к session-серверу, мс. */
    private static final long REQUEST_GAP_MS = 1000L;
    /** Доп. пауза после рейт-лимита, мс. */
    private static final long RATE_LIMIT_GAP_MS = 30000L;
    /** Base64-текстура («value» свойства textures) — сплошные A-Za-z0-9+/=
     *  до закрывающей кавычки; завершающий символ не требует, т.к. длинная. */
    private static final Pattern VALUE =
            Pattern.compile("\"value\"\\s*:\\s*\"([A-Za-z0-9+/=]+)");

    private static final class Skinned {
        final String base64;
        final long at;

        Skinned(String base64, long at) {
            this.base64 = base64;
            this.at = at;
        }
    }

    /** Итог запроса к session-серверу. */
    private static final class Fetched {
        final String base64;   // Base64-текстура ("" — профиль без скина; null — ошибка)
        final boolean rateLimited;
        final boolean ok;      // ответ получен (200)

        Fetched(String base64, boolean rateLimited, boolean ok) {
            this.base64 = base64;
            this.rateLimited = rateLimited;
            this.ok = ok;
        }
    }

    private final Map<UUID, Skinned> skins = new ConcurrentHashMap<>();
    private final Deque<UUID> pending = new ArrayDeque<>();
    /** UUID, которые закончились «жёсткой» ошибкой (не 429): до перезапуска
     *  сервера не запрашиваем заново, чтобы не молотить session-сервер. */
    private final java.util.Set<UUID> failed = ConcurrentHashMap.newKeySet();
    private final QQRegions plugin;

    private boolean fetching = false;
    private long lastRequest = 0;

    public SkullResolver(QQRegions plugin) {
        this.plugin = plugin;
    }

    /** Поставить голову игрока на SkullMeta (синхронно, при сборке меню):
     *  онлайн — актуальный скин из профиля; иначе из кэша (при необходимости
     *  ставится в очередь перепроверки); нет скина — простая голова + запрос. */
    public void applyHead(SkullMeta meta, UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            String b64 = texturesOf(online.getPlayerProfile());
            if (b64 != null) {
                skins.put(uuid, new Skinned(b64, System.currentTimeMillis()));
                setHead(meta, uuid, b64);
                return;
            }
        }
        Skinned sk = skins.get(uuid);
        if (sk != null) {
            setHead(meta, uuid, sk.base64);
            long maxAge = plugin.config().playerSearchTextureRefreshMs();
            if (maxAge > 0 && System.currentTimeMillis() - sk.at >= maxAge) {
                // скин мог устареть — перепроверить, не снимая старый
                enqueue(uuid, true);
            }
            return;
        }
        setHead(meta, uuid, null);
        enqueue(uuid, false);
    }

    /** Base64-текстура из живого paper-профиля игрока (свойство "textures").
     *  В Leaf getProperties() возвращает МНОЖЕСТВО ProfileProperty, а не Map —
     *  ищем по имени. */
    private static String texturesOf(PlayerProfile profile) {
        try {
            for (ProfileProperty p : profile.getProperties()) {
                if ("textures".equals(p.getName())) {
                    return p.getValue();
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    private void setHead(SkullMeta meta, UUID uuid, String base64) {
        if (base64 == null || base64.isEmpty()) {
            return; // default-голова; настоящий скин придёт из кэша на следующей перерисовке
        }
        MenuItem.applyHeadTexture(meta, uuid, base64);
    }

    private void enqueue(UUID uuid, boolean allowRefresh) {
        if (pending.contains(uuid)) {
            return;
        }
        if (!allowRefresh && failed.contains(uuid)) {
            return;
        }
        pending.add(uuid);
    }

    /** Вызывается из тикера плагина: строго ОДИН запрос за раз, с паузой.
     *  Пустая очередь / уже идущий запрос — сразу выход. */
    public void poll() {
        if (fetching || pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRequest < REQUEST_GAP_MS) {
            return;
        }
        final UUID uuid = pending.peek();
        if (uuid == null) {
            return;
        }
        fetching = true;
        lastRequest = now;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Fetched f = fetchTexture(uuid);
            try {
                Bukkit.getScheduler().runTask(plugin, () -> finish(uuid, f));
            } catch (Throwable ignored) {
                // плагин уже выгружается — сброс в finish() не обязателен
            }
        });
    }

    /** Запрос к sessionserver.mojang.com (поток вне main). */
    private static Fetched fetchTexture(UUID uuid) {
        String hex = uuid.toString().replace("-", "");
        HttpURLConnection conn = null;
        try {
            URL u = new URL("https://sessionserver.mojang.com/session/minecraft/profile/"
                    + hex);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "QQRegions skull-resolver");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code == 429) {
                return new Fetched(null, true, false);
            }
            if (code != 200) {
                return new Fetched(null, false, false);
            }
            try (InputStream in = conn.getInputStream()) {
                String body = new String(readAll(in), StandardCharsets.UTF_8);
                String b64 = textureValueOf(body);
                return new Fetched(b64 == null ? "" : b64, false, true);
            }
        } catch (Throwable ignored) {
            return new Fetched(null, false, false);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** "value" свойства textures из JSON ответа sessionserver (без JSON-библиотеки).
     *  Значение — Base64 длиной до нескольких тысяч символов, поэтому окно поиска
     *  с запасом, а паттерн захватывает до кавычки/запятой. */
    private static String textureValueOf(String body) {
        int cursor = 0;
        while (cursor < body.length()) {
            int idx = body.indexOf("\"textures\"", cursor);
            if (idx < 0) {
                return null;
            }
            int start = Math.max(0, idx - 100);
            int end = Math.min(body.length(), idx + 8000);
            Matcher m = VALUE.matcher(body.substring(start, end));
            if (m.find()) {
                return m.group(1);
            }
            cursor = idx + 10;
        }
        return null;
    }

    private void finish(UUID uuid, Fetched f) {
        fetching = false;
        if (f.ok && f.base64 != null && !f.base64.isEmpty()) {
            pending.poll();
            lastRequest = System.currentTimeMillis();
            skins.put(uuid, new Skinned(f.base64, System.currentTimeMillis()));
            failed.remove(uuid);
        } else if (f.ok) {
            // ответ получен, но скина нет (профиль без текстур) — не зацикливаемся
            pending.poll();
            if (!skins.containsKey(uuid)) {
                failed.add(uuid);
            }
        } else if (f.rateLimited) {
            // 429 — жёсткая пауза, потом повтор (UUID остаётся в очереди)
            lastRequest = System.currentTimeMillis() + RATE_LIMIT_GAP_MS;
        } else {
            // другая ошибка (оффлайн-сервер без session-сервера и т.п.) —
            // снимаем с очереди и больше не пробуем до перезапуска
            failed.add(uuid);
            pending.poll();
        }
    }

    /** Игрок вошёл: его профиль теперь «живой» и актуальный — сбрасываем кэш,
     *  чтобы головы сразу показывали новый скин (без запросов к Mojang). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        skins.remove(e.getPlayer().getUniqueId());
    }

    public void clear() {
        skins.clear();
        pending.clear();
        failed.clear();
        fetching = false;
    }
}