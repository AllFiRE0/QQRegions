package dev.qqregions;

import dev.qqregions.commands.CommandManager;
import dev.qqregions.config.Config;
import dev.qqregions.config.Lang;
import dev.qqregions.config.ReplaceManager;
import dev.qqregions.gui.MenuManager;
import dev.qqregions.highlight.HighlightManager;
import dev.qqregions.market.MarketManager;
import dev.qqregions.papi.QQExpansion;
import dev.qqregions.raid.RaidManager;
import dev.qqregions.selection.InteractListener;
import dev.qqregions.selection.SelectionManager;
import dev.qqregions.selection.SessionStore;
import dev.qqregions.util.Papi;
import dev.qqregions.wg.Wg;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.stream.Collectors;

/**
 * QQRegions — мощное управление регионами поверх WorldGuard.
 * Автор: AllF1RE
 */
public final class QQRegions extends JavaPlugin {

    private static QQRegions instance;

    private Config config;
    private Lang lang;
    private ReplaceManager replace;
    private Wg wg;
    private SelectionManager selections;
    private SessionStore store;
    private MenuManager menus;
    private CommandManager commands;
    private InteractListener interactListener;
    private HighlightManager highlight;
    private MarketManager market;
    private RaidManager raid;

    public static QQRegions get() {
        return instance;
    }

    @Override
    public void onLoad() {
        instance = this;
        // Кастомные флаги WG обязаны регистрироваться ДО активации WorldGuard
        // (после неё FlagRegistry блокируется).
        this.wg = new Wg(this);
        this.wg.registerFlags();
    }

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        this.config = new Config(this);
        this.lang = new Lang(this);
        this.replace = new ReplaceManager(this);
        this.selections = new SelectionManager(this);
        this.store = new SessionStore(this);
        this.menus = new MenuManager(this);
        this.commands = new CommandManager(this);
        this.commands.register();

        this.interactListener = new InteractListener(this);
        this.highlight = new HighlightManager(this);
        this.market = new MarketManager(this);
        this.raid = new RaidManager(this);
        Bukkit.getPluginManager().registerEvents(interactListener, this);
        Bukkit.getPluginManager().registerEvents(selections, this);
        Bukkit.getPluginManager().registerEvents(menus, this);
        Bukkit.getPluginManager().registerEvents(highlight, this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            Papi.setEnabled(true);
            new QQExpansion(this).register();
            getLogger().info("PlaceholderAPI подключён: доступны %qqregions_*% заполнители.");
        } else {
            getLogger().info("PlaceholderAPI не найден: внешние заполнители недоступны.");
        }

        Bukkit.getScheduler().runTaskTimer(this, this::onTick, 20L, 5L);

        getLogger().info("QQRegions v" + getDescription().getVersion() + " включён. Команда: /"
                + config.commandName() + " (алиасы: "
                + config.aliases().stream().map(a -> "/" + a).collect(Collectors.joining(", ")) + ")");
    }

    private void onTick() {
        selections.tick();
        menus.tick();
        highlight.tick();
        raid.tick();
        marketTick();
    }

    private int marketTickTimer = 0;

    private void marketTick() {
        marketTickTimer += 5;
        if (marketTickTimer < 20 * 60) {
            return;
        }
        marketTickTimer = 0;
        market.tick();
    }

    @Override
    public void onDisable() {
        if (selections != null) {
            selections.endAll();
        }
        if (menus != null) {
            menus.closeAll();
        }
        if (highlight != null) {
            highlight.clearAll();
        }
        if (market != null) {
            market.save();
        }
        if (commands != null) {
            commands.unregister();
        }
        instance = null;
        getLogger().info("QQRegions выключен.");
    }

    public Config config() {
        return config;
    }

    public Lang lang() {
        return lang;
    }

    public ReplaceManager replace() {
        return replace;
    }

    public Wg wg() {
        return wg;
    }

    public SelectionManager selections() {
        return selections;
    }

    public SessionStore store() {
        return store;
    }

    public MenuManager menus() {
        return menus;
    }

    public CommandManager commands() {
        return commands;
    }

    public HighlightManager highlight() {
        return highlight;
    }

    public MarketManager market() {
        return market;
    }

    public RaidManager raid() {
        return raid;
    }

    /** Подробный лог в консоль, если в config.yml включён debug: true. */
    public void dbg(String msg) {
        if (config != null && config.debug()) {
            getLogger().info("[DEBUG] " + msg);
        }
    }
}