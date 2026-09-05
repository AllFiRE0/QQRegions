package dev.qqregions.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Утилиты работы с цветами и текстом.
 */
public final class Msg {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Msg() {
    }

    /** Преобразует строку с '&'-кодами и '&#RRGGBB' в Adventure-компонент. */
    public static Component color(String s) {
        return LEGACY.deserialize(s == null ? "" : s);
    }

    /** Сериализует компонент обратно в строку с '§'. */
    public static String toLegacy(Component c) {
        return LEGACY.serialize(c);
    }

    /** Палитра-парсер: "#00ff00" -> Bukkit Color. */
    public static java.awt.Color parseColor(String hex) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return new java.awt.Color(Integer.parseInt(h, 16));
        } catch (Exception e) {
            return java.awt.Color.GREEN;
        }
    }
}