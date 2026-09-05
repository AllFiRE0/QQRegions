package dev.qqregions.util;

import org.bukkit.OfflinePlayer;

/**
 * Минимальный интерпретатор условий вида:
 *   "%placeholder%>=60", "%vault_rank%==VIP", "true", "false"
 * Сравнение чисел через >=, <=, >, <, ==, !=.
 * Если ни один оператор не найден — непустое значение, отличное от
 * "false"/"0"/"no", считается истиной.
 */
public final class Expressions {

    private Expressions() {
    }

    public static boolean matches(String expression, OfflinePlayer player) {
        if (expression == null) {
            return true;
        }
        String raw = expression.trim();
        if (raw.isEmpty()) {
            return true;
        }
        String value = Papi.set(player, raw).trim();

        String op = findOperator(value);
        if (op == null) {
            return !value.isEmpty()
                    && !value.equalsIgnoreCase("false")
                    && !value.equals("0")
                    && !value.equalsIgnoreCase("no");
        }

        String[] parts = value.split(java.util.regex.Pattern.quote(op), 2);
        if (parts.length < 2) {
            return false;
        }
        String left = parts[0].trim();
        String right = parts[1].trim();

        // пробуем числа
        try {
            double l = Double.parseDouble(left);
            double r = Double.parseDouble(right);
            switch (op) {
                case ">=": return l >= r;
                case "<=": return l <= r;
                case ">":  return l > r;
                case "<":  return l < r;
                case "==": return Math.abs(l - r) < 1e-9;
                case "!=": return Math.abs(l - r) >= 1e-9;
                default:   return false;
            }
        } catch (NumberFormatException e) {
            switch (op) {
                case "==": return left.equalsIgnoreCase(right);
                case "!=": return !left.equalsIgnoreCase(right);
                default:   return false;
            }
        }
    }

    private static String findOperator(String s) {
        for (String op : new String[]{">=", "<=", "==", "!=", ">", "<"}) {
            if (s.contains(op)) {
                return op;
            }
        }
        return null;
    }
}