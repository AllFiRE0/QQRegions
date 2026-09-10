package dev.qqregions.gui;

import dev.qqregions.QQRegions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URL;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кэш скинов игроков для голов PLAYER_HEAD + медленный последовательный
 * дофетч с session-сервера.
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
 */
public final class SkullResolver implements Listener {

    /** Пауза между запросами к session-серверу, мс. */
    private static final long REQUEST_GAP_MS = 1000L;
    /** Доп. пауза после рейт-лимита, мс. */
    private static final long RATE_LIMIT_GAP_MS = 30000L;

    private static final class Skinned {
        final URL url;
        final long at;

        Skinned(URL url, long at) {
            this.url = url;
            this.at = at;
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
            URL url = texturesOf(online.getPlayerProfile());
            if (url != null) {
                skins.put(uuid, new Skinned(url, System.currentTimeMillis()));
                setHead(meta, uuid, url);
                return;
            }
        }
        Skinned sk = skins.get(uuid);
        if (sk != null) {
            setHead(meta, uuid, sk.url);
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

    private static URL texturesOf(PlayerProfile profile) {
        PlayerTextures tx = profile.getTextures();
        return tx == null ? null : tx.getSkin();
    }

    private void setHead(SkullMeta meta, UUID uuid, URL url) {
        if (url == null) {
            return; // default-голова; настоящий скин придёт из кэша на следующей перерисовке
        }
        try {
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
            String base64 = java.util.Base64.getEncoder()
                    .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            MenuItem.applyHeadTexture(meta, base64);
        } catch (Throwable ignored) {
            // старая версия — просто голова
        }
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
        final PlayerProfile profile = Bukkit.createProfile(uuid);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = false;
            boolean limited = false;
            try {
                ok = profile.complete();
            } catch (Throwable t) {
                String m = String.valueOf(t.getMessage());
                limited = m.contains("429") || m.contains("status=")
                        || m.contains("HTTP_ERROR");
            }
            final boolean fixed = ok;
            final boolean rateLimited = limited;
            try {
                Bukkit.getScheduler().runTask(plugin,
                        () -> finish(uuid, profile, fixed, rateLimited));
            } catch (Throwable ignored) {
                // плагин уже выгружается — сброс в finish() не обязателен
            }
        });
    }

    private void finish(UUID uuid, PlayerProfile profile, boolean fixed, boolean rateLimited) {
        fetching = false;
        if (fixed) {
            URL url = null;
            try {
                PlayerTextures tx = profile.getTextures();
                url = tx == null ? null : tx.getSkin();
            } catch (Throwable ignored) {
            }
            pending.poll();
            lastRequest = System.currentTimeMillis();
            if (url != null) {
                skins.put(uuid, new Skinned(url, System.currentTimeMillis()));
                failed.remove(uuid);
            } else if (!skins.containsKey(uuid)) {
                // скина нет и старого кэша тоже — не зацикливаемся
                failed.add(uuid);
            }
        } else if (rateLimited) {
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