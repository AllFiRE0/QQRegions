package dev.qqregions.util;

/**
 * Форматирование длительности (млс) в текст: «1 г, 2 мес, 3 д, 4 ч, 5 мин, 6 сек».
 * Единицы и разделитель берутся из lang.yml (menu.time-*), чтобы админ мог
 * подстроить формат под свой сервер (1д / 1 день / 1 д / 1 ДНЕЙ и т.п.) —
 * например для внешнего заполнителя в скорборде (%qqregions_region_rent_time_%).
 */
public final class TimeFmt {

    private final dev.qqregions.QQRegions plugin;

    public TimeFmt(dev.qqregions.QQRegions plugin) {
        this.plugin = plugin;
    }

    /** Строка «1 г, 2 мес, 3 д...» по шаблонам из lang.yml; нулевые единицы
     *  пропускаются, пустой результат → menu.time-empty. */
    public String format(long millis) {
        if (millis < 1000) {
            return plugin.lang().get("menu.time-empty");
        }
        long t = millis / 1000;
        long y = t / 31_536_000L;
        t %= 31_536_000L;
        long mo = t / 2_592_000L;
        t %= 2_592_000L;
        long d = t / 86_400L;
        t %= 86_400L;
        long h = t / 3_600L;
        t %= 3_600L;
        long mi = t / 60L;
        long s = t % 60L;
        java.util.List<String> parts = new java.util.ArrayList<>();
        put(parts, y, "menu.time-year");
        put(parts, mo, "menu.time-month");
        put(parts, d, "menu.time-day");
        put(parts, h, "menu.time-hour");
        put(parts, mi, "menu.time-min");
        put(parts, s, "menu.time-sec");
        if (parts.isEmpty()) {
            return plugin.lang().get("menu.time-empty");
        }
        return String.join(plugin.lang().get("menu.time-join"), parts);
    }

    private void put(java.util.List<String> parts, long n, String key) {
        if (n <= 0) {
            return;
        }
        parts.add(plugin.lang().get(key).replace("{n}", String.valueOf(n)));
    }

    /**
     * Компактный таймер (обратный отсчёт, секунды) для боссбаров/механик.
     * Отличается от {@link #format}: показывает только старшие ненулевые единицы
     * (две, если включено time-format.units: TWO, иначе одну) и пуст при 0:
     * месяц(30д)→дни, →часы, →минуты, →секунды, < 1 сек — пусто.
     * Единицы и разделитель — те же, что у {@link #format} (lang.yml menu.time-*).
     */
    public String timer(long seconds) {
        if (seconds <= 0) {
            return "";
        }
        long[] units = {2_592_000L, 86_400L, 3_600L, 60L, 1L};
        String[] keys = {"menu.time-month", "menu.time-day", "menu.time-hour", "menu.time-min", "menu.time-sec"};
        java.util.List<String> parts = new java.util.ArrayList<>();
        int first = -1;
        for (int i = 0; i < units.length; i++) {
            if (seconds >= units[i]) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            first = units.length - 1;
        }
        add(parts, seconds / units[first], keys[first]);
        if (plugin.config().timeFmtTwoUnits() && first + 1 < units.length) {
            long rest = (seconds % units[first]) / units[first + 1];
            if (rest > 0) {
                add(parts, rest, keys[first + 1]);
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        return String.join(plugin.lang().get("menu.time-join"), parts);
    }

    private void add(java.util.List<String> parts, long n, String key) {
        if (n <= 0) {
            return;
        }
        parts.add(plugin.lang().get(key).replace("{n}", String.valueOf(n)));
    }
}