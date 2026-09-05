package dev.qqregions.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Исполнение команд кнопок меню.
 *   asConsole! <cmd> — от консоли
 *   asPlayer! <cmd>  — от имени игрока
 *   close            — закрыть меню
 *   иначе            — команда от имени игрока
 */
public final class MenuAction {

    private MenuAction() {
    }

    public static void run(Player p, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String cmd = raw.trim();
        if (cmd.toLowerCase().startsWith("asconsole!")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.substring("asConsole!".length()).trim());
        } else if (cmd.toLowerCase().startsWith("asplayer!")) {
            p.performCommand(cmd.substring("asPlayer!".length()).trim());
        } else if (cmd.equalsIgnoreCase("close")) {
            p.closeInventory();
        } else {
            p.performCommand(cmd);
        }
    }
}