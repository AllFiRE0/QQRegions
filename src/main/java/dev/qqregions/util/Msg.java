package dev.qqregions.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Утилиты работы с цветами и текстом.
 * <p>
 * Каждая строка проходит через {@link #color(String)}. Поддерживаются:
 * <ul>
 *   <li>MiniMessage-теги и градиенты: {@code <gradient:#55ffff:#ff55ff>},
 *       {@code <rainbow>}, {@code <color:#FF5555>}, {@code <hover:...>} и пр.;</li>
 *   <li>весь legacy: {@code &c}, {@code &#RRGGBB}, {@code &x&R&R&G&G&B&B};</li>
 *   <li>токены Colors: имена, CMI-обёртки {@code {#FF5555}}, квадратные
 *       {@code [#lime]};</li>
 *   <li>голый {@code #RRGGBB} прямо перед текстом ({@code #FFFFFFописание}).</li>
 * </ul>
 * Строки без признаков MiniMessage идут тем же legacy-путём, что и раньше, —
 * ничего не ломается.
 */
public final class Msg {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    /** Ленивый MiniMessage: если библиотека недоступна в рантайме, legacy-путь
     *  работает, a мини-ветка просто отключается (важно для совместимости). */
    private static MiniMessage mini() {
        try {
            return MiniMessage.miniMessage();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Msg() {
    }

    /**
     * Преобразует строку в Adventure-компонент. Если строка содержит
     * MiniMessage-разметку (градиенты/теги), она парсится MiniMessage,
     * при этом legacy-коды ({@code &c}) заранее переводятся в мини-эквиваленты.
     * Иначе — legacy-путь через Colors (как раньше). Битые мини-строки
     * не роняют: откат на legacy-разбор.
     */
    public static Component color(String s) {
        if (s == null || s.isEmpty()) {
            return Component.empty();
        }
        String raw = s;
        if (looksLikeMini(raw)) {
            MiniMessage mm = mini();
            if (mm != null) {
                try {
                    // Голые #RRGGBB -> мини-теги, затем legacy '&' -> мини, затем парсим.
                    return mm.deserialize(legacyToMini(protectHex(raw, true)));
                } catch (Throwable ignored) {
                    // падение мини-парсера — не ломаем вывод, уходим на legacy.
                }
            }
        }
        return LEGACY.deserialize(Colors.toLegacy(protectHex(raw, false)));
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

    /** Признак того, что строка содержит MiniMessage-разметку. */
    private static final String[] MINI_MARKERS = {
            "<gradient", "<rainbow", "<color:", "<#", "<hover:", "<click:",
            "</", "<transition", "<key:", "<lang:", "<newline", "<br>",
            "<bold", "<italic", "<underlined", "<strikethrough", "<obfuscated",
            "<reset>", "<reset:"
    };

    private static boolean looksLikeMini(String s) {
        for (String marker : MINI_MARKERS) {
            if (s.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** Карта legacy-кодов в мини-теги. */
    private static final Map<Character, String> CODE_MAP = new HashMap<>();

    static {
        CODE_MAP.put('0', "<black>");
        CODE_MAP.put('1', "<dark_blue>");
        CODE_MAP.put('2', "<dark_green>");
        CODE_MAP.put('3', "<dark_aqua>");
        CODE_MAP.put('4', "<dark_red>");
        CODE_MAP.put('5', "<dark_purple>");
        CODE_MAP.put('6', "<gold>");
        CODE_MAP.put('7', "<gray>");
        CODE_MAP.put('8', "<dark_gray>");
        CODE_MAP.put('9', "<blue>");
        CODE_MAP.put('a', "<green>");
        CODE_MAP.put('b', "<aqua>");
        CODE_MAP.put('c', "<red>");
        CODE_MAP.put('d', "<light_purple>");
        CODE_MAP.put('e', "<yellow>");
        CODE_MAP.put('f', "<white>");
        CODE_MAP.put('k', "<obfuscated>");
        CODE_MAP.put('l', "<bold>");
        CODE_MAP.put('m', "<strikethrough>");
        CODE_MAP.put('n', "<underlined>");
        CODE_MAP.put('o', "<italic>");
        CODE_MAP.put('r', "<reset>");
    }

    private static boolean isHex(String s) {
        if (s.length() != 6) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Голый {@code #RRGGBB} (шесть hex-цифр) прямо в тексте -> токен.
     * mini=true: превращает в {@code <#RRGGBB>} (для MiniMessage),
     * mini=false: в {@code &#RRGGBB} (для legacy-пути Colors).
     * Внутри мини-тегов {@code <...>} не трогаем (там как раз и живут
     * градиентные «#55ffff», которые нельзя конвертировать).
     */
    private static String protectHex(String s, boolean mini) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '<') {
                int end = s.indexOf('>', i);
                if (end < 0) {
                    sb.append(s, i, n);
                    break;
                }
                sb.append(s, i, end + 1);
                i = end + 1;
                continue;
            }
            if (c == '#' && i + 7 <= n) {
                String hex = s.substring(i + 1, i + 7);
                if (isHex(hex)) {
                    sb.append(mini ? "<#" + hex + ">" : "&#" + hex);
                    i += 7;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /**
     * Переводит legacy-коды в мини-теги перед MiniMessage. Обрабатываются:
     * {@code &x&F&F&5&5&5&5}, {@code &#RRGGBB}, одиночные {@code &c}, {@code &l}
     * и т.д. Всё, что внутри уже существующих тегов {@code <...>}, не трогаем.
     */
    private static String legacyToMini(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 32);
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '<') {
                int end = s.indexOf('>', i);
                if (end < 0) {
                    sb.append(s, i, n);
                    break;
                }
                sb.append(s, i, end + 1);
                i = end + 1;
                continue;
            }
            if (c == '&' && i + 1 < n) {
                char code = s.charAt(i + 1);
                // &x&F&F&5&5&5&5 (ванильный RGB) — 6 пар "&<hex>"
                if ((code == 'x' || code == 'X') && i + 14 <= n && s.charAt(i + 2) == '&') {
                    StringBuilder hex = new StringBuilder(6);
                    int j = i + 2;
                    boolean ok = true;
                    for (int k = 0; k < 6; k++) {
                        if (j + 1 < n && s.charAt(j) == '&' && isHexChar(s.charAt(j + 1))) {
                            hex.append(s.charAt(j + 1));
                            j += 2;
                        } else {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) {
                        sb.append("<#").append(hex).append(">");
                        i = j;
                        continue;
                    }
                }
                // &#RRGGBB
                if (code == '#' && i + 8 <= n) {
                    String hex = s.substring(i + 2, i + 8);
                    if (isHex(hex)) {
                        sb.append("<#").append(hex).append(">");
                        i += 8;
                        continue;
                    }
                }
                // одиночные коды
                String tag = CODE_MAP.get(Character.toLowerCase(code));
                if (tag != null) {
                    sb.append(tag);
                    i += 2;
                    continue;
                }
            }
            if (c == '#' && i + 7 <= n) {
                String hex = s.substring(i + 1, i + 7);
                if (isHex(hex)) {
                    sb.append("<#").append(hex).append(">");
                    i += 7;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }
}