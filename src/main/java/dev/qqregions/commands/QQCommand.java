package dev.qqregions.commands;

import org.bukkit.command.Command;

/**
 * Базовый объект команды (регистрируется на каждое имя/алиас).
 */
public class QQCommand extends Command {

    private final RegionCommand executor;

    public QQCommand(String name, RegionCommand executor) {
        super(name, "QQRegions command", "/" + name + " help", java.util.List.of());
        this.executor = executor;
    }

    @Override
    public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
        return executor.execute(sender, commandLabel, args);
    }

    @Override
    public java.util.List<String> tabComplete(org.bukkit.command.CommandSender sender, String alias, String[] args) {
        return executor.tabComplete(sender, alias, args);
    }

    @Override
    public java.util.List<String> tabComplete(org.bukkit.command.CommandSender sender, String alias, String[] args,
                                              org.bukkit.Location location) {
        return tabComplete(sender, alias, args);
    }
}