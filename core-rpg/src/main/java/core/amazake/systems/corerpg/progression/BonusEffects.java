package core.amazake.systems.corerpg.progression;

import core.amazake.systems.corerpg.profile.PlayerProfile;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Эффекты и права из бонусов класса и профессии. Аспектные бонусы считает вкладчик аспектов, здесь — то, что живёт на
 * самом игроке: постоянные эффекты (переустанавливаются при входе, респавне и после молока) и PermissionAttachment.
 * Снимается только то, что наложила система. Все методы с игроком — в его потоке.
 */
final class BonusEffects {

    /** Что система наложила игроку: постоянные эффекты (тип → уровень) и вложение прав. */
    private record Applied(Map<PotionEffectType, Integer> effects, PermissionAttachment attachment) {
    }

    private final Plugin plugin;
    private final Map<UUID, Applied> applied = new ConcurrentHashMap<>();
    private final Set<String> warnedEffects = ConcurrentHashMap.newKeySet();

    BonusEffects(final Plugin plugin) {
        this.plugin = plugin;
    }

    /** Ключ эффекта → тип или null. Любой поток (реестр неизменяем). */
    static PotionEffectType resolve(final String key) {
        final NamespacedKey nk = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
        return nk == null ? null : Registry.MOB_EFFECT.get(nk);
    }

    /** Предупредить о неизвестных эффектах в каталоге (при загрузке настроек). */
    void validate(final ClassCatalog catalog, final Consumer<String> warn) {
        this.warnedEffects.clear();
        for (final ClassCatalog.RpgClass c : catalog.classes().values()) {
            check(c.bonuses(), "classes." + c.key(), warn);
        }
        for (final ClassCatalog.RpgProfession p : catalog.professions().values()) {
            check(p.bonuses(), "professions." + p.key(), warn);
        }
    }

    private void check(final List<Bonus> bonuses, final String where, final Consumer<String> warn) {
        for (final Bonus b : bonuses) {
            if (b instanceof Bonus.EffectBonus e && resolve(e.effectKey()) == null && this.warnedEffects.add(e.effectKey())) {
                warn.accept(where + ".bonuses: нет эффекта «" + e.effectKey() + "», бонус не действует");
            }
        }
    }

    /**
     * Привести эффекты и права игрока к его классу и профессии. {@code withTemporary} — наложить и временные эффекты
     * (только при выборе класса или профессии). Поток игрока.
     */
    void apply(final Player player, final PlayerProfile profile, final ClassCatalog catalog, final boolean withTemporary) {
        this.remove(player);
        final List<Bonus> bonuses = catalog.bonuses(profile.classId(), profile.professionId());
        final Map<PotionEffectType, Integer> effects = new HashMap<>();
        PermissionAttachment attachment = null;
        for (final Bonus bonus : bonuses) {
            if (bonus instanceof Bonus.EffectBonus e) {
                final PotionEffectType type = resolve(e.effectKey());
                if (type == null) {
                    continue;
                }
                if (e.permanent()) {
                    final Integer prev = effects.get(type);
                    if (prev == null || prev < e.amplifier()) {
                        effects.put(type, e.amplifier());
                    }
                } else if (withTemporary) {
                    player.addPotionEffect(new PotionEffect(type, e.durationSeconds() * 20, e.amplifier(), true, false, true));
                }
            } else if (bonus instanceof Bonus.PermissionBonus p) {
                if (attachment == null) {
                    attachment = player.addAttachment(this.plugin);
                }
                attachment.setPermission(p.node(), true);
            }
        }
        if (effects.isEmpty() && attachment == null) {
            return;
        }
        this.applied.put(player.getUniqueId(), new Applied(Map.copyOf(effects), attachment));
        this.reapplyPermanent(player);
        if (attachment != null) {
            player.recalculatePermissions();
        }
    }

    /** Вернуть постоянные эффекты (после молока, тотема, смерти). Поток игрока. */
    void reapplyPermanent(final Player player) {
        final Applied a = this.applied.get(player.getUniqueId());
        if (a == null) {
            return;
        }
        a.effects().forEach((type, amplifier) -> {
            final PotionEffect current = player.getPotionEffect(type);
            if (current != null && current.isInfinite() && current.getAmplifier() == amplifier) {
                return;
            }
            if (current != null && current.getAmplifier() > amplifier) {
                return; // сильнее нашего — например, зелье; вернём своё после молока или смерти
            }
            player.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, amplifier, true, false, true));
        });
    }

    /** Есть ли у игрока постоянные эффекты системы. Любой поток. */
    boolean hasPermanent(final UUID uuid) {
        final Applied a = this.applied.get(uuid);
        return a != null && !a.effects().isEmpty();
    }

    /** Снять наложенное системой: только бесконечные эффекты нашего уровня и наше вложение прав. Поток игрока. */
    void remove(final Player player) {
        final Applied a = this.applied.remove(player.getUniqueId());
        if (a == null) {
            return;
        }
        a.effects().forEach((type, amplifier) -> {
            final PotionEffect current = player.getPotionEffect(type);
            if (current != null && current.isInfinite() && current.getAmplifier() == amplifier) {
                player.removePotionEffect(type);
            }
        });
        if (a.attachment() != null) {
            try {
                a.attachment().remove();
            } catch (final IllegalArgumentException ignored) {
                // вложение уже снято (игрок вышел)
            }
            player.recalculatePermissions();
        }
    }

    /** Игрок вышел: забыть без обращения к игроку. */
    void forget(final UUID uuid) {
        this.applied.remove(uuid);
    }
}
