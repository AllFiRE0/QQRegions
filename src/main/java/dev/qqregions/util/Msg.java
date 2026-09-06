package dev.qqregions.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Утилиты работы с цветами и текстом.
 */
public final class Msg {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private Msg() {
    }

    /**
     * Преобразует строку в Adventure-компонент. Помимо '&'-кодов понимает
     * '#RRGGBB', '&x&R&R&G&G&B&B' и все форматы Colors (имена, {#FF5555},
     * [#lime], <#FF5555>, <color:#FF5555>, обёртки CMI). Неизвестные
     * токены проходят как есть — строка не ломается.
     */
    public static Component color(String s) {
        return LEGACY.deserialize(s == null ? "" : Colors.toLegacy(s));
    }

    /** Сериализует компонент обратно в строку с '§'. */
    public static String toLegacy(Component c) {
        return LEGACY.serialize(c);
    }

    /** Палитра-парсер (совместимость): любой формат -> awt Color, fallback зелёный. */
    public static java.awt.Color parseColor(String hex) {
        int rgb = Colors.parse(hex);
        return new java.awt.Color(rgb == -1 ? 0x00FF00 : rgb);
    }
}