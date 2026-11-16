package core.amazake.systems.corepython.host;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Временные изменения игроков, сделанные скриптом через API: модификаторы атрибутов (transient — в файл игрока не
 * пишутся) и эффекты зелий. При выгрузке скрипта всё это снимается Java-кодом в потоке каждого игрока — даже если
 * Python-код уже не может выполниться.
 */
final class Tracker {

    private record Mod(Attribute attribute, NamespacedKey key) {
    }

    private final Map<UUID, Set<Mod>> mods = new ConcurrentHashMap<>();
    private final Map<UUID, Map<PotionEffectType, PotionEffect>> effects = new ConcurrentHashMap<>();

    /** В потоке игрока. Модификатор с тем же ключом заменяется. */
    void addModifier(final Player p, final Attribute attribute, final AttributeModifier modifier) {
        final AttributeInstance inst = p.getAttribute(attribute);
        if (inst == null) {
            throw new IllegalArgumentException("у игрока нет атрибута " + attribute.getKey());
        }
        inst.removeModifier(modifier.getKey());
        inst.addTransientModifier(modifier);
        this.mods.computeIfAbsent(p.getUniqueId(), k -> ConcurrentHashMap.newKeySet()).add(new Mod(attribute, modifier.getKey()));
    }

    /** В потоке игрока. */
    boolean removeModifier(final Player p, final Attribute attribute, final NamespacedKey key) {
        final Set<Mod> set = this.mods.get(p.getUniqueId());
        if (set != null) {
            set.remove(new Mod(attribute, key));
        }
        final AttributeInstance inst = p.getAttribute(attribute);
        if (inst == null || inst.getModifier(key) == null) {
            return false;
        }
        inst.removeModifier(key);
        return true;
    }

    /** В потоке игрока. */
    void addEffect(final Player p, final PotionEffect effect) {
        p.addPotionEffect(effect);
        this.effects.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>()).put(effect.getType(), effect);
    }

    /** В потоке игрока: снимает эффект, только если сейчас на игроке наш (та же сила, длительность не больше нашей). */
    boolean removeEffect(final Player p, final PotionEffectType type) {
        final Map<PotionEffectType, PotionEffect> mine = this.effects.get(p.getUniqueId());
        final PotionEffect ours = mine == null ? null : mine.remove(type);
        if (ours == null) {
            return false;
        }
        final PotionEffect now = p.getPotionEffect(type);
        if (now != null && now.getAmplifier() == ours.getAmplifier()
            && (ours.isInfinite() ? now.isInfinite() : !now.isInfinite() && now.getDuration() <= ours.getDuration())) {
            p.removePotionEffect(type);
            return true;
        }
        return false;
    }

    /** Игрок вышел: transient-модификаторы и так пропадут. */
    void forget(final UUID id) {
        this.mods.remove(id);
        this.effects.remove(id);
    }

    int players() {
        final Set<UUID> ids = new java.util.HashSet<>(this.mods.keySet());
        ids.addAll(this.effects.keySet());
        return ids.size();
    }

    /** Снять всё у всех (каждому — в его потоке). */
    void cleanup(final Platform platform) {
        final Map<UUID, Set<Mod>> m = new HashMap<>(this.mods);
        final Map<UUID, Map<PotionEffectType, PotionEffect>> e = new HashMap<>(this.effects);
        this.mods.clear();
        this.effects.clear();
        final Set<UUID> ids = new java.util.HashSet<>(m.keySet());
        ids.addAll(e.keySet());
        for (final UUID id : ids) {
            final Player p = platform.player(id);
            if (p == null) {
                continue;
            }
            final Set<Mod> mods = m.getOrDefault(id, Set.of());
            final List<PotionEffect> effs = List.copyOf(e.getOrDefault(id, Map.of()).values());
            platform.runOnceForPlayer(p, () -> {
                for (final Mod mod : mods) {
                    final AttributeInstance inst = p.getAttribute(mod.attribute());
                    if (inst != null) {
                        inst.removeModifier(mod.key());
                    }
                }
                for (final PotionEffect ours : effs) {
                    final PotionEffect now = p.getPotionEffect(ours.getType());
                    if (now != null && now.getAmplifier() == ours.getAmplifier()
                        && (ours.isInfinite() || !now.isInfinite() && now.getDuration() <= ours.getDuration())) {
                        p.removePotionEffect(ours.getType());
                    }
                }
            });
        }
    }
}
