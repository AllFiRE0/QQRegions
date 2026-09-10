package dev.qqregions.gui;

import dev.qqregions.QQRegions;
import dev.qqregions.util.Msg;
import dev.qqregions.util.Papi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Кнопка меню. name/lore/commands поддерживают {переменные} контекста,
 * %заполнители% PlaceholderAPI и подмену значений через replace.yml.
 */
public class MenuItem {

    private static final Pattern PH = Pattern.compile("%([^%]+)%");

    private final String material;
    private final int amount;
    private final Integer slot;
    private final String name;
    private final List<String> lore;
    private final List<String> commands;
    private final String permission;
    /** Требуемая для ПОКАЗА кнопки роль (owner/member/other); "" = любая.
     *  Админ (qqregions.admin) и операторы видят кнопку при любой роли. */
    private final String roleRequired;
    /** tooltip: false — убрать всплывающее окно у КОНКРЕТНОЙ кнопки
     *  (например у фона из стекла). По умолчанию тултип показывается. */
    private boolean tooltip = true;
    /** UUID игрока для PLAYER_HEAD: при сборке кнопки подставляется голова
     *  скина этого игрока (через setOwningPlayer). */
    private String ownerUuid;
    /** Base64-текстура кастомного скина для PLAYER_HEAD (одинаковая для всех;
     *  приоритетнее ownerUuid). */
    private String skinTexture;

    /** имя флага для динамических кнопок, null для статичных */
    private final String flag;
    /** группа флага: all / members / owners / ... (null = не динамическая) */
    private final String group;
    /** список значений для цикла (например [allow, deny]) */
    private final List<String> states;
    /** флаг является StateFlag (переключение allow<->deny по умолчанию) */
    private final boolean stateFlag;

    public MenuItem(String material, int amount, Integer slot, String name,
                    List<String> lore, List<String> commands, String permission) {
        this(material, amount, slot, name, lore, commands, permission, null, null, null, false, null);
    }

    public MenuItem(String material, int amount, Integer slot, String name,
                    List<String> lore, List<String> commands, String permission,
                    String flag, String group, List<String> states, boolean stateFlag) {
        this(material, amount, slot, name, lore, commands, permission, flag, group, states, stateFlag, null);
    }

    public MenuItem(String material, int amount, Integer slot, String name,
                    List<String> lore, List<String> commands, String permission,
                    String flag, String group, List<String> states, boolean stateFlag,
                    String roleRequired) {
        this.material = material;
        this.amount = amount;
        this.slot = slot;
        this.name = name;
        this.lore = lore;
        this.commands = commands;
        this.permission = permission;
        this.roleRequired = roleRequired;
        this.flag = flag;
        this.group = group;
        this.states = states;
        this.stateFlag = stateFlag;
    }

    public Integer slot() {
        return slot;
    }

    public List<String> commands() {
        return commands;
    }

    public String permission() {
        return permission;
    }

    public String roleRequired() {
        return roleRequired;
    }

    /** true, если кнопку можно ПОКАЗАТЬ игроку: роль совпадает или админ/оператор. */
    public boolean visible(String role, boolean isAdmin) {
        if (roleRequired == null || roleRequired.trim().isEmpty()) {
            return true;
        }
        if (isAdmin) {
            return true;
        }
        return roleRequired.trim().equalsIgnoreCase(role == null ? "" : role);
    }

    public boolean isDynamic() {
        return flag != null;
    }

    public String flag() {
        return flag;
    }

    public String group() {
        return group;
    }

    public List<String> states() {
        return states;
    }

    public boolean isStateFlag() {
        return stateFlag;
    }

    /** tooltip: false — скрыть тултип у этой кнопки (setHideTooltip). */
    public MenuItem tooltip(boolean tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    public boolean tooltip() {
        return tooltip;
    }

    /** Поставить голову игрока (для PLAYER_HEAD): скин по UUID игрока. */
    public MenuItem ownerUuid(String ownerUuid) {
        this.ownerUuid = ownerUuid;
        return this;
    }

    /** Поставить ОДИНАКОВУЮ кастомную текстуру скина головы для всех кнопок
     *  (для PLAYER_HEAD; приоритетнее ownerUuid). */
    public MenuItem skinTexture(String base64) {
        this.skinTexture = base64;
        return this;
    }

    /** Собрать физический предмет с применением контекста и замен.
     *
     *  material поддерживает синтаксис DeluxeMenus:
     *  - обычное имя (STONE, water_bottle, air);
     *  - головы: head-<ник>, basehead-<base64 Value field>, texture-<id от
     *    textures.minecraft.net>, hdb-<ID HeadDatabase>, PLAYER_HEAD:<uuid>,
     *    PLAYER_HEAD (uuid через ownerUuid для динамических кнопок);
     *  - placeholder-<заполнитель> (префикс-форма, {..}/%..% и так обрабатываются);
     *  - живой предмет игрока: main_hand, off_hand, armor_helmet/chestplate/
     *    leggings/boots (показывается как есть, без имени/лора кнопки). */
    public ItemStack build(QQRegions plugin, Player player, Map<String, String> ctx) {
        String raw = material == null ? "" : process(plugin, player, ctx, material).trim();
        String key = raw.toLowerCase(Locale.ROOT);

        // «живой» предмет руки/слота брони игрока — как в DeluxeMenus
        if (key.equals("main_hand") || key.equals("off_hand") || key.startsWith("armor_")) {
            return liveItem(player, key);
        }
        if (key.startsWith("placeholder-")) {
            raw = raw.substring("placeholder-".length()).trim();
            key = raw.toLowerCase(Locale.ROOT);
        }

        // головы (синтаксис DeluxeMenus)
        if (key.startsWith("head-")) {
            return buildHead(plugin, player, ctx, uuidForName(raw.substring(5).trim()), null);
        }
        if (key.startsWith("basehead-")) {
            return buildHead(plugin, player, ctx, null, raw.substring(9).trim());
        }
        if (key.startsWith("texture-")) {
            return buildHead(plugin, player, ctx, null, textureIdToBase64(raw.substring(8).trim()));
        }
        if (key.startsWith("hdb-")) {
            return buildHead(plugin, player, ctx, null, hdbBase64(plugin, raw.substring(4).trim()));
        }

        // собственная форма "PLAYER_HEAD:<uuid>" и обычный PLAYER_HEAD:
        // UUID берётся из строки либо из ownerUuid (динамические кнопки).
        String ownerUu = ownerUuid;
        if (key.startsWith("player_head:")) {
            ownerUu = raw.substring("player_head:".length()).trim();
            raw = "PLAYER_HEAD";
            key = "player_head";
        }
        if (key.equals("player_head")) {
            UUID uuid = null;
            if (ownerUu != null && !ownerUu.isEmpty()) {
                try {
                    uuid = UUID.fromString(ownerUu);
                } catch (Throwable ignored) {
                    // невалидный UUID — без скина
                }
            }
            return buildHead(plugin, player, ctx, uuid,
                    skinTexture == null || skinTexture.isEmpty() ? null : skinTexture);
        }

        Material m = Material.matchMaterial(raw);
        ItemStack item = new ItemStack(m == null ? Material.STONE : m, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            decorate(item, meta, plugin, player, ctx);
        }
        return item;
    }

    /** Одинаковая сборка любой кнопки-головы: скин по UUID (резолвер/кэш),
     *  поверх — кастомная Base64-текстура (при заданной), затем имя/лор. */
    private ItemStack buildHead(QQRegions plugin, Player player, Map<String, String> ctx,
                                UUID uuid, String base64) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (uuid != null && meta instanceof SkullMeta sm) {
                try {
                    plugin.skulls().applyHead(sm, uuid);
                } catch (Throwable ignored) {
                    // невалидный UUID — без скина
                }
            }
            if (base64 != null && !base64.isEmpty() && meta instanceof SkullMeta sm) {
                try {
                    applyHeadTexture(sm, base64);
                } catch (Throwable ignored) {
                    // не получилось — голова без скина
                }
            }
            decorate(item, meta, plugin, player, ctx);
        }
        return item;
    }

    /** Имя/лор/флаги кнопки поверх уже готового meta (сам скин головы
     *  подставляется раньше — в buildHead, см. SkullResolver). */
    private void decorate(ItemStack item, ItemMeta meta, QQRegions plugin,
                          Player player, Map<String, String> ctx) {
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_DYE,
                ItemFlag.HIDE_ARMOR_TRIM, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        if (!tooltip) {
            try {
                meta.setHideTooltip(true);
            } catch (Throwable ignored) {
                // старые версии API без setHideTooltip — просто показываем тултип
            }
        }
        meta.displayName(noItalic(Msg.color(process(plugin, player, ctx, name == null ? "" : name))));
        List<Component> lines = new ArrayList<>();
        if (lore != null) {
            for (String l : lore) {
                if (l == null || l.isEmpty()) {
                    lines.add(Component.empty());
                    continue;
                }
                // после Papi/replace в строке могут остаться \n (например
                // {groups-list}); каждую физическую строку красим отдельно
                String processed = process(plugin, player, ctx, l);
                for (String seg : processed.split("\n", -1)) {
                    if (seg.isEmpty()) {
                        lines.add(Component.empty());
                    } else {
                        lines.add(noItalic(Msg.color(seg)));
                    }
                }
            }
        }
        meta.lore(lines);
        item.setItemMeta(meta);
    }

    /** Предмет, который сейчас у игрока в указанном слоте (main_hand/off_hand/
     *  armor_*); пусто — air. Показывается как есть, без имени/лора кнопки. */
    private static ItemStack liveItem(Player player, String which) {
        ItemStack it;
        switch (which) {
            case "main_hand":
                it = player.getInventory().getItemInMainHand();
                break;
            case "off_hand":
                it = player.getInventory().getItemInOffHand();
                break;
            case "armor_helmet":
                it = player.getInventory().getHelmet();
                break;
            case "armor_chestplate":
                it = player.getInventory().getChestplate();
                break;
            case "armor_leggings":
                it = player.getInventory().getLeggings();
                break;
            case "armor_boots":
                it = player.getInventory().getBoots();
                break;
            default:
                it = null;
                break;
        }
        return it == null || it.getType() == Material.AIR
                ? new ItemStack(Material.AIR) : it.clone();
    }

    /** head-<ник>: UUID игрока по нику (или уже готовый UUID). */
    private static UUID uuidForName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(name);
        } catch (Throwable ignored) {
            // не UUID — ищем по нику
        }
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            return op == null ? null : op.getUniqueId();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** texture-<id>: из id после textures.minecraft.net/texture/ собрать
     *  Base64-текстуру текстуры скина (формат Value field). */
    private static String textureIdToBase64(String id) {
        String url = "https://textures.minecraft.net/texture/" + id;
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return java.util.Base64.getEncoder()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** hdb-<id>: Base64-текстура головы из HeadDatabase (мягкая зависимость). */
    private static String hdbBase64(QQRegions plugin, String id) {
        try {
            org.bukkit.plugin.Plugin hdb = Bukkit.getPluginManager().getPlugin("HeadDatabase");
            if (hdb == null) {
                return null;
            }
            Class<?> apiClass = Class.forName("me.arcaniax.hdb.api.HeadDatabaseAPI");
            Object api = apiClass.getConstructor().newInstance();
            Object base64 = apiClass.getMethod("getBase64", String.class).invoke(api, id);
            return base64 == null ? null : String.valueOf(base64);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Убрать курсив у кнопки: имя/лор игровых предметов не должна наклоняться. */
    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Поставить кастомный скин головы по Base64-текстуре через рефлексию
     *  (GameProfile + Property "textures") — без привязки к версии сервера. */
    public static void applyHeadTexture(SkullMeta meta, String base64) throws Throwable {
        String ver = null;
        try {
            ver = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        } catch (Throwable ignored) {
            // новый формат имён пакетов OBC — CraftMetaSkull ищем без суффикса версии
        }
        Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
        Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
        Object property = propertyClass.getConstructor(String.class, String.class, String.class)
                .newInstance("textures", base64, null);
        Object gameProfile = gameProfileClass.getConstructor(UUID.class, String.class)
                .newInstance(UUID.nameUUIDFromBytes(base64.getBytes(StandardCharsets.UTF_8)), "");
        Object props = gameProfileClass.getMethod("getProperties").invoke(gameProfile);
        props.getClass().getMethod("put", Object.class, Object.class).invoke(props, "textures", property);
        String craftName = ver == null
                ? "org.bukkit.craftbukkit.inventory.CraftMetaSkull"
                : "org.bukkit.craftbukkit." + ver + ".inventory.CraftMetaSkull";
        java.lang.reflect.Method setProfile = Class.forName(craftName)
                .getDeclaredMethod("setProfile", gameProfileClass);
        setProfile.setAccessible(true);
        setProfile.invoke(meta, gameProfile);
    }

    /** Process: {заполнители} контекста + %PlaceholderAPI% + replace.yml.
     * player может быть любом OfflinePlayer (для шаблонов списка игроков). */
    public String process(QQRegions plugin, OfflinePlayer player, Map<String, String> ctx, String text) {
        String out = ctx == null ? text : text;
        for (Map.Entry<String, String> e : ctx.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        String papi = Papi.set(player, out);
        // замена значений заполнителей WorldGuard/WGEFP через replace.yml
        if (!papi.contains("%")) {
            return papi;
        }
        StringBuilder sb = new StringBuilder();
        Matcher m = PH.matcher(papi);
        int last = 0;
        while (m.find()) {
            sb.append(papi, last, m.start());
            String ph = m.group(1);
            String value = Papi.set(player, m.group(0));
            sb.append(plugin.replace().resolve(ph, value));
            last = m.end();
        }
        sb.append(papi.substring(last));
        return sb.toString();
    }
}