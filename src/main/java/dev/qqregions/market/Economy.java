package dev.qqregions.market;

import dev.qqregions.QQRegions;
import dev.qqregions.config.Config;
import org.bukkit.Bukkit;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.UUID;

/**
 * Обёртка над Vault (мягкая зависимость). Если Vault/Economy нет — рынок
 * недоступен и команды рыночных операций отвечают «экономика выключена».
 *
 * Формат денег настраивается в config.yml (market.economy.*):
 * знак валюты, позиция, десятичные знаки, группировка разрядов.
 * Примеры: 1,000 ₽  1.000₽  1000  1000.00 ₽  1.000,00 ₽
 */
public final class Economy {

    private final QQRegions plugin;
    private net.milkbowl.vault.economy.Economy vault;

    Economy(QQRegions plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        vault = null;
        try {
            if (Bukkit.getPluginManager().getPlugin("Vault") != null
                    && plugin.config().market().economyEnabled) {
                var reg = Bukkit.getServicesManager()
                        .getRegistration(net.milkbowl.vault.economy.Economy.class);
                if (reg != null) {
                    vault = reg.getProvider();
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Vault/Economy недоступен: " + t.getMessage());
        }
    }

    public boolean enabled() {
        return vault != null;
    }

    public double balance(UUID id) {
        if (vault == null) {
            return 0;
        }
        return vault.getBalance(Bukkit.getOfflinePlayer(id));
    }

    public boolean has(UUID id, double amount) {
        return vault != null && vault.has(Bukkit.getOfflinePlayer(id), amount);
    }

    public boolean withdraw(UUID id, double amount) {
        return vault != null
                && vault.withdrawPlayer(Bukkit.getOfflinePlayer(id), amount).transactionSuccess();
    }

    public boolean deposit(UUID id, double amount) {
        return vault != null
                && vault.depositPlayer(Bukkit.getOfflinePlayer(id), amount).transactionSuccess();
    }

    /** Отформатировать сумму по правилам market.economy.* (знак + символ валюты). */
    public String format(double amount) {
        Config.MarketOptions m = plugin.config().market();
        String gs = m.groupSeparator == null || m.groupSeparator.isEmpty() ? " " : m.groupSeparator;
        String ds = m.decimalSeparator == null || m.decimalSeparator.isEmpty() ? "." : m.decimalSeparator;
        if (gs.equals(".")) {
            gs = " ";
        }
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.ROOT);
        sym.setGroupingSeparator(gs.charAt(0));
        sym.setDecimalSeparator(ds.charAt(0));
        StringBuilder pat = new StringBuilder();
        if (m.grouping) {
            pat.append("#,##0");
        } else {
            pat.append("#0");
        }
        if (m.decimalPlaces > 0) {
            pat.append('.').append("0".repeat(m.decimalPlaces));
        }
        DecimalFormat df = new DecimalFormat(pat.toString(), sym);
        String num = df.format(amount);
        String symStr = m.symbol == null ? "" : m.symbol;
        return m.symbolPosition == Config.MarketOptions.SymbolPosition.BEFORE
                ? symStr + num : num + symStr;
    }
}