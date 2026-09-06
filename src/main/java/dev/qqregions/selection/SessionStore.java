package dev.qqregions.selection;

import dev.qqregions.QQRegions;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Персистентный бэкап инвентаря на время интерактивной сессии.
 *
 * Зачем: сессия забирает инвентарь игрока (чистит и кладёт кнопки в хотбар).
 * Если сервер упадёт/перезагрузится прямо во время сессии, память потеряется,
 * а вместе с ней — «настоящие» предметы (их нигде больше нет). Чтобы потеря
 * вещей была невозможна, снимок сериализуется на диск СРАЗУ при старте сессии
 * (ДО очистки инвентаря) и восстанавливается ИЗ НЕГО при выходе. При входе
 * игрока после краша/рестарта незакрытый снимок возвращается автоматически.
 *
 * Формат хранения: <data>/sessions/<uuid>.yml (YAML + ConfigurationSerializable,
 * умеет сериализовать ItemStack нативно).
 *
 * Гарантии восстановления:
 *   - обычный end() ........................ восстанавливает из снимка и удаляет его
 *   - смерть ............................... снимок НЕ трогаем; вернём при респавне
 *   - выход с сервера (quit/kick/бан) ...... end() восстанавливает из снимка
 *   - падение/перезагрузка сервера ......... на PlayerJoinEvent снимок возвращается
 *   - долгое отсутствие игрока ............. снимок лежит, вернётся при входе
 */
public class SessionStore {

    private final QQRegions plugin;
    private final File dir;

    public SessionStore(QQRegions plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "sessions");
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private File file(String uuid) {
        File f = new File(dir, uuid + ".yml");
        // нормализация: никаких '..' / абсолютных путей из uuid
        if (!f.getAbsolutePath().startsWith(dir.getAbsolutePath())) {
            return new File(dir, "default.yml");
        }
        return f;
    }

    /** Снимает инвентарь игрока на диск ДО того, как сессия его очистит.
     *  Возвращает false, если записать снимок не удалось — старт сессии надо отменить. */
    public boolean save(String uuid, PlayerInventory inv) {
        try {
            YamlConfiguration yml = new YamlConfiguration();
            yml.set("saved-at", System.currentTimeMillis());
            yml.set("contents", Arrays.asList(inv.getContents()));
            yml.set("armor", Arrays.asList(inv.getArmorContents()));
            yml.set("offhand", inv.getItemInOffHand());
            yml.set("held-slot", inv.getHeldItemSlot());
            yml.save(file(uuid));
            return true;
        } catch (Exception e) {
            plugin.dbg("session store save failed for " + uuid + ": " + e);
            return false;
        }
    }

    /** Восстанавливает инвентарь из снимка. Возвращает false, если снимка не было. */
    public boolean restore(String uuid, Player player) {
        File f = file(uuid);
        if (!f.exists()) {
            return false;
        }
        try {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
            List<?> contentsRaw = yml.getList("contents", List.of());
            List<?> armorRaw = yml.getList("armor", List.of());
            ItemStack[] contents = toStackArray(contentsRaw);
            ItemStack[] armor = toStackArray(armorRaw);
            ItemStack offhand = yml.getItemStack("offhand");
            int held = yml.getInt("held-slot", 0);

            PlayerInventory inv = player.getInventory();
            inv.clear();
            inv.setArmorContents(armor);
            inv.setItemInOffHand(offhand);
            ItemStack[] padded = new ItemStack[36];
            System.arraycopy(contents, 0, padded, 0, Math.min(contents.length, 36));
            inv.setContents(padded);
            inv.setHeldItemSlot(Math.max(0, Math.min(8, held)));
            delete(uuid);
            return true;
        } catch (Exception e) {
            plugin.dbg("session store restore failed for " + uuid + ": " + e);
            // снимок не удаляем — дадим повторить восстановление позже
            return false;
        }
    }

    private static ItemStack[] toStackArray(List<?> raw) {
        ItemStack[] out = new ItemStack[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            out[i] = (ItemStack) raw.get(i);
        }
        return out;
    }

    /** Есть ли незакрытый снимок (краш/рестарт/долгое отсутствие). */
    public boolean has(String uuid) {
        return file(uuid).exists();
    }

    /** Удаляет снимок (после успешного восстановления или нормального выхода). */
    public void delete(String uuid) {
        File f = file(uuid);
        if (f.exists()) {
            f.delete();
        }
    }
}