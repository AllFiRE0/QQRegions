package dev.qqregions.gui;

import dev.qqregions.QQRegions;
import dev.qqregions.util.Msg;
import dev.qqregions.util.Papi;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Кнопка меню. name/lore/commands поддерживают {переменные} контекста,
 * %заполнители% PlaceholderAPI и подмену значений через replace.yml.
 */
public class MenuItem {

    private static final Pattern PH = Pattern.compile("%([^%]+)%");

    private final String material;
    private final int amount;
    private final Integer slot;
    private final String name;
    private final List<String> lore;
    private final List<String> commands;
    private final String permission;

    /** имя флага для динамических кнопок, null для статичных */
    private final String flag;
    /** группа флага: all / members / owners / ... (null = не динамическая) */
    private final String group;
    /** список значений для цикла (например [allow, deny]) */
    private final List<String> states;
    /** флаг является StateFlag (переключение allow<->deny по умолчанию) */
    private final boolean stateFlag;

    public MenuItem(String material, int amount, Integer slot, String name,
                    List<String> lore, List<String> commands, String permission) {
        this(material, amount, slot, name, lore, commands, permission, null, null, null, false);
    }

    public MenuItem(String material, int amount, Integer slot, String name,
                    List<String> lore, List<String> commands, String permission,
                    String flag, String group, List<String> states, boolean stateFlag) {
        this.material = material;
        this.amount = amount;
        this.slot = slot;
        this.name = name;
        this.lore = lore;
        this.commands = commands;
        this.permission = permission;
        this.flag = flag;
        this.group = group;
        this.states = states;
        this.stateFlag = stateFlag;
    }

    public Integer slot() {
        return slot;
    }

    public List<String> commands() {
        return commands;
    }

    public String permission() {
        return permission;
    }

    public boolean isDynamic() {
        return flag != null;
    }

    public String flag() {
        return flag;
    }

    public String group() {
        return group;
    }

    public List<String> states() {
        return states;
    }

    public boolean isStateFlag() {
        return stateFlag;
    }

    /** Собрать физический предмет с применением контекста и замен. */
    public ItemStack build(QQRegions plugin, Player player, Map<String, String> ctx) {
        Material m = Material.matchMaterial(material);
        ItemStack item = new ItemStack(m == null ? Material.STONE : m, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Msg.color(process(plugin, player, ctx, name == null ? "" : name)));
            List<Component> lines = new ArrayList<>();
            if (lore != null) {
                for (String l : lore) {
                    if (l == null || l.isEmpty()) {
                        lines.add(Component.empty());
                    } else {
                        lines.add(Msg.color(process(plugin, player, ctx, l)));
                    }
                }
            }
            meta.lore(lines);
            item.setItemMeta(meta);
        }
        return item;
    }

    public String process(QQRegions plugin, Player player, Map<String, String> ctx, String text) {
        String out = ctx == null ? text : text;
        for (Map.Entry<String, String> e : ctx.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        String papi = Papi.set(player, out);
        // замена значений заполнителей WorldGuard/WGEFP через replace.yml
        if (!papi.contains("%")) {
            return papi;
        }
        StringBuilder sb = new StringBuilder();
        Matcher m = PH.matcher(papi);
        int last = 0;
        while (m.find()) {
            sb.append(papi, last, m.start());
            String ph = m.group(1);
            String value = Papi.set(player, m.group(0));
            sb.append(plugin.replace().resolve(ph, value));
            last = m.end();
        }
        sb.append(papi.substring(last));
        return sb.toString();
    }
}