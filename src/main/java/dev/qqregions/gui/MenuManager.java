package dev.qqregions.gui;

import com.sk89q.worldguard.protection.ProtectedRegion;
import dev.qqregions.QQRegions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Менеджер GUI-меню. Загружает файлы из папки menus/, хранит открытые
 * меню и обновляет их по update_interval, исполняет команды кнопок.
 */
public class MenuManager implements Listener {

    /** файл (без .yml) -> упорядоченные по приоритету шаблоны */
    private final Map<String, List<Menu>> menus = new HashMap<>();
    private final Map<UUID, OpenMenu> open = new HashMap<>();
    private final QQRegions plugin;

    public MenuManager(QQRegions plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        menus.clear();
        File dir = new File(plugin.getDataFolder(), "menus");
        if (!dir.exists()) {
            dir.mkdirs();
            plugin.saveResource("menus/flags.yml", false);
        }
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                List<Menu> parsed = Menu.parseFile(plugin, f, 20);
                parsed.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
                menus.put(f.getName().replaceFirst("\\.yml$", ""), parsed);
            }
        }
    }

    /** Открыть лучшее подходящее меню flags для владельца региона. */
    public boolean openFlags(Player player, ProtectedRegion region) {
        String world = region.getWorld().getName();
        Map<String, String> ctx = new HashMap<>();
        ctx.put("region", region.getId());
        ctx.put("world", world);
        ctx.put("player", player.getName());
        return open(player, "flags", ctx);
    }

    public boolean open(Player player, String menuName, Map<String, String> ctx) {
        return open(player, menuName, ctx, 0);
    }

    public boolean open(Player player, String menuName, Map<String, String> ctx, int page) {
        List<Menu> candidates = menus.get(menuName.toLowerCase(java.util.Locale.ROOT));
        if (candidates == null) {
            return false;
        }
        Menu best = null;
        for (Menu m : candidates) {
            if (m.matches(player)) {
                best = m;
                break;
            }
        }
        if (best == null) {
            return false;
        }
        return render(player, best, ctx, page);
    }

    /** Перерендерить инвентарь меню. */
    private boolean render(Player player, Menu menu, Map<String, String> ctx, int page) {
        List<MenuItem> dynItems = menu.dynamicItems(plugin, player, ctx);
        int maxPages = menu.maxPages(dynItems.size());
        int safePage = Math.max(0, Math.min(maxPages - 1, page));
        Map<Integer, MenuItem> slotMap = new HashMap<>();
        Inventory inv = menu.build(plugin, player, ctx, safePage, maxPages, dynItems, slotMap);
        player.openInventory(inv);
        open.put(player.getUniqueId(), new OpenMenu(player, inv, menu, ctx, safePage, maxPages, slotMap));
        return true;
    }

    public void tick() {
        if (open.isEmpty()) {
            return;
        }
        for (OpenMenu om : List.copyOf(open.values())) {
            om.ticks += 5;
            if (om.ticks >= om.menu.updateInterval()) {
                om.ticks = 0;
                if (!om.player.isOnline()) {
                    continue;
                }
                render(om.player, om.menu, om.ctx, om.page);
            }
        }
    }

    public void closeAll() {
        for (OpenMenu om : open.values()) {
            if (om.player.isOnline()) {
                om.player.closeInventory();
            }
        }
        open.clear();
    }

    // ---------- события ----------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        OpenMenu om = open.get(p.getUniqueId());
        if (om == null || om.inv != e.getInventory()) {
            return;
        }
        e.setCancelled(true);
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= om.inv.getSize()) {
            return;
        }
        MenuItem item = om.slotMap.get(slot);
        if (item == null) {
            return;
        }
        if (item.permission() != null && !item.permission().isEmpty()
                && !p.hasPermission("qqregions.admin") && !p.hasPermission(item.permission())) {
            return;
        }
        List<String> cmds = item.commands();
        if (cmds == null) {
            return;
        }

        // динамическая кнопка флага: подставляем следующий статус
        if (item.isDynamic()) {
            cmds = new ArrayList<>(cmds);
            for (int i = 0; i < cmds.size(); i++) {
                cmds.set(i, cmds.get(i).replace("{next-state}", nextState(item, currentValue(om, item))));
            }
        }

        String context = applyContext(om.ctx, cmds);
        for (String c : context.split("\\n")) {
            c = c.trim();
            if (c.isEmpty()) {
                continue;
            }
            if (c.startsWith("@page:")) {
                String arg = c.substring("@page:".length()).trim();
                if (arg.equalsIgnoreCase("prev") && om.page > 0) {
                    render(p, om.menu, om.ctx, om.page - 1);
                } else if (arg.equalsIgnoreCase("next") && om.page < om.maxPages - 1) {
                    render(p, om.menu, om.ctx, om.page + 1);
                }
                continue;
            }
            MenuAction.run(p, dev.qqregions.util.Papi.set(p, c));
        }
    }

    /** Текущее значение флага (через заполнитель WorldGuard) для группы кнопки. */
    private String currentValue(OpenMenu om, MenuItem item) {
        String id = item.flag();
        String group = item.group();
        String ph = (group == null || group.equalsIgnoreCase("all"))
                ? "%worldguard_region_has_flag_" + id + "%"
                : "%worldguard_region_has_flag_" + id + ":" + group + "%";
        String value = dev.qqregions.util.Papi.set(om.player, ph);
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Следующее значение в цикле states (или allow&lt;-&gt;deny для StateFlag). */
    private String nextState(MenuItem item, String current) {
        List<String> states = item.states();
        if (states == null || states.isEmpty()) {
            return "";
        }
        int idx = -1;
        for (int i = 0; i < states.size(); i++) {
            if (states.get(i).equalsIgnoreCase(current)) {
                idx = i;
                break;
            }
        }
        return idx < 0 ? states.get(0) : states.get((idx + 1) % states.size());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        open.remove(e.getPlayer().getUniqueId());
    }

    private String applyContext(Map<String, String> ctx, List<String> cmds) {
        StringBuilder sb = new StringBuilder();
        for (String c : cmds) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String line = c;
            for (Map.Entry<String, String> en : ctx.entrySet()) {
                line = line.replace("{" + en.getKey() + "}", en.getValue() == null ? "" : en.getValue());
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private static class OpenMenu {
        final Player player;
        final Inventory inv;
        final Menu menu;
        final Map<String, String> ctx;
        final Map<Integer, MenuItem> slotMap;
        final int page;
        final int maxPages;
        int ticks;

        OpenMenu(Player player, Inventory inv, Menu menu, Map<String, String> ctx,
                 int page, int maxPages, Map<Integer, MenuItem> slotMap) {
            this.player = player;
            this.inv = inv;
            this.menu = menu;
            this.ctx = ctx;
            this.page = page;
            this.maxPages = maxPages;
            this.slotMap = slotMap;
        }
    }
}