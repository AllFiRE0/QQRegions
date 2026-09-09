package dev.qqregions.gui;

import dev.qqregions.QQRegions;
import dev.qqregions.util.Actions;
import dev.qqregions.util.Papi;
import org.bukkit.entity.Player;

/**
 * Исполнение команд кнопок меню.
 *   asConsole! <cmd> — от консоли
 *   asPlayer! <cmd>  — от имени игрока
 *   message!/title!/actionbar!/sound!/delay! и др. — см. Actions
 *   close            — закрыть меню
 *   иначе            — команда от имени игрока
 */
public final class MenuAction {

    private MenuAction() {
    }

    public static void run(QQRegions plugin, Player p, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String cmd = Papi.set(p, raw.trim());
        if (Actions.isAction(cmd)) {
            Actions.run(plugin, p, cmd);
            return;
        }
        if (cmd.equalsIgnoreCase("close")) {
            p.closeInventory();
            return;
        }
        p.performCommand(cmd);
    }
}