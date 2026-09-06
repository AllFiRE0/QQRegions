package dev.qqregions.util;

import org.bukkit.boss.BarColor;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Универсальный разбор цвета «не упади на неизвестном».
 * Поддерживает (везде, где плагин читает цвет из конфига или сообщения):
 *   #FF5555, FF5555, имена (red, lime, hotpink),
 *   CMI: {#FF5555}, {#FF5555>}, {#lime},
 *   квадратные [#FF5555], [#lime], угловые <#FF5555>, <#lime>,
 *   #lime, ванильные коды (&c, &6, &a), ванильный RGB (&x&F&F&5&5&5&5),
 *   MiniMessage (<color:#FF5555>),
 * 140+ HTML/CSS-цветов с приставками light/dark/medium/pale.
 * Неизвестные токены и битые значения НЕ ломают строку — проходят как есть.
 */
public final class Colors {

    private static final Map<String, Integer> NAMES = new HashMap<>();
    private static final Pattern TOKEN = Pattern.compile(
            "&#([0-9A-Fa-f]{6})"
                    + "|\\{[ \\t#]*([0-9A-Fa-f]{6})[ \\t]*(>)?\\}"
                    + "|\\{[ \\t#]*([A-Za-z]+)[ \\t]*(>)?\\}"
                    + "|\\[[ \\t#]*([0-9A-Fa-f]{6})[ \\t]*\\]"
                    + "|\\[[ \\t#]*([A-Za-z]+)[ \\t]*\\]"
                    + "|<[ \\t#]*([0-9A-Fa-f]{6})[ \\t]*>"
                    + "|<[ \\t#]*([A-Za-z]+)[ \\t]*>"
                    + "|<color:#([0-9A-Fa-f]{6})>"
                    + "|#[A-Za-z]+");

    private Colors() {
    }

    static {
        // CSS3 named colors (W3C).
        put("aliceblue", 0xF0F8FF); put("antiquewhite", 0xFAEBD7); put("aqua", 0x00FFFF);
        put("aquamarine", 0x7FFFD4); put("azure", 0xF0FFFF); put("beige", 0xF5F5DC);
        put("bisque", 0xFFE4C4); put("black", 0x000000); put("blanchedalmond", 0xFFEBCD);
        put("blue", 0x0000FF); put("blueviolet", 0x8A2BE2); put("brown", 0xA52A2A);
        put("burlywood", 0xDEB887); put("cadetblue", 0x5F9EA0); put("chartreuse", 0x7FFF00);
        put("chocolate", 0xD2691E); put("coral", 0xFF7F50); put("cornflowerblue", 0x6495ED);
        put("cornsilk", 0xFFF8DC); put("crimson", 0xDC143C); put("cyan", 0x00FFFF);
        put("darkblue", 0x00008B); put("darkcyan", 0x008B8B); put("darkgoldenrod", 0xB8860B);
        put("darkgray", 0xA9A9A9); put("darkgreen", 0x006400); put("darkgrey", 0xA9A9A9);
        put("darkkhaki", 0xBDB76B); put("darkmagenta", 0x8B008B); put("darkolivegreen", 0x556B2F);
        put("darkorange", 0xFF8C00); put("darkorchid", 0x9932CC); put("darkred", 0x8B0000);
        put("darksalmon", 0xE9967A); put("darkseagreen", 0x8FBC8F); put("darkslateblue", 0x483D8B);
        put("darkslategray", 0x2F4F4F); put("darkslategrey", 0x2F4F4F); put("darkturquoise", 0x00CED1);
        put("darkviolet", 0x9400D3); put("deeppink", 0xFF1493); put("deepskyblue", 0x00BFFF);
        put("dimgray", 0x696969); put("dimgrey", 0x696969); put("dodgerblue", 0x1E90FF);
        put("firebrick", 0xB22222); put("floralwhite", 0xFFFAF0); put("forestgreen", 0x228B22);
        put("fuchsia", 0xFF00FF); put("gainsboro", 0xDCDCDC); put("ghostwhite", 0xF8F8FF);
        put("gold", 0xFFD700); put("goldenrod", 0xDAA520); put("gray", 0x808080);
        put("green", 0x008000); put("greenyellow", 0xADFF2F); put("grey", 0x808080);
        put("honeydew", 0xF0FFF0); put("hotpink", 0xFF69B4); put("indianred", 0xCD5C5C);
        put("indigo", 0x4B0082); put("ivory", 0xFFFFF0); put("khaki", 0xF0E68C);
        put("lavender", 0xE6E6FA); put("lavenderblush", 0xFFF0F5); put("lawngreen", 0x7CFC00);
        put("lemonchiffon", 0xFFFACD); put("lightblue", 0xADD8E6); put("lightcoral", 0xF08080);
        put("lightcyan", 0xE0FFFF); put("lightgoldenrodyellow", 0xFAFAD2); put("lightgray", 0xD3D3D3);
        put("lightgreen", 0x90EE90); put("lightgrey", 0xD3D3D3); put("lightpink", 0xFFB6C1);
        put("lightsalmon", 0xFFA07A); put("lightseagreen", 0x20B2AA); put("lightskyblue", 0x87CEFA);
        put("lightslategray", 0x778899); put("lightslategrey", 0x778899); put("lightsteelblue", 0xB0C4DE);
        put("lightyellow", 0xFFFFE0); put("lime", 0x00FF00); put("limegreen", 0x32CD32);
        put("linen", 0xFAF0E6); put("magenta", 0xFF00FF); put("maroon", 0x800000);
        put("mediumaquamarine", 0x66CDAA); put("mediumblue", 0x0000CD); put("mediumorchid", 0xBA55D3);
        put("mediumpurple", 0x9370DB); put("mediumseagreen", 0x3CB371); put("mediumslateblue", 0x7B68EE);
        put("mediumspringgreen", 0x00FA9A); put("mediumturquoise", 0x48D1CC); put("mediumvioletred", 0xC71585);
        put("midnightblue", 0x191970); put("mintcream", 0xF5FFFA); put("mistyrose", 0xFFE4E1);
        put("moccasin", 0xFFE4B5); put("navajowhite", 0xFFDEAD); put("navy", 0x000080);
        put("oldlace", 0xFDF5E6); put("olive", 0x808000); put("olivedrab", 0x6B8E23);
        put("orange", 0xFFA500); put("orangered", 0xFF4500); put("orchid", 0xDA70D6);
        put("palegoldenrod", 0xEEE8AA); put("palegreen", 0x98FB98); put("paleturquoise", 0xAFEEEE);
        put("palevioletred", 0xDB7093); put("papayawhip", 0xFFEFD5); put("peachpuff", 0xFFDAB9);
        put("peru", 0xCD853F); put("pink", 0xFFC0CB); put("plum", 0xDDA0DD);
        put("powderblue", 0xB0E0E6); put("purple", 0x800080); put("rebeccapurple", 0x663399);
        put("red", 0xFF0000); put("rosybrown", 0xBC8F8F); put("royalblue", 0x4169E1);
        put("saddlebrown", 0x8B4513); put("salmon", 0xFA8072); put("sandybrown", 0xF4A460);
        put("seagreen", 0x2E8B57); put("seashell", 0xFFF5EE); put("sienna", 0xA0522D);
        put("silver", 0xC0C0C0); put("skyblue", 0x87CEEB); put("slateblue", 0x6A5ACD);
        put("slategray", 0x708090); put("slategrey", 0x708090); put("snow", 0xFFFAFA);
        put("springgreen", 0x00FF7F); put("steelblue", 0x4682B4); put("tan", 0xD2B48C);
        put("teal", 0x008080); put("thistle", 0xD8BFD8); put("tomato", 0xFF6347);
        put("turquoise", 0x40E0D0); put("violet", 0xEE82EE); put("wheat", 0xF5DEB3);
        put("white", 0xFFFFFF); put("whitesmoke", 0xF5F5F5); put("yellow", 0xFFFF00);
        put("yellowgreen", 0x9ACD32);
        // Распространённые игровые алиасы.
        put("aqua", 0x55FFFF); put("dark_aqua", 0x00AAAA); put("dark_blue", 0x0000AA);
        put("dark_gray", 0x555555); put("dark_green", 0x00AA00); put("dark_purple", 0xAA00AA);
        put("dark_red", 0xAA0000); put("gold", 0xFFAA00); put("light_purple", 0xFF55FF);
        put("red", 0xFF5555); put("yellow", 0xFFFF55); put("white", 0xFFFFFF);
    }

    private static void put(String name, int rgb) {
        NAMES.put(name, rgb);
    }

    /**
     * Возвращает строку для Legacy-сериализатора: все распознанные цветовые
     * токены заменены на ванильный формат (&x&F&F&5&5&5&5), всё неизвестное
     * остаётся как есть. Никогда не бросает исключений.
     */
    public static String toLegacy(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        Matcher m = TOKEN.matcher(raw);
        int last = 0;
        while (m.find()) {
            String full = m.group();
            sb.append(raw, last, m.start());
            int rgb = tokenRgb(full);
            if (rgb >= 0) {
                sb.append(hexLegacy(rgb));
            } else {
                sb.append(full);
            }
            last = m.end();
        }
        sb.append(raw, last, raw.length());
        return sb.toString();
    }

    /** #RRGGBB -> Bukkit Color; неизвестное/битое -> fallback (не бросает). */
    public static org.bukkit.Color bukkit(String raw, org.bukkit.Color def) {
        int rgb = parse(raw);
        return rgb >= 0 ? org.bukkit.Color.fromRGB(rgb) : def;
    }

    /** Наименование BarColor (WHITE/RED/...) или любой цвет -> ближайший из 7. */
    public static BarColor bar(String raw, BarColor def) {
        if (raw == null) {
            return def;
        }
        String up = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (up.startsWith("#")) {
            up = up.substring(1);
        }
        for (BarColor c : BarColor.values()) {
            if (c.name().equals(up)) {
                return c;
            }
        }
        int rgb = parse(raw);
        if (rgb < 0) {
            return def;
        }
        BarColor best = def;
        double bestDist = Double.MAX_VALUE;
        for (BarColor c : BarColor.values()) {
            double d = dist(rgb, BAR_RGB.get(c));
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    /** Фиксированные ЦБ палитры боссбара (у org.bukkit.boss.BarColor нет getColor()). */
    private static final Map<BarColor, Integer> BAR_RGB = new HashMap<>();

    static {
        BAR_RGB.put(BarColor.PINK, 0xF38BAA);
        BAR_RGB.put(BarColor.BLUE, 0x3C44AA);
        BAR_RGB.put(BarColor.RED, 0xFF5555);
        BAR_RGB.put(BarColor.GREEN, 0x55FF55);
        BAR_RGB.put(BarColor.YELLOW, 0xFFFF55);
        BAR_RGB.put(BarColor.PURPLE, 0xAA00AA);
        BAR_RGB.put(BarColor.WHITE, 0xFFFFFF);
    }

    /** Цвет в любом поддерживаемом формате -> RGB int (-1 = не распознан). */
    public static int parse(String raw) {
        if (raw == null) {
            return -1;
        }
        String s = raw.trim();
        // MiniMessage
        if (s.startsWith("<color:#") && s.endsWith(">")) {
            s = s.substring(1, s.length() - 1).substring("color:".length());
        }
        // одиночные обёртки CMI/скобок: {#FF5555} [#lime] <#FF5555>
        if (s.length() >= 3) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '{' && last == '}') || (first == '[' && last == ']') || (first == '<' && last == '>')) {
                s = s.substring(1, s.length() - 1).trim();
                int gt = s.indexOf('>');
                if (gt >= 0) {
                    s = s.substring(0, gt).trim();
                }
            }
        }
        if (s.startsWith("#")) {
            s = s.substring(1).trim();
        }
        if (s.length() == 6 && s.matches("[0-9A-Fa-f]{6}")) {
            return Integer.parseInt(s, 16);
        }
        int rgb = named(s);
        if (rgb >= 0) {
            return rgb;
        }
        return shade(s);
    }

    // -------- внутреннее --------

    private static int tokenRgb(String token) {
        String s = token;
        // MiniMessage <color:#FF5555>
        if (s.startsWith("<color:#")) {
            s = s.substring(7, s.length() - 1);
            return hex6(s);
        }
        // CMI {#FF5555} / {#FF5555>} / {#lime} / {#lime>}
        if (s.startsWith("{") && s.endsWith("}")) {
            String inner = s.substring(1, s.length() - 1);
            int gt = inner.indexOf('>');
            if (gt >= 0) {
                inner = inner.substring(0, gt);
            }
            inner = inner.trim();
            return hexOrName(inner);
        }
        // [#FF5555] [#lime] <#FF5555> <#lime>
        if ((s.startsWith("[") && s.endsWith("]")) || (s.startsWith("<") && s.endsWith(">"))) {
            String inner = s.substring(1, s.length() - 1).trim();
            return hexOrName(inner);
        }
        // &#FF5555
        if (s.startsWith("&#")) {
            return hex6(s.substring(2));
        }
        // #lime
        if (s.startsWith("#")) {
            String name = s.substring(1).trim();
            int rgb = named(name);
            if (rgb >= 0) {
                return rgb;
            }
            return shade(name);
        }
        return -1;
    }

    private static int hexOrName(String s) {
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() == 6 && s.matches("[0-9A-Fa-f]{6}")) {
            return Integer.parseInt(s, 16);
        }
        int rgb = named(s);
        if (rgb >= 0) {
            return rgb;
        }
        return shade(s);
    }

    private static int hex6(String s) {
        if (s.length() == 6 && s.matches("[0-9A-Fa-f]{6}")) {
            return Integer.parseInt(s, 16);
        }
        return -1;
    }

    private static int named(String lower) {
        lower = lower.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains(" ")) {
            lower = lower.replace(" ", "_");
        }
        Integer v = NAMES.get(lower);
        return v == null ? -1 : v;
    }

    /** Приставки light/dark/medium/pale к базовому имени (напр. lightblue). */
    private static int shade(String lower) {
        for (String prefix : new String[]{"light", "dark", "medium", "pale"}) {
            if (lower.startsWith(prefix) && lower.length() > prefix.length()) {
                int base = named(lower.substring(prefix.length()));
                if (base >= 0) {
                    switch (prefix) {
                        case "light":
                            return blend(base, 0xFFFFFF, 0.45f);
                        case "dark":
                            return scale(base, 0.55f);
                        case "medium":
                            return base;
                        default:
                            return blend(base, 0xFFFFFF, 0.55f);
                    }
                }
            }
        }
        return -1;
    }

    private static int scale(int rgb, float f) {
        int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * f);
        int g = (int) Math.min(255, ((rgb >> 8) & 0xFF) * f);
        int b = (int) Math.min(255, (rgb & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    private static int blend(int rgb, int other, float t) {
        int r = Math.round(((rgb >> 16) & 0xFF) * (1 - t) + ((other >> 16) & 0xFF) * t);
        int g = Math.round(((rgb >> 8) & 0xFF) * (1 - t) + ((other >> 8) & 0xFF) * t);
        int b = Math.round((rgb & 0xFF) * (1 - t) + (other & 0xFF) * t);
        return Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b);
    }

    private static double dist(int rgb, int other) {
        double dr = ((rgb >> 16) & 0xFF) - ((other >> 16) & 0xFF);
        double dg = ((rgb >> 8) & 0xFF) - ((other >> 8) & 0xFF);
        double db = (rgb & 0xFF) - (other & 0xFF);
        return dr * dr + dg * dg + db * db;
    }

    private static String hexLegacy(int rgb) {
        char[] hex = new char[]{'x',
                hexChar((rgb >> 20) & 0xF), hexChar((rgb >> 16) & 0xF),
                hexChar((rgb >> 12) & 0xF), hexChar((rgb >> 8) & 0xF),
                hexChar((rgb >> 4) & 0xF), hexChar(rgb & 0xF)};
        StringBuilder sb = new StringBuilder(13);
        for (char c : hex) {
            sb.append('&').append(c);
        }
        return sb.toString();
    }

    private static char hexChar(int v) {
        return v < 10 ? (char) ('0' + v) : (char) ('A' + v - 10);
    }
}