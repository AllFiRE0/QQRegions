package dev.qqregions.commands;

import dev.qqregions.QQRegions;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Регистрация команды и её алиасов. Алиасы берутся из config.yml и могут
 * меняться во время работы /region reload.
 */
public class CommandManager {

    private final QQRegions plugin;
    private final List<Command> registered = new ArrayList<>();
    private final List<String> names = new ArrayList<>();

    public CommandManager(QQRegions plugin) {
        this.plugin = plugin;
    }

    public void register() {
        unregister();
        RegionCommand executor = new RegionCommand(plugin);

        Set<String> all = new LinkedHashSet<>();
        all.add(plugin.config().commandName());
        all.addAll(plugin.config().aliases());

        CommandMap map = map();
        Map<String, Command> known = map.getKnownCommands();
        for (String name : all) {
            String lower = name.toLowerCase(Locale.ROOT);
            QQCommand cmd = new QQCommand(lower, executor);
            map.register(lower, "qqregions", cmd);
            known.putIfAbsent(lower, cmd);
            registered.add(cmd);
            names.add(lower);
        }
    }

    public void unregister() {
        if (registered.isEmpty()) {
            return;
        }
        CommandMap map = map();
        java.util.Map<String, Command> known = map.getKnownCommands();
        for (String name : names) {
            Command cmd = known.get(name);
            if (cmd != null && registered.contains(cmd)) {
                known.remove(name);
            }
        }
        for (Command c : registered) {
            c.unregister(map);
        }
        registered.clear();
        names.clear();
    }

    private CommandMap map() {
        try {
            return Bukkit.getCommandMap();
        } catch (Throwable t) {
            try {
                Method m = Bukkit.getServer().getClass().getMethod("getCommandMap");
                return (CommandMap) m.invoke(Bukkit.getServer());
            } catch (Throwable t2) {
                throw new IllegalStateException("Командная карта недоступна", t2);
            }
        }
    }
}