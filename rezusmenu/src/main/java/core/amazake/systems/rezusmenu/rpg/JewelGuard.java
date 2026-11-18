package core.amazake.systems.rezusmenu.rpg;

import core.amazake.systems.rezusmenu.Settings;
import core.amazake.systems.rezusmenu.util.Text;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.inventory.ItemStack;

/**
 * Класс и уровень для бижутерии (версия 1.0.1, перенесено из сборки, залитой на прод напрямую).
 *
 * <p>В пять слотов бижутерии кладётся только то, что подходит игроку по классу и уровню. Требования берутся из NBT
 * MMOItems ({@code MMOITEMS_REQUIRED_CLASS}, {@code MMOITEMS_LEVEL} / {@code MMOITEMS_REQUIRED_LEVEL}, через API
 * MythicLib или разбором компонентов), а для предметов без NBT (Nexo, id из {@code nexo:id}) — из
 * {@code rpg.jewelry.bind.item-classes} / {@code item-levels} (без раздела — встроенные списки ниже). Класс и
 * уровень игрока — RoyalQuestClasses (базовый класс + профессия), без него — MMOCore. Профессия подходит и под
 * требование своего базового класса ({@link #PARENT}).</p>
 *
 * <p>{@link #enforce} снимает уже надетое, что перестало подходить (сменил класс, профиль, уровень упал), и
 * возвращает в инвентарь (полный — на землю). Зовётся при входе, при включении системы, после смены профиля, при
 * открытии меню и по событиям MMOCore (смена класса, уровня, загрузка данных). Если класс или уровень игрока не
 * удалось узнать — по этому требованию ничего не снимается.</p>
 */
public final class JewelGuard {

    private static final NamespacedKey NEXO_ID = new NamespacedKey("nexo", "id");
    private static final Pattern NBT_STRING =
        Pattern.compile("MMOITEMS_([A-Z_]+)\\\\?\"?\\s*:\\s*\\\\?\"?([^,}\\]\"]+)");
    /** Профессия → базовый класс. */
    private static final Map<String, String> PARENT = Map.of(
        "crusader", "swordsman", "dark_knight", "swordsman",
        "hunter", "archer", "sniper", "archer",
        "wizard", "mage", "warlock", "mage",
        "rogue", "thief", "assassin", "thief");
    private static final Set<String> BASE = Set.of("swordsman", "archer", "mage", "thief");
    /** Русские названия классов (как в NBT MMOItems) → ключ. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("swordsman", "swordsman"), Map.entry("мечник", "swordsman"),
        Map.entry("crusader", "crusader"), Map.entry("крестоносец", "crusader"),
        Map.entry("dark_knight", "dark_knight"), Map.entry("тёмный рыцарь", "dark_knight"),
        Map.entry("темный рыцарь", "dark_knight"),
        Map.entry("archer", "archer"), Map.entry("стрелок", "archer"),
        Map.entry("hunter", "hunter"), Map.entry("охотник", "hunter"),
        Map.entry("sniper", "sniper"), Map.entry("снайпер", "sniper"),
        Map.entry("mage", "mage"), Map.entry("маг", "mage"),
        Map.entry("wizard", "wizard"), Map.entry("волшебник", "wizard"),
        Map.entry("warlock", "warlock"), Map.entry("чернокнижник", "warlock"),
        Map.entry("thief", "thief"), Map.entry("вор", "thief"),
        Map.entry("rogue", "rogue"), Map.entry("разбойник", "rogue"),
        Map.entry("assassin", "assassin"), Map.entry("ассасин", "assassin"));
    private static final Map<String, List<String>> DEFAULT_CLASSES = defaultClasses();
    private static final Map<String, Integer> DEFAULT_LEVELS = defaultLevels();

    private static volatile Supplier<Settings> settings;
    private static volatile Plugin plugin;
    private static volatile Logger log;
    private static volatile Map<String, List<String>> classFallback = DEFAULT_CLASSES;
    private static volatile Map<String, Integer> levelFallback = DEFAULT_LEVELS;
    private static volatile ClassesApi classesApi;
    private static volatile NbtApi nbtApi;
    private static volatile boolean eventsRegistered;

    private JewelGuard() {
    }

    /** При включении системы: откуда брать настройки, плагин для задач и событий. */
    public static void attach(final Supplier<Settings> settingsSupplier, final Plugin owner, final Logger logger) {
        settings = settingsSupplier;
        plugin = owner;
        log = logger;
        classesApi = null;
        nbtApi = null;
        registerEvents(owner);
    }

    /** {@code rpg.jewelry.bind}: item-classes и item-levels поверх встроенных списков (пустой список / 0 — убрать). */
    public static void configure(final ConfigurationSection bind) {
        final Map<String, List<String>> classes = new LinkedHashMap<>(DEFAULT_CLASSES);
        final Map<String, Integer> levels = new LinkedHashMap<>(DEFAULT_LEVELS);
        if (bind != null) {
            final ConfigurationSection ic = bind.getConfigurationSection("item-classes");
            if (ic != null) {
                for (final String key : ic.getKeys(false)) {
                    final String id = normId(key);
                    if (id == null) {
                        continue;
                    }
                    List<String> raw = ic.getStringList(key);
                    if (raw.isEmpty()) {
                        final String s = ic.getString(key);
                        if (s != null && !s.isBlank()) {
                            raw = List.of(s.split(","));
                        }
                    }
                    final List<String> out = new ArrayList<>();
                    for (final String r : raw) {
                        final String c = resolve(r);
                        if (c != null) {
                            out.add(c);
                        }
                    }
                    if (out.isEmpty()) {
                        classes.remove(id);
                    } else {
                        classes.put(id, List.copyOf(out));
                    }
                }
            }
            final ConfigurationSection il = bind.getConfigurationSection("item-levels");
            if (il != null) {
                for (final String key : il.getKeys(false)) {
                    final String id = normId(key);
                    if (id == null) {
                        continue;
                    }
                    final int n = il.getInt(key, 0);
                    if (n <= 0) {
                        levels.remove(id);
                    } else {
                        levels.put(id, n);
                    }
                }
            }
        }
        classFallback = Map.copyOf(classes);
        levelFallback = Map.copyOf(levels);
    }

    /** Почему предмет нельзя положить в слот бижутерии {@code slot} (null — можно). */
    public static String denyReason(final Settings st, final Player player, final int slot, final ItemStack item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        if (!Jewelry.allowed(st, slot, item)) {
            return "Это не " + Jewelry.slotName(slot).toLowerCase(Locale.ROOT);
        }
        if (core.amazake.systems.rezusmenu.util.AdminMode.isOn(player)) {
            return null; // режим админа (/rpgadmin): класс и уровень не проверяются
        }
        final String id = itemId(item);
        final Set<String> need = requiredClass(item, id);
        if (!need.isEmpty()) {
            final Set<String> have = playerClasses(player);
            if (have.isEmpty()) {
                return "Не подходит класс";
            }
            if (!matches(need, have)) {
                return "Не подходит класс";
            }
        }
        final int needLevel = requiredLevel(item, id);
        if (needLevel > 0) {
            final int have = playerLevel(player);
            if (have >= 0 && have < needLevel) {
                return "Недостаточный уровень (нужен " + needLevel + ")";
            }
        }
        return null;
    }

    /**
     * Снять из слотов бижутерии всё, что игроку больше не подходит, и вернуть ему (полный инвентарь — на землю).
     * Сколько снято. Поток игрока.
     */
    public static int enforce(final Player player) {
        if (player == null || !player.isOnline() || core.amazake.systems.rezusmenu.util.AdminMode.isOn(player)) {
            return 0; // режим админа (/rpgadmin): бижутерию не снимаем
        }
        final Settings st = settings == null ? null : settings.get();
        if (st == null) {
            return 0;
        }
        final Set<String> classes = playerClasses(player);
        final int level = playerLevel(player);
        final boolean classKnown = !classes.isEmpty();
        final Jewelry jewelry = new Jewelry(player);
        int removed = 0;
        for (int slot = Jewelry.AMULET; slot <= Jewelry.ARTIFACT; slot++) {
            if (jewelry.locked(slot)) {
                continue;
            }
            final ItemStack item = jewelry.get(slot);
            if (item == null || item.isEmpty()) {
                continue;
            }
            final String id = itemId(item);
            final Set<String> need = requiredClass(item, id);
            final int needLevel = requiredLevel(item, id);
            String reason = null;
            if (!need.isEmpty()) {
                if (!classKnown) {
                    continue;
                }
                if (!matches(need, classes)) {
                    reason = "не подходит класс";
                }
            }
            if (reason == null && needLevel > 0) {
                if (level < 0) {
                    continue;
                }
                if (level < needLevel) {
                    reason = "недостаточный уровень (нужен " + needLevel + ")";
                }
            }
            if (reason == null) {
                continue;
            }
            jewelry.set(slot, null);
            giveBack(player, item);
            player.sendMessage(Text.mm(Text.prefix() + "<yellow>Снят предмет из слота «" + Jewelry.slotName(slot)
                + "»: " + reason + ".</yellow>"));
            removed++;
        }
        return removed;
    }

    /** {@link #enforce} через {@code ticks} тиков в потоке игрока. */
    public static void scheduleEnforce(final Player player, final long ticks) {
        final Plugin p = plugin;
        if (player == null || p == null || !p.isEnabled()) {
            return;
        }
        player.getScheduler().runDelayed(p, t -> enforce(player), null, Math.max(1L, ticks));
    }

    /** События MMOCore (смена класса, уровня, загрузка данных) — проверить бижутерию через 2 тика. */
    private static void registerEvents(final Plugin p) {
        if (p == null || eventsRegistered) {
            return;
        }
        eventsRegistered = true;
        final Listener listener = new Listener() {
        };
        final EventExecutor executor = (l, event) -> {
            final Player player = eventPlayer(event);
            if (player != null) {
                scheduleEnforce(player, 2L);
            }
        };
        final String[] names = {
            "net.Indyuce.mmocore.api.event.PlayerChangeClassEvent",
            "net.Indyuce.mmocore.api.event.PlayerLevelChangeEvent",
            "net.Indyuce.mmocore.api.event.PlayerLevelUpEvent",
            "net.Indyuce.mmocore.api.event.PlayerDataLoadEvent"};
        for (final String name : names) {
            try {
                final Class<?> c = Class.forName(name, true, p.getClass().getClassLoader());
                if (Event.class.isAssignableFrom(c)) {
                    @SuppressWarnings("unchecked")
                    final Class<? extends Event> ec = (Class<? extends Event>) c;
                    Bukkit.getPluginManager().registerEvent(ec, listener, EventPriority.MONITOR, executor, p, true);
                }
            } catch (final ClassNotFoundException | LinkageError e) {
                // MMOCore нет или событие другое — пропустить
            }
        }
    }

    private static Player eventPlayer(final Event event) {
        try {
            final Method m = event.getClass().getMethod("getPlayer");
            final Object o = m.invoke(event);
            return o instanceof final Player pl ? pl : null;
        } catch (final ReflectiveOperationException e) {
            return null;
        }
    }

    private static void giveBack(final Player player, final ItemStack item) {
        final Map<Integer, ItemStack> left = player.getInventory().addItem(item);
        if (left.isEmpty()) {
            return;
        }
        for (final ItemStack rest : left.values()) {
            if (rest != null && !rest.isEmpty()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
                player.sendMessage(Text.mm(Text.prefix()
                    + "<yellow>Инвентарь был полон — предмет выброшен на землю.</yellow>"));
            }
        }
    }

    /** id предмета: Nexo ({@code nexo:id}), иначе MMOItems ({@code MMOITEMS_ITEM_ID}); нижний регистр, без пути. */
    static String itemId(final ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return null;
        }
        try {
            final String nexo = item.getItemMeta().getPersistentDataContainer()
                .get(NEXO_ID, org.bukkit.persistence.PersistentDataType.STRING);
            final String id = normId(nexo);
            if (id != null) {
                return id;
            }
        } catch (final RuntimeException e) {
            // ниже — MMOItems
        }
        return normId(nbtString(item, "MMOITEMS_ITEM_ID"));
    }

    static Set<String> requiredClass(final ItemStack item, final String id) {
        final Set<String> fromNbt = parseClassList(nbtString(item, "MMOITEMS_REQUIRED_CLASS"));
        if (!fromNbt.isEmpty()) {
            return fromNbt;
        }
        final List<String> list = id == null ? null : classFallback.get(id);
        return list == null || list.isEmpty() ? Set.of() : new LinkedHashSet<>(list);
    }

    static int requiredLevel(final ItemStack item, final String id) {
        int n = nbtInt(item, "MMOITEMS_LEVEL");
        if (n <= 0) {
            n = nbtInt(item, "MMOITEMS_REQUIRED_LEVEL");
        }
        if (n > 0) {
            return n;
        }
        final Integer v = id == null ? null : levelFallback.get(id);
        return v == null ? 0 : v;
    }

    static Set<String> playerClasses(final Player player) {
        final ClassesApi api = classesApi();
        if (api != null) {
            try {
                final Set<String> s = api.of(player);
                if (s != null && !s.isEmpty()) {
                    return s;
                }
            } catch (final RuntimeException e) {
                // ниже — пусто
            }
        }
        return Set.of();
    }

    /** Уровень игрока или −1, если не узнать. */
    static int playerLevel(final Player player) {
        final ClassesApi api = classesApi();
        if (api != null) {
            try {
                return api.level(player);
            } catch (final RuntimeException e) {
                // ниже
            }
        }
        return -1;
    }

    /** Подходит ли игрок (его классы — базовый и профессия) под требуемые классы. Пустое требование — да. */
    static boolean matches(final Set<String> required, final Set<String> player) {
        if (required == null || required.isEmpty()) {
            return true;
        }
        if (player == null || player.isEmpty()) {
            return false;
        }
        for (final String r : required) {
            final String k = resolve(r);
            if (k != null && player.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> parseClassList(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        final Set<String> out = new LinkedHashSet<>();
        for (final String part : raw.split(",")) {
            final String k = resolve(part);
            if (k != null) {
                out.add(k);
            }
        }
        return out;
    }

    /** Название или ключ класса → ключ (swordsman, crusader…); неизвестное — null. */
    private static String resolve(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        final String k = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        final String alias = ALIASES.get(k);
        if (alias != null) {
            return alias;
        }
        return BASE.contains(k) || PARENT.containsKey(k) ? k : null;
    }

    /** id в нижнем регистре без пространства/пути ({@code azerus:rings/ring_049} → {@code ring_049}). */
    private static String normId(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        final int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        if (cut >= 0) {
            s = s.substring(cut + 1);
        }
        return s.isEmpty() ? null : s;
    }

    private static String nbtString(final ItemStack item, final String tag) {
        final NbtApi api = nbtApi();
        if (api != null) {
            try {
                final Object nbt = api.get.invoke(null, item);
                if (nbt != null && Boolean.TRUE.equals(api.hasTag.invoke(nbt, tag))) {
                    final Object v = api.getString.invoke(nbt, tag);
                    if (v != null && !v.toString().isEmpty()) {
                        return v.toString();
                    }
                }
            } catch (final ReflectiveOperationException | RuntimeException e) {
                // ниже — разбор компонентов
            }
        }
        if (!item.hasItemMeta()) {
            return null;
        }
        try {
            final Matcher m = NBT_STRING.matcher(item.getItemMeta().getAsString());
            while (m.find()) {
                if (tag.equals("MMOITEMS_" + m.group(1))) {
                    return m.group(2);
                }
            }
        } catch (final RuntimeException e) {
            // нет
        }
        return null;
    }

    private static int nbtInt(final ItemStack item, final String tag) {
        final NbtApi api = nbtApi();
        if (api != null) {
            try {
                final Object nbt = api.get.invoke(null, item);
                if (nbt != null && Boolean.TRUE.equals(api.hasTag.invoke(nbt, tag))) {
                    final Object i = api.getInteger.invoke(nbt, tag);
                    if (i instanceof final Number n && n.intValue() != 0) {
                        return n.intValue();
                    }
                    final Object d = api.getDouble.invoke(nbt, tag);
                    if (d instanceof final Number n && n.doubleValue() > 0) {
                        return (int) Math.round(n.doubleValue());
                    }
                }
            } catch (final ReflectiveOperationException | RuntimeException e) {
                // ниже — строкой
            }
        }
        final String s = nbtString(item, tag);
        if (s == null) {
            return 0;
        }
        try {
            return (int) Math.round(Double.parseDouble(s.trim()));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** NBTItem MythicLib (MMOItems пишет требования в NBT предмета). */
    private static final class NbtApi {
        final Plugin plugin;
        final Method get;
        final Method getString;
        final Method hasTag;
        final Method getDouble;
        final Method getInteger;

        NbtApi(final Plugin plugin, final Method get, final Method getString, final Method hasTag,
               final Method getDouble, final Method getInteger) {
            this.plugin = plugin;
            this.get = get;
            this.getString = getString;
            this.hasTag = hasTag;
            this.getDouble = getDouble;
            this.getInteger = getInteger;
        }
    }

    private static NbtApi nbtApi() {
        final Plugin ml = Bukkit.getPluginManager().getPlugin("MythicLib");
        if (ml == null || !ml.isEnabled()) {
            return null;
        }
        NbtApi a = nbtApi;
        if (a != null && a.plugin == ml) {
            return a;
        }
        try {
            final Class<?> c = Class.forName("io.lumine.mythic.lib.api.item.NBTItem", true, ml.getClass().getClassLoader());
            a = new NbtApi(ml, c.getMethod("get", ItemStack.class), c.getMethod("getString", String.class),
                c.getMethod("hasTag", String.class), c.getMethod("getDouble", String.class),
                c.getMethod("getInteger", String.class));
            nbtApi = a;
            return a;
        } catch (final ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static ClassesApi classesApi() {
        ClassesApi a = classesApi;
        if (a != null) {
            return a;
        }
        a = new ClassesApi(log != null ? log : Bukkit.getLogger());
        classesApi = a;
        return a;
    }

    /** Класс и уровень игрока: RoyalQuestClasses (класс + профессия), без него — MMOCore. Отражением. */
    private static final class ClassesApi {
        private Object rqPlugin;
        private Method rqDataManager;
        private Method rqGet;
        private Method rqClassChosen;
        private Method rqBase;
        private Method rqProfession;
        private Method rqBaseKey;
        private Method rqProfessionKey;
        private Method rqLevel;
        private boolean rq;
        private Method mmoGet;
        private Method mmoProfess;
        private Method mmoClassId;
        private Method mmoLevel;
        private boolean mmo;

        ClassesApi(final Logger logger) {
            final Plugin rqp = Bukkit.getPluginManager().getPlugin("RoyalQuestClasses");
            if (rqp != null && rqp.isEnabled()) {
                try {
                    final ClassLoader cl = rqp.getClass().getClassLoader();
                    final Class<?> main = Class.forName("ru.royalquest.classes.RoyalQuestClasses", true, cl);
                    this.rqPlugin = main.getMethod("getInstance").invoke(null);
                    this.rqDataManager = main.getMethod("getDataManager");
                    final Class<?> dm = Class.forName("ru.royalquest.classes.data.PlayerDataManager", true, cl);
                    this.rqGet = dm.getMethod("get", Player.class);
                    final Class<?> pd = Class.forName("ru.royalquest.classes.data.PlayerData", true, cl);
                    this.rqClassChosen = pd.getMethod("isClassChosen");
                    this.rqBase = pd.getMethod("getBaseClass");
                    this.rqProfession = pd.getMethod("getProfession");
                    this.rqBaseKey = Class.forName("ru.royalquest.classes.model.BaseClass", true, cl).getMethod("getConfigKey");
                    this.rqProfessionKey = Class.forName("ru.royalquest.classes.model.Profession", true, cl)
                        .getMethod("getConfigKey");
                    try {
                        this.rqLevel = pd.getMethod("getLevel");
                    } catch (final NoSuchMethodException e) {
                        this.rqLevel = null;
                    }
                    this.rq = this.rqPlugin != null;
                } catch (final RuntimeException | LinkageError | ReflectiveOperationException e) {
                    logger.warning("rezusmenu: RoyalQuestClasses найден, класс игрока беру из MMOCore: " + e);
                }
            }
            final Plugin mc = Bukkit.getPluginManager().getPlugin("MMOCore");
            if (mc != null && mc.isEnabled()) {
                try {
                    final ClassLoader cl = mc.getClass().getClassLoader();
                    final Class<?> pd = Class.forName("net.Indyuce.mmocore.api.player.PlayerData", true, cl);
                    this.mmoGet = pd.getMethod("get", org.bukkit.OfflinePlayer.class);
                    this.mmoProfess = pd.getMethod("getProfess");
                    this.mmoClassId = Class.forName("net.Indyuce.mmocore.api.player.profess.PlayerClass", true, cl)
                        .getMethod("getId");
                    try {
                        this.mmoLevel = pd.getMethod("getLevel");
                    } catch (final NoSuchMethodException e) {
                        this.mmoLevel = null;
                    }
                    this.mmo = true;
                } catch (final RuntimeException | LinkageError | ReflectiveOperationException e) {
                    logger.warning("rezusmenu: MMOCore найден, но его API не подошёл: " + e);
                }
            }
        }

        Set<String> of(final Player p) {
            try {
                if (this.rq) {
                    final Object d = this.rqGet.invoke(this.rqDataManager.invoke(this.rqPlugin), p);
                    if (d != null && Boolean.TRUE.equals(this.rqClassChosen.invoke(d))) {
                        final Object base = this.rqBase.invoke(d);
                        final Object prof = this.rqProfession.invoke(d);
                        return keys(base == null ? null : (String) this.rqBaseKey.invoke(base),
                            prof == null ? null : (String) this.rqProfessionKey.invoke(prof));
                    }
                    return Set.of();
                }
                if (this.mmo) {
                    final Object d = this.mmoGet.invoke(null, p);
                    final Object cls = d == null ? null : this.mmoProfess.invoke(d);
                    return fromMmocore(cls == null ? null : (String) this.mmoClassId.invoke(cls));
                }
            } catch (final RuntimeException | ReflectiveOperationException e) {
                // ниже
            }
            return Set.of();
        }

        int level(final Player p) {
            try {
                if (this.rq && this.rqLevel != null) {
                    final Object d = this.rqGet.invoke(this.rqDataManager.invoke(this.rqPlugin), p);
                    if (d != null && this.rqLevel.invoke(d) instanceof final Number n) {
                        return n.intValue();
                    }
                }
                if (this.mmo && this.mmoLevel != null) {
                    final Object d = this.mmoGet.invoke(null, p);
                    if (d != null && this.mmoLevel.invoke(d) instanceof final Number n) {
                        return n.intValue();
                    }
                }
            } catch (final RuntimeException | ReflectiveOperationException e) {
                // ниже
            }
            return -1;
        }

        private static Set<String> keys(final String base, final String profession) {
            final Set<String> out = new LinkedHashSet<>();
            final String b = norm(base);
            final String pr = norm(profession);
            if (b != null && BASE.contains(b)) {
                out.add(b);
            }
            if (pr != null && PARENT.containsKey(pr)) {
                out.add(PARENT.get(pr));
                out.add(pr);
            }
            return Set.copyOf(out);
        }

        private static Set<String> fromMmocore(final String classId) {
            final String c = norm(classId);
            if (c == null) {
                return Set.of();
            }
            return PARENT.containsKey(c) ? keys(null, c) : keys(c, null);
        }

        private static String norm(final String s) {
            return s != null && !s.isBlank() ? s.trim().toLowerCase(Locale.ROOT).replace('-', '_') : null;
        }
    }

    // --- встроенные списки (то же, что rpg.jewelry.bind в конфиге прода) ---

    private static Map<String, List<String>> defaultClasses() {
        final Map<String, List<String>> m = new LinkedHashMap<>();
        put(m, List.of("azerus_ring_051", "azerus_ring_074"), List.of("swordsman", "crusader", "dark_knight"));
        put(m, List.of("azerus_ring_052", "azerus_ring_067"), List.of("crusader"));
        put(m, List.of("azerus_ring_058", "azerus_ring_085"), List.of("dark_knight"));
        put(m, List.of("azerus_ring_054", "azerus_ring_070"), List.of("archer", "hunter", "sniper"));
        put(m, List.of("azerus_ring_075", "azerus_ring_081"), List.of("hunter"));
        put(m, List.of("azerus_ring_065", "azerus_ring_083"), List.of("sniper"));
        put(m, List.of("azerus_ring_049", "azerus_ring_062"), List.of("mage", "wizard", "warlock"));
        put(m, List.of("azerus_ring_057", "azerus_ring_080"), List.of("wizard"));
        put(m, List.of("azerus_ring_072", "azerus_ring_082"), List.of("warlock"));
        put(m, List.of("azerus_ring_060", "azerus_ring_095"), List.of("thief", "rogue", "assassin"));
        put(m, List.of("azerus_ring_050", "azerus_ring_091"), List.of("rogue"));
        put(m, List.of("azerus_ring_098", "azerus_ring_099"), List.of("assassin"));
        put(m, List.of("azerus_artifact_008"),
            List.of("swordsman", "crusader", "dark_knight", "thief", "rogue", "assassin"));
        put(m, List.of("azerus_artifact_009", "azerus_artifact_016", "azerus_artifact_017", "azerus_artifact_002"),
            List.of("mage", "wizard", "warlock"));
        put(m, List.of("azerus_artifact_010"), List.of("archer", "hunter", "sniper"));
        put(m, List.of("azerus_artifact_015", "azerus_artifact_006"), List.of("thief", "rogue", "assassin"));
        put(m, List.of("azerus_artifact_003"), List.of("swordsman", "crusader", "dark_knight", "archer", "hunter",
            "sniper", "thief", "rogue", "assassin"));
        return Map.copyOf(m);
    }

    private static Map<String, Integer> defaultLevels() {
        final Map<String, Integer> m = new HashMap<>();
        m.put("azerus_artifact_007", 1);
        m.put("azerus_artifact_008", 5);
        m.put("azerus_artifact_009", 5);
        m.put("azerus_artifact_010", 10);
        m.put("azerus_artifact_015", 10);
        m.put("azerus_artifact_016", 15);
        m.put("azerus_artifact_017", 15);
        m.put("azerus_artifact_011", 20);
        m.put("azerus_artifact_001", 1);
        m.put("azerus_artifact_002", 15);
        m.put("azerus_artifact_003", 5);
        m.put("azerus_artifact_004", 5);
        m.put("azerus_artifact_005", 10);
        m.put("azerus_artifact_006", 10);
        return Map.copyOf(m);
    }

    private static void put(final Map<String, List<String>> m, final List<String> ids, final List<String> classes) {
        final List<String> c = List.copyOf(classes);
        for (final String id : ids) {
            m.put(id, c);
        }
    }
}
