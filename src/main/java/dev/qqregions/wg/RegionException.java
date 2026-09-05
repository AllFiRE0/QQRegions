package dev.qqregions.wg;

/**
 * Ошибка операций с регионами. Имеет ключ локализации и заполнители.
 */
public class RegionException extends Exception {

    private final String key;
    private final String[] kv;

    public RegionException(String key, String... kv) {
        super(key);
        this.key = key;
        this.kv = kv;
    }

    public String getKey() {
        return key;
    }

    public String[] getKv() {
        return kv;
    }
}