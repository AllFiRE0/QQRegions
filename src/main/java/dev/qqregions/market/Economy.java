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
        boolean found = false;
        try {
            if (plugin.config().market().economyEnabled) {
                Class<?> eco = Class.forName("net.milkbowl.vault.economy.Economy");
                found = true;
                Object provider = provider(eco);
                if (provider != null) {
                    vault = provider;
                    mGetBalance = eco.getMethod("getBalance", OfflinePlayer.class);
                    mHas = eco.getMethod("has", OfflinePlayer.class, double.class);
                    mWithdraw = eco.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
                    mDeposit = eco.getMethod("depositPlayer", OfflinePlayer.class, double.class);
                }
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            plugin.getLogger().warning("Vault/Economy недоступен: "
                    + cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage())
                    + " (Vault-класс найден: " + found + ")");
        }
    }

    /** Достать Vault-провайдер сервиса несколькими способами (устойчивость к форкам API). */
    private Object provider(Class<?> eco) {
        ServicesManager sm = Bukkit.getServicesManager();
        // 1) getRegisteredProvider(Class<T>): стандартный Bukkit API
        try {
            Method m = ServicesManager.class.getMethod("getRegisteredProvider", Class.class);
            Object p = m.invoke(sm, eco);
            if (p != null) {
                return p;
            }
        } catch (Throwable ignore) {
            // пробуем другие способы
        }
        // 2) getRegistration(Class) -> getProvider()
        try {
            Method reg = ServicesManager.class.getMethod("getRegistration", Class.class);
            Object sr = reg.invoke(sm, eco);
            if (sr != null) {
                Method gp = sr.getClass().getMethod("getProvider");
                Object p = gp.invoke(sr);
                if (p != null) {
                    return p;
                }
            }
        } catch (Throwable ignore) {
        }
        // 3) getRegistrations(Class) -> перебор -> первый провайдер
        try {
            Method regs = ServicesManager.class.getMethod("getRegistrations", Class.class);
            Object list = regs.invoke(sm, eco);
            if (list instanceof Iterable) {
                for (Object sr : (Iterable<?>) list) {
                    try {
                        Method gp = sr.getClass().getMethod("getProvider");
                        Object p = gp.invoke(sr);
                        if (p != null && eco.isInstance(p)) {
                            return p;
                        }
                    } catch (ReflectiveOperationException ignore) {
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
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

    /** Символ валюты из настроек config.yml (может быть пустой строкой). */
    public String symbol() {
        Config.MarketOptions m = plugin.config().market();
        return m.symbol == null ? "" : m.symbol;
    }

    /** Отформатировать ТОЛЬКО число (разряды/десятичные), без символа валюты. */
    public String formatAmount(double amount) {
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
        return new DecimalFormat(pat.toString(), sym).format(amount);
    }

    /** Отформатировать сумму: число + символ валюты (число и символ
     *  доступны отдельно через {@link #formatAmount(double)} и {@link #symbol()},
     *  чтобы в переводах можно было составить, например, «1.000 ₽» с пробелом). */
    public String format(double amount) {
        String amountStr = formatAmount(amount);
        String symStr = symbol();
        return plugin.config().market().symbolPosition == Config.MarketOptions.SymbolPosition.BEFORE
                ? symStr + amountStr : amountStr + symStr;
    }
}