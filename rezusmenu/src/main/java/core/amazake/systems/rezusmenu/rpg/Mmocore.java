package core.amazake.systems.rezusmenu.rpg;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Навыки класса из MMOCore — рефлексией через загрузчик самого MMOCore (загрузчик системы его классов не видит).
 * Только читает; только в потоке игрока (PlayerData MMOCore не потокобезопасен). Любая ошибка — {@link View#error}.
 */
final class Mmocore {

    /** Навык класса для карточки. */
    record Skill(String id, String name, ItemStack icon, int level, int maxLevel, int unlockLevel, boolean unlocked,
                 String loreFirst) {
    }

    /** Что показать на вкладке; {@code error} не null — MMOCore недоступен или игрок ещё не загружен. */
    record View(String error, int skillPoints, List<Skill> skills) {

        static View error(final String why) {
            return new View(why, 0, List.of());
        }
    }

    /** Методы MMOCore, найденные для этого экземпляра плагина. */
    private record Api(Plugin plugin, Class<?> playerData, Method has, Method get, Method fullyLoaded, Method profess,
                       Method skillPoints, Method skillLevel, Method hasUnlocked, Class<?> handlerType) {
    }

    private static volatile Api api;

    private Mmocore() {
    }

    static void reset() {
        api = null;
    }

    private static Api api() throws ReflectiveOperationException {
        final Plugin plugin = Bukkit.getPluginManager().getPlugin("MMOCore");
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        Api a = api;
        if (a != null && a.plugin() == plugin) {
            return a;
        }
        final ClassLoader cl = plugin.getClass().getClassLoader();
        final Class<?> pd = Class.forName("net.Indyuce.mmocore.api.player.PlayerData", true, cl);
        final Class<?> unlockable = Class.forName("net.Indyuce.mmocore.player.Unlockable", true, cl);
        final Class<?> handler = Class.forName("io.lumine.mythic.lib.skill.handler.SkillHandler", true, cl);
        a = new Api(plugin, pd, pd.getMethod("has", Player.class), pd.getMethod("get", OfflinePlayer.class),
            pd.getMethod("isFullyLoaded"), pd.getMethod("getProfess"), pd.getMethod("getSkillPoints"),
            pd.getMethod("getSkillLevel", handler), pd.getMethod("hasUnlocked", unlockable), handler);
        api = a;
        return a;
    }

    /** Навыки класса игрока (до {@code limit}, по уровню открытия). Не бросает исключений. */
    static View read(final Player player, final int limit) {
        try {
            final Api a = api();
            if (a == null) {
                return View.error("MMOCore недоступен");
            }
            if (!(boolean) a.has().invoke(null, player)) {
                return View.error("Данные MMOCore ещё грузятся");
            }
            final Object data = a.get().invoke(null, player);
            if (!(boolean) a.fullyLoaded().invoke(data)) {
                return View.error("Данные MMOCore ещё грузятся");
            }
            final int points = (int) a.skillPoints().invoke(data);
            final Object cls = a.profess().invoke(data);
            if (cls == null) {
                return new View(null, points, List.of());
            }
            final Collection<?> raw = (Collection<?>) cls.getClass().getMethod("getSkills").invoke(cls);
            final List<Skill> out = new ArrayList<>();
            for (final Object cs : raw) {
                out.add(skill(a, data, cs));
            }
            out.sort(Comparator.comparingInt(Skill::unlockLevel));
            return new View(null, points, List.copyOf(out.subList(0, Math.min(limit, out.size()))));
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return View.error("MMOCore недоступен");
        }
    }

    private static Skill skill(final Api a, final Object data, final Object cs) throws ReflectiveOperationException {
        final Class<?> csType = cs.getClass();
        final Object handler = csType.getMethod("getSkill").invoke(cs);
        final Class<?> h = a.handlerType();
        final String id = String.valueOf(h.getMethod("getId").invoke(handler));
        final String name = String.valueOf(h.getMethod("getName").invoke(handler));
        final int unlock = (int) csType.getMethod("getUnlockLevel").invoke(cs);
        final int max = (int) csType.getMethod("getMaxLevel").invoke(cs);
        final int level = (int) a.skillLevel().invoke(data, handler);
        final boolean unlocked = (boolean) a.hasUnlocked().invoke(data, cs);
        return new Skill(id, name, icon(h, handler, id), level, max, unlock, unlocked, loreFirst(csType, cs, a, data));
    }

    private static ItemStack icon(final Class<?> h, final Object handler, final String id) {
        try {
            final Object options = h.getMethod("getIcon").invoke(handler);
            if (options != null) {
                final Object stack = options.getClass().getMethod("toItemStack").invoke(options);
                if (stack instanceof final ItemStack is && !is.isEmpty()) {
                    return is;
                }
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            // ниже — иконка по соглашению пака RoyalQuest
        }
        final ItemStack paper = new ItemStack(Material.PAPER);
        final NamespacedKey model = NamespacedKey.fromString("royalquest:skill/" + id.toLowerCase(Locale.ROOT));
        if (model != null) {
            paper.editMeta(m -> m.setItemModel(model));
        }
        return paper;
    }

    /** Первая непустая строка описания с подставленными параметрами (легаси-цвета). */
    private static String loreFirst(final Class<?> csType, final Object cs, final Api a, final Object data) {
        try {
            final List<?> lore = (List<?>) csType.getMethod("calculateLore", a.playerData()).invoke(cs, data);
            for (final Object line : lore) {
                final String s = String.valueOf(line);
                if (!s.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "").isBlank()) {
                    return s;
                }
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            // без описания
        }
        return "";
    }
}
