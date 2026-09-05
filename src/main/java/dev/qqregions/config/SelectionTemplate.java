package dev.qqregions.config;

import dev.qqregions.util.Expressions;
import dev.qqregions.util.Papi;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Один шаблон прав выделения.
 */
public class SelectionTemplate {

    private final String name;
    private final int priority;
    private final long maxBlocks;
    private final long minBlocks;
    private final int chunks;

    private final String permission;
    private final String placeholder;

    public SelectionTemplate(String name, int priority, long maxBlocks, long minBlocks,
                             int chunks, String permission, String placeholder) {
        this.name = name;
        this.priority = priority;
        this.maxBlocks = maxBlocks;
        this.minBlocks = minBlocks;
        this.chunks = chunks;
        this.permission = permission;
        this.placeholder = placeholder;
    }

    public static SelectionTemplate fromMap(String name, Map<?, ?> map) {
        int priority = toInt(map.get("priority"), 1);
        long maxBlocks = toLong(map.get("max-blocks"), 10000L);
        long minBlocks = toLong(map.get("min-blocks"), 100L);
        int chunks = toInt(map.get("chunks"), 16);
        String permission = str(map.get("permission"));
        String placeholder = str(map.get("placeholder"));
        return new SelectionTemplate(name, priority, maxBlocks, minBlocks, chunks, permission, placeholder);
    }

    public boolean matches(OfflinePlayer player) {
        boolean permOk = permission == null || permission.isEmpty()
                || (player instanceof Player p && p.hasPermission(permission));
        if (!permOk) {
            return false;
        }
        return Expressions.matches(placeholder, player);
    }

    public String getName() {
        return name;
    }

    public int getPriority() {
        return priority;
    }

    public long getMaxBlocks() {
        return maxBlocks;
    }

    public long getMinBlocks() {
        return minBlocks;
    }

    public int getChunks() {
        return chunks;
    }

    public String getPermission() {
        return permission;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int toInt(Object o, int def) {
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long toLong(Object o, long def) {
        try {
            return o == null ? def : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}