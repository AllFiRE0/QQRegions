package dev.qqregions.util;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;

/**
 * Тонкая обёртка PlaceholderAPI. Если PAPI не установлен — текст
 * возвращается без изменений.
 */
public final class Papi {
    private static boolean enabled = false;

    private Papi() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** Заменяет %заполнители% PlaceholderAPI для игрока. */
    public static String set(OfflinePlayer player, String text) {
        if (text == null) {
            return "";
        }
        if (enabled && player != null) {
            return PlaceholderAPI.setPlaceholders(player, text);
        }
        return text;
    }
}