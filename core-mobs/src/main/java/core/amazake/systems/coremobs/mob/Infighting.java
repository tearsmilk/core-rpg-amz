package core.amazake.systems.coremobs.mob;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;

/**
 * Драки мобов между собой (config.yml, раздел {@code infighting}). Неизменяемый снимок настроек плюс чистая логика:
 * кто на самом деле атакует ({@link #resolve}) и надо ли запретить атаку ({@link #blocked}). Без аллокаций: на
 * горячем пути (каждый удар сущности по сущности) только instanceof и пара полей.
 *
 * @param enabled true — мобы дерутся как в ванилле; false — драки запрещены в пределах {@code scope}
 * @param scope   кого защищать: всех враждебных ({@link Scope#HOSTILE}) или только мобов core-mobs
 * @param allow   типы, которым драки разрешены (и как нападающим, и как целям)
 */
public record Infighting(boolean enabled, Scope scope, Set<EntityType> allow) {

    /** Кто считается «своим» и не трогает других «своих». */
    public enum Scope {
        /** Все враждебные мобы: {@link Enemy} — монстры, слизни, фантомы, гасты, шалкеры, хоглины, дракон. */
        HOSTILE,
        /** Только мобы core-mobs (с меткой из mobs.yml) между собой; ванильные дерутся как обычно. */
        CORE_MOBS
    }

    /** Сколько звеньев «снаряд → стрелок → …» разворачивать (динамит, подожжённый стрелой, и т. п.). */
    private static final int MAX_DEPTH = 4;

    public static final Infighting DEFAULTS = new Infighting(false, Scope.HOSTILE, Set.of());

    public Infighting {
        allow = allow.isEmpty() ? Set.of() : Set.copyOf(allow);
    }

    /** Есть ли что запрещать — первая и самая дешёвая проверка в каждом обработчике. */
    public boolean active() {
        return !this.enabled;
    }

    /**
     * Запретить атаку {@code attacker} → {@code victim}: драки выключены, оба «свои» по {@code scope}, это не одна и
     * та же сущность и ни один тип не в {@code allow}. {@code attacker} — уже настоящий нападающий ({@link #resolve}).
     */
    public boolean blocked(final Entity attacker, final Entity victim, final Predicate<Entity> coreMob) {
        if (this.enabled || attacker == null || victim == null || attacker == victim) {
            return false;
        }
        if (!this.member(victim, coreMob) || !this.member(attacker, coreMob)) {
            return false;
        }
        return this.allow.isEmpty() || !this.allow.contains(attacker.getType()) && !this.allow.contains(victim.getType());
    }

    /** Защищён ли {@code e} вообще — для ранних выходов до разворачивания нападающего. */
    public boolean protects(final Entity e, final Predicate<Entity> coreMob) {
        return !this.enabled && e != null && this.member(e, coreMob);
    }

    private boolean member(final Entity e, final Predicate<Entity> coreMob) {
        // core-mobs: сначала instanceof (все типы mobs.yml враждебные), PDC читаем только у враждебных
        return e instanceof Enemy && (this.scope == Scope.HOSTILE || coreMob.test(e));
    }

    /**
     * Настоящий нападающий за сущностью, нанёсшей урон или поджёгшей: стрелок снаряда (стрелы, трезубцы, огненные шары
     * гаста и блейза, пули шалкера, черепа иссушителя, зелья, плевок ламы), источник облака зелья, поджигатель динамита,
     * хозяин клыков заклинателя. Крипер, вредина, зоглин и прочие бьют сами — возвращаются как есть. null — источник
     * не сущность (раздатчик) или неизвестен.
     */
    public static Entity resolve(final Entity damager) {
        Entity e = damager;
        for (int i = 0; i < MAX_DEPTH && e != null; i++) {
            final Entity next;
            if (e instanceof final Projectile p) {
                next = p.getShooter() instanceof final Entity shooter ? shooter : null;
            } else if (e instanceof final TNTPrimed tnt) {
                next = tnt.getSource();
            } else if (e instanceof final AreaEffectCloud cloud) {
                next = cloud.getSource() instanceof final Entity source ? source : null;
            } else if (e instanceof final EvokerFangs fangs) {
                next = fangs.getOwner();
            } else {
                return e;
            }
            if (next == null) {
                return null;
            }
            e = next;
        }
        return e;
    }

    /** Раздел {@code infighting} из config.yml; нет раздела — {@link #DEFAULTS}. */
    public static Infighting from(final ConfigurationSection c, final Consumer<String> warn) {
        if (c == null) {
            return DEFAULTS;
        }
        final boolean enabled = c.getBoolean("enabled", DEFAULTS.enabled());
        final String rawScope = c.getString("scope", "hostile");
        Scope scope = parseScope(rawScope);
        if (scope == null) {
            warn.accept("infighting.scope: «" + rawScope + "» — нужно hostile или core-mobs, беру hostile");
            scope = Scope.HOSTILE;
        }
        final Set<EntityType> allow = EnumSet.noneOf(EntityType.class);
        for (final String raw : c.getStringList("allow")) {
            try {
                allow.add(EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
            } catch (final IllegalArgumentException e) {
                warn.accept("infighting.allow: «" + raw + "» — нет такого типа (ZOMBIE, WITHER, ...)");
            }
        }
        return new Infighting(enabled, scope, allow);
    }

    static Scope parseScope(final String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "hostile", "all" -> Scope.HOSTILE;
            case "core-mobs", "coremobs" -> Scope.CORE_MOBS;
            default -> null;
        };
    }
}
