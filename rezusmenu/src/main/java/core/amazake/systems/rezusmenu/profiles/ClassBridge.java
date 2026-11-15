package core.amazake.systems.rezusmenu.profiles;

import core.amazake.systems.rezusmenu.util.Papi;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Класс, уровень, опыт, параметры и навыки персонажа — у плагинов RoyalQuestClasses (RQ: класс, профессия, уровень,
 * опыт, очки и вложенные параметры) и MMOCore (скрытый класс, синхронизируемый RQ: очки и уровни навыков, привязка
 * навыков к слотам, атрибуты, мана). Своих команд «задать класс» у RQ нет, поэтому — их API отражением через
 * загрузчики самих плагинов (система их классов не видит):
 * <ul>
 *   <li>RQ: {@code getDataManager().get(player)} → сеттеры {@code PlayerData}, затем как в самом RQ после смены
 *       класса: {@code BonusManager.clearAll}, {@code StatsManager.applyStats}, {@code BonusManager.apply} (он же
 *       синхронизирует MMOCore), {@code DataManager.save}.</li>
 *   <li>MMOCore: {@code new SavedClassInformation(playerData)} — снимок всего прогресса класса; обратно —
 *       {@code new SavedClassInformation(section).load(class, playerData)} (так MMOCore сам меняет класс игрока).
 *       Общие для классов настройки {@code share_across_classes} на проде выключены — снимок ставится как есть.</li>
 * </ul>
 * Режим {@code commands} (или нет плагинов) — только команды консоли из config.yml. Всё — в потоке игрока.
 */
final class ClassBridge {

    /** Что умеет мост сейчас. */
    enum Kind { API, COMMANDS, NONE }

    private final Logger log;
    private final ProfileConfig cfg;

    ClassBridge(final ProfileConfig cfg, final Logger log) {
        this.cfg = cfg;
        this.log = log;
    }

    Kind kind() {
        return switch (this.cfg.bridgeMode()) {
            case "none" -> Kind.NONE;
            case "commands" -> Kind.COMMANDS;
            case "api" -> Kind.API;
            default -> rq() != null ? Kind.API : Kind.COMMANDS;
        };
    }

    // --- чтение для карточек ---

    /** Ключ класса (swordsman…) или "" — без класса. */
    String classKey(final Player p) {
        try {
            final Object data = rqData(p);
            if (data != null) {
                final Object bc = call(data, "getBaseClass");
                return bc == null || !(boolean) call(data, "isClassChosen") ? "" : configKey(bc);
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            // ниже — плейсхолдер
        }
        final String v = Papi.value(p, this.cfg.classKeyPlaceholder());
        return v == null ? "" : v.toLowerCase(Locale.ROOT);
    }

    int level(final Player p) {
        try {
            final Object data = rqData(p);
            if (data != null) {
                return (int) call(data, "getLevel");
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            // ниже — плейсхолдер
        }
        final String v = Papi.value(p, this.cfg.levelPlaceholder());
        try {
            return v == null ? 1 : Integer.parseInt(v.strip());
        } catch (final NumberFormatException e) {
            return 1;
        }
    }

    // --- снимок и установка ---

    /** Снять класс и прогресс; null — снимать нечего (режим none). */
    ConfigurationSection capture(final Player p) {
        final MemoryConfiguration out = new MemoryConfiguration();
        final Kind kind = this.kind();
        if (kind == Kind.API) {
            try {
                final Object data = rqData(p);
                if (data == null) {
                    throw new IllegalStateException("RoyalQuestClasses недоступен");
                }
                captureRq(data, out.createSection("rq"));
                final ConfigurationSection mmo = out.createSection("mmocore");
                if (!captureMmo(p, mmo)) {
                    out.set("mmocore", null);
                }
            } catch (final ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("снимок класса не снят: " + e, e);
            }
        }
        out.set("class-key", this.classKey(p));
        out.set("level", this.level(p));
        if (kind != Kind.NONE) {
            this.run(this.cfg.onSave(), p, out);
        }
        return out;
    }

    /** Надеть сохранённый класс и прогресс. */
    void apply(final Player p, final ConfigurationSection s) {
        final Kind kind = this.kind();
        if (kind == Kind.API && s != null && s.isConfigurationSection("rq")) {
            try {
                // MMOCore первым: BonusManager.apply у RQ сразу синхронизирует класс и уровень MMOCore с RQ.
                if (s.isConfigurationSection("mmocore")) {
                    applyMmo(p, s.getConfigurationSection("mmocore"));
                }
                applyRq(p, s.getConfigurationSection("rq"));
            } catch (final ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("класс не установлен: " + e, e);
            }
        }
        if (kind != Kind.NONE) {
            this.run(this.cfg.onLoad(), p, s);
        }
    }

    /** Новый персонаж: класс {@code key}, уровень 1, всё по нулям. */
    void fresh(final Player p, final String key) {
        final Kind kind = this.kind();
        if (kind == Kind.API) {
            try {
                freshMmo(p);
                final MemoryConfiguration rq = new MemoryConfiguration();
                rq.set("class", key == null || key.isBlank() ? null : key);
                rq.set("class-chosen", key != null && !key.isBlank());
                rq.set("level", 1);
                rq.set("exp", 0L);
                rq.set("mmocore.skill-points-backfilled", true);
                applyRq(p, rq);
            } catch (final ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("новый класс не установлен: " + e, e);
            }
        }
        if (kind != Kind.NONE) {
            final MemoryConfiguration vars = new MemoryConfiguration();
            vars.set("class-key", key == null ? "" : key);
            vars.set("level", 1);
            this.run(this.cfg.onCreate(), p, vars);
        }
    }

    /** Выбрать класс персонажу без класса (как кнопка в меню выбора класса RQ). */
    void chooseClass(final Player p, final String key) {
        if (this.kind() == Kind.API) {
            try {
                final Plugin rq = rq();
                final Object data = rqData(p);
                final Object bc = baseClass(rq, key);
                if (data == null || bc == null) {
                    throw new IllegalStateException("нет класса " + key);
                }
                data.getClass().getMethod("setBaseClass", ((Enum<?>) bc).getDeclaringClass()).invoke(data, bc);
                rqRefresh(rq, p, data);
                return;
            } catch (final ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("класс не выбран: " + e, e);
            }
        }
        final MemoryConfiguration vars = new MemoryConfiguration();
        vars.set("class-key", key);
        vars.set("level", this.level(p));
        this.run(this.cfg.onCreate(), p, vars);
    }

    /** Сохранить данные плагинов на диск сразу (после смены профиля). Ошибки — только в лог. */
    void flush(final Player p) {
        if (this.kind() != Kind.API) {
            return;
        }
        try {
            final Plugin rq = rq();
            final Object data = rqData(p);
            if (rq != null && data != null) {
                final Object dm = call(rq, "getDataManager");
                find(dm.getClass(), "save", 1, data.getClass()).invoke(dm, data);
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            this.log.warning("profiles: RoyalQuestClasses не сохранил игрока " + p.getName() + ": " + e);
        }
        try {
            final Object md = mmoData(p);
            final Plugin mmo = mmo();
            if (md != null && mmo != null) {
                final Object core = mmo.getClass().getField("plugin").get(null);
                final Object pdm = core.getClass().getField("playerDataManager").get(core);
                final ClassLoader cl = mmo.getClass().getClassLoader();
                // Без getMethods()/Enum.valueOf: они разбирают все сигнатуры класса, а там есть типы плагина профилей
                // Phoenix, которого на сервере нет (NoClassDefFoundError). Поле и точный дескриптор — безопасно.
                final Class<?> reason = Class.forName("io.lumine.mythic.lib.profile.SessionUpdateReason", true, cl);
                final Class<?> holder = Class.forName("io.lumine.mythic.lib.data.SynchronizedDataHolder", true, cl);
                final Object autosave = reason.getField("AUTOSAVE").get(null);
                final java.lang.invoke.MethodHandle save = java.lang.invoke.MethodHandles.publicLookup().findVirtual(
                    pdm.getClass(), "saveData", java.lang.invoke.MethodType.methodType(
                        java.util.concurrent.CompletableFuture.class, holder, reason));
                save.invoke(pdm, md, autosave);
            }
        } catch (final Throwable e) {
            this.log.warning("profiles: MMOCore не сохранил игрока " + p.getName() + ": " + e);
        }
    }

    // --- RoyalQuestClasses ---

    private static Plugin rq() {
        final Plugin p = Bukkit.getPluginManager().getPlugin("RoyalQuestClasses");
        return p != null && p.isEnabled() ? p : null;
    }

    private static Object rqData(final Player p) throws ReflectiveOperationException {
        final Plugin rq = rq();
        if (rq == null) {
            return null;
        }
        final Object dm = call(rq, "getDataManager");
        return dm.getClass().getMethod("get", Player.class).invoke(dm, p);
    }

    private static String configKey(final Object enumValue) {
        try {
            return String.valueOf(call(enumValue, "getConfigKey")).toLowerCase(Locale.ROOT);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return ((Enum<?>) enumValue).name().toLowerCase(Locale.ROOT);
        }
    }

    private static void captureRq(final Object d, final ConfigurationSection s) throws ReflectiveOperationException {
        final Object bc = call(d, "getBaseClass");
        final Object pr = call(d, "getProfession");
        s.set("class", bc == null ? null : ((Enum<?>) bc).name());
        s.set("profession", pr == null ? null : ((Enum<?>) pr).name());
        s.set("class-chosen", call(d, "isClassChosen"));
        s.set("profession-chosen", call(d, "isProfessionChosen"));
        s.set("level", call(d, "getLevel"));
        s.set("exp", call(d, "getExp"));
        s.set("stats.available", call(d, "getAvailableStatPoints"));
        s.set("stats.primary", call(d, "getPrimaryStat"));
        s.set("stats.agility", call(d, "getAgility"));
        s.set("stats.stamina", call(d, "getStamina"));
        s.set("stats.luck", call(d, "getLuck"));
        s.set("mmocore.class", call(d, "getMmocoreClass"));
        s.set("mmocore.auto-bound", new ArrayList<>((Collection<?>) call(d, "getAutoBoundSkills")).stream().map(String::valueOf).toList());
        s.set("mmocore.skill-points-granted", call(d, "getSkillPointsGranted"));
        s.set("mmocore.skill-points-pending", call(d, "getSkillPointsPending"));
        s.set("mmocore.skill-points-backfilled", call(d, "isSkillPointsBackfilled"));
    }

    private static Object baseClass(final Plugin rq, final String name) throws ReflectiveOperationException {
        if (name == null || name.isBlank()) {
            return null;
        }
        final Class<?> c = Class.forName("ru.royalquest.classes.model.BaseClass", true, rq.getClass().getClassLoader());
        return c.getMethod("fromString", String.class).invoke(null, name);
    }

    private static Object profession(final Plugin rq, final String name) throws ReflectiveOperationException {
        if (name == null || name.isBlank()) {
            return null;
        }
        final Class<?> c = Class.forName("ru.royalquest.classes.model.Profession", true, rq.getClass().getClassLoader());
        return c.getMethod("fromString", String.class).invoke(null, name);
    }

    /** Те же поля, что пишет и читает сам RQ (playerdata/<uuid>.yml). */
    private void applyRq(final Player p, final ConfigurationSection s) throws ReflectiveOperationException {
        final Plugin rq = rq();
        final Object d = rqData(p);
        if (rq == null || d == null) {
            throw new IllegalStateException("RoyalQuestClasses недоступен");
        }
        final Object bonus = call(rq, "getBonusManager");
        bonus.getClass().getMethod("clearAll", Player.class).invoke(bonus, p);
        call(d, "resetAll");
        final Object bc = baseClass(rq, s.getString("class"));
        if (bc != null) {
            d.getClass().getMethod("setBaseClass", ((Enum<?>) bc).getDeclaringClass()).invoke(d, bc);
        }
        final Object pr = profession(rq, s.getString("profession"));
        if (pr != null) {
            d.getClass().getMethod("setProfession", ((Enum<?>) pr).getDeclaringClass()).invoke(d, pr);
        }
        set(d, "setClassChosen", boolean.class, s.getBoolean("class-chosen", bc != null));
        set(d, "setProfessionChosen", boolean.class, s.getBoolean("profession-chosen", pr != null));
        set(d, "setLevel", int.class, Math.max(1, s.getInt("level", 1)));
        set(d, "setExp", long.class, Math.max(0, s.getLong("exp", 0)));
        set(d, "setAvailableStatPoints", int.class, s.getInt("stats.available", 0));
        set(d, "setPrimaryStat", int.class, s.getInt("stats.primary", 0));
        set(d, "setAgility", int.class, s.getInt("stats.agility", 0));
        set(d, "setStamina", int.class, s.getInt("stats.stamina", 0));
        set(d, "setLuck", int.class, s.getInt("stats.luck", 0));
        d.getClass().getMethod("setMmocoreClass", String.class).invoke(d, s.getString("mmocore.class"));
        @SuppressWarnings("unchecked")
        final Set<String> bound = (Set<String>) call(d, "getAutoBoundSkills");
        bound.clear();
        bound.addAll(s.getStringList("mmocore.auto-bound"));
        set(d, "setSkillPointsGranted", int.class, s.getInt("mmocore.skill-points-granted", 0));
        set(d, "setSkillPointsPending", int.class, s.getInt("mmocore.skill-points-pending", 0));
        set(d, "setSkillPointsBackfilled", boolean.class, s.getBoolean("mmocore.skill-points-backfilled", true));
        rqRefresh(rq, p, d);
    }

    /** Как RQ после смены класса: параметры, бонусы (+ синхронизация MMOCore), запись файла. */
    private static void rqRefresh(final Plugin rq, final Player p, final Object d) throws ReflectiveOperationException {
        final Object stats = call(rq, "getStatsManager");
        stats.getClass().getMethod("applyStats", Player.class).invoke(stats, p);
        final Object bonus = call(rq, "getBonusManager");
        bonus.getClass().getMethod("apply", Player.class).invoke(bonus, p);
        final Object dm = call(rq, "getDataManager");
        find(dm.getClass(), "save", 1, d.getClass()).invoke(dm, d);
    }

    // --- MMOCore ---

    private static Plugin mmo() {
        final Plugin p = Bukkit.getPluginManager().getPlugin("MMOCore");
        return p != null && p.isEnabled() ? p : null;
    }

    private static Object mmoData(final Player p) throws ReflectiveOperationException {
        final Plugin mmo = mmo();
        if (mmo == null) {
            return null;
        }
        final Class<?> pd = Class.forName("net.Indyuce.mmocore.api.player.PlayerData", true, mmo.getClass().getClassLoader());
        if (!(boolean) pd.getMethod("has", Player.class).invoke(null, p)) {
            return null;
        }
        final Object md = pd.getMethod("get", OfflinePlayer.class).invoke(null, p);
        return md != null && (boolean) call(md, "isFullyLoaded") ? md : null;
    }

    private static Class<?> sci(final Plugin mmo) throws ClassNotFoundException {
        return Class.forName("net.Indyuce.mmocore.api.player.profess.SavedClassInformation", true, mmo.getClass().getClassLoader());
    }

    /** Снимок класса MMOCore в ключах его же ConfigurationSection-конструктора. false — MMOCore нет/не загружен. */
    private static boolean captureMmo(final Player p, final ConfigurationSection s) throws ReflectiveOperationException {
        final Plugin mmo = mmo();
        final Object md = mmoData(p);
        if (mmo == null || md == null) {
            return false;
        }
        final Object profess = call(md, "getProfess");
        s.set("class-id", profess == null ? null : String.valueOf(call(profess, "getId")));
        Object info = null;
        for (final Constructor<?> c : sci(mmo).getConstructors()) {
            if (c.getParameterCount() == 1 && c.getParameterTypes()[0].isInstance(md)
                && !ConfigurationSection.class.isAssignableFrom(c.getParameterTypes()[0])) {
                info = c.newInstance(md);
                break;
            }
        }
        if (info == null) {
            throw new IllegalStateException("MMOCore: нет SavedClassInformation(PlayerData)");
        }
        s.set("level", call(info, "getLevel"));
        s.set("experience", call(info, "getExperience"));
        s.set("skill-points", call(info, "getSkillPoints"));
        s.set("attribute-points", call(info, "getAttributePoints"));
        s.set("attribute-realloc-points", call(info, "getAttributeReallocationPoints"));
        s.set("skill-reallocation-points", call(info, "getSkillReallocationPoints"));
        s.set("skill-tree-reallocation-points", call(info, "getSkillTreeReallocationPoints"));
        s.set("health", call(info, "getLastHealth"));
        s.set("mana", call(info, "getMana"));
        s.set("stamina", call(info, "getStamina"));
        s.set("stellium", call(info, "getStellium"));
        intMap(s, "attribute", call(info, "mapAttributeLevels"));
        intMap(s, "skill", call(info, "mapSkillLevels"));
        intMap(s, "skill-tree-points", call(info, "mapSkillTreePoints"));
        intMap(s, "node-levels", call(info, "getNodeLevels"));
        intMap(s, "node-times-claimed", call(info, "getNodeTimesClaimed"));
        final Map<?, ?> bound = (Map<?, ?>) call(info, "mapBoundSkills");
        for (final Map.Entry<?, ?> e : bound.entrySet()) {
            s.set("bound-skills." + e.getKey(), String.valueOf(e.getValue()));
        }
        s.set("unlocked-items", new ArrayList<>((Collection<?>) call(info, "getUnlockedItems")).stream().map(String::valueOf).toList());
        return true;
    }

    private static void intMap(final ConfigurationSection s, final String key, final Object map) {
        final Map<String, Object> m = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
            m.put(String.valueOf(e.getKey()), e.getValue());
        }
        if (!m.isEmpty()) {
            s.createSection(key, m);
        }
    }

    private static void applyMmo(final Player p, final ConfigurationSection s) throws ReflectiveOperationException {
        final Plugin mmo = mmo();
        final Object md = mmoData(p);
        if (mmo == null || md == null) {
            throw new IllegalStateException("данные MMOCore игрока ещё не загружены");
        }
        loadMmo(mmo, md, s, s.getString("class-id"));
    }

    private static void freshMmo(final Player p) throws ReflectiveOperationException {
        final Plugin mmo = mmo();
        final Object md = mmoData(p);
        if (mmo == null || md == null) {
            return; // MMOCore нет — RQ справится сам
        }
        final MemoryConfiguration s = new MemoryConfiguration();
        s.set("level", 1);
        s.set("experience", 0.0);
        s.set("skill-points", 0);
        s.set("attribute-points", 0);
        s.set("attribute-realloc-points", 0);
        s.set("skill-reallocation-points", 0);
        s.set("skill-tree-reallocation-points", 0);
        loadMmo(mmo, md, s, null);
    }

    /** {@code SavedClassInformation(section).load(class, data)}; класс — по id, иначе класс по умолчанию. */
    private static void loadMmo(final Plugin mmo, final Object md, final ConfigurationSection s, final String classId)
        throws ReflectiveOperationException {
        final Object core = mmo.getClass().getField("plugin").get(null);
        final Object classes = core.getClass().getField("classManager").get(core);
        Object profess = null;
        if (classId != null && !classId.isBlank()) {
            profess = classes.getClass().getMethod("get", String.class).invoke(classes, classId);
        }
        if (profess == null) {
            profess = call(classes, "getDefaultClass");
        }
        final Object info = sci(mmo).getConstructor(ConfigurationSection.class).newInstance(s);
        for (final Method m : info.getClass().getMethods()) {
            if (m.getName().equals("load") && m.getParameterCount() == 2 && m.getParameterTypes()[0].isInstance(profess)
                && m.getParameterTypes()[1].isInstance(md)) {
                m.invoke(info, profess, md);
                return;
            }
        }
        throw new IllegalStateException("MMOCore: нет SavedClassInformation.load(PlayerClass, PlayerData)");
    }

    // --- команды ---

    private void run(final List<String> commands, final Player p, final ConfigurationSection vars) {
        if (commands.isEmpty()) {
            return;
        }
        final String cls = vars == null ? "" : vars.getString("class-key", "");
        final String lvl = vars == null ? "1" : String.valueOf(vars.getInt("level", 1));
        final String name = p.getName();
        final String uuid = p.getUniqueId().toString();
        final List<String> ready = new ArrayList<>();
        for (final String c : commands) {
            ready.add(Papi.apply(p, c.replace("{player}", name).replace("{uuid}", uuid).replace("{class}", cls)
                .replace("{level}", lvl)));
        }
        Bukkit.getGlobalRegionScheduler().execute(owner(), () -> {
            for (final String c : ready) {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
                } catch (final RuntimeException e) {
                    this.log.warning("profiles: команда «" + c + "» упала: " + e);
                }
            }
        });
    }

    private static volatile Plugin owner;

    static void owner(final Plugin plugin) {
        owner = plugin;
    }

    private static Plugin owner() {
        return owner;
    }

    // --- отражение ---

    private static Object call(final Object target, final String method) throws ReflectiveOperationException {
        final Class<?> type = target instanceof final Enum<?> e ? e.getDeclaringClass() : target.getClass();
        return type.getMethod(method).invoke(target);
    }

    private static void set(final Object target, final String method, final Class<?> type, final Object value)
        throws ReflectiveOperationException {
        target.getClass().getMethod(method, type).invoke(target, value);
    }

    private static Method find(final Class<?> type, final String name, final int params, final Class<?> arg)
        throws NoSuchMethodException {
        for (final Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params && m.getParameterTypes()[0].isAssignableFrom(arg)) {
                return m;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
