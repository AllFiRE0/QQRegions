package dev.qqregions.market;

import dev.qqregions.QQRegions;
import dev.qqregions.config.Config;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicesManager;

import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.UUID;

/**
 * Обёртка над Vault (мягкая зависимость, подключается РЕФЛЕКСИЕЙ — в pom.xml
 * VaultAPI нет, чтобы не зависеть от нестабильных репозиториев). Если
 * Vault/экономики нет — рынок недоступен и команды отвечают «экономика
 * выключена».
 *
 * Формат денег настраивается в config.yml (market.economy.*):
 * знак валюты, позиция, десятичные знаки, группировка разрядов.
 * Примеры: 1,000 ₽  1.000₽  1000  1000.00 ₽  1.000,00 ₽
 */
public final class Economy {

    private final QQRegions plugin;

    private Object vault;            // net.milkbowl.vault.economy.Economy
    private Method mGetBalance;
    private Method mHas;
    private Method mWithdraw;
    private Method mDeposit;

    Economy(QQRegions plugin) {
        this.plugin = plugin;
        reload();
    }

    @SuppressWarnings("unchecked")
    void reload() {
        vault = null;
        mGetBalance = mHas = mWithdraw = mDeposit = null;
        try {
            if (Bukkit.getPluginManager().getPlugin("Vault") != null
                    && plugin.config().market().economyEnabled) {
                Class<?> eco = Class.forName("net.milkbowl.vault.economy.Economy");
                ServicesManager sm = Bukkit.getServicesManager();
                Method reg = ServicesManager.class.getMethod("getRegisteredProvider", Class.class);
                Object provider = reg.invoke(sm, eco);
                if (provider != null) {
                    vault = provider;
                    mGetBalance = eco.getMethod("getBalance", OfflinePlayer.class);
                    mHas = eco.getMethod("has", OfflinePlayer.class, double.class);
                    mWithdraw = eco.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
                    mDeposit = eco.getMethod("depositPlayer", OfflinePlayer.class, double.class);
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
        return invokeDouble(mGetBalance, id);
    }

    public boolean has(UUID id, double amount) {
        return invokeBool(mHas, id, amount);
    }

    public boolean withdraw(UUID id, double amount) {
        return invokeResponse(mWithdraw, id, amount);
    }

    public boolean deposit(UUID id, double amount) {
        return invokeResponse(mDeposit, id, amount);
    }

    private double invokeDouble(Method m, UUID id) {
        try {
            return vault != null && m != null
                    ? (Double) m.invoke(vault, Bukkit.getOfflinePlayer(id)) : 0;
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    private boolean invokeBool(Method m, UUID id, double amount) {
        try {
            return vault != null && m != null
                    && (Boolean) m.invoke(vault, Bukkit.getOfflinePlayer(id), amount);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** Vault возвращает EconomyResponse; success = response.transactionSuccess(). */
    private boolean invokeResponse(Method m, UUID id, double amount) {
        try {
            if (vault == null || m == null) {
                return false;
            }
            Object resp = m.invoke(vault, Bukkit.getOfflinePlayer(id), amount);
            if (resp == null) {
                return false;
            }
            Method success = resp.getClass().getMethod("transactionSuccess");
            return Boolean.TRUE.equals(success.invoke(resp));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** Отформатировать сумму по правилам market.economy.* (знак + символ валюты). */
    public String format(double amount) {
        Config.MarketOptions m = plugin.config().market();
        String gs = m.groupSeparator == null || m.groupSeparator.isEmpty() ? " " : m.groupSeparator;
        String ds = m.decimalSeparator == null || m.decimalSeparator.isEmpty() ? "." : m.decimalSeparator;
        if (gs.equals(".")) {
            gs = " ";
        }
        if (gs.equals(ds)) {
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