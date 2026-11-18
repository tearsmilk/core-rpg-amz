package core.amazake.systems.coremobs.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Evoker;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.Llama;
import org.bukkit.entity.LlamaSpit;
import org.bukkit.entity.MagmaCube;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.PiglinBrute;
import org.bukkit.entity.Player;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Slime;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Trident;
import org.bukkit.entity.Vex;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Witch;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zoglin;
import org.bukkit.entity.Zombie;
import org.bukkit.projectiles.BlockProjectileSource;
import org.junit.jupiter.api.Test;

/** Кто на самом деле атакует и когда атака запрещена. Сущности — java.lang.reflect.Proxy, без сервера. */
class InfightingTest {

    private static final Predicate<Entity> NONE = e -> false;
    private static final Infighting HOSTILE = Infighting.DEFAULTS;

    /** Сущность-заглушка: геттеры из {@code values} по имени метода, остальное — null/false/0. */
    @SuppressWarnings("unchecked")
    private static <T> T fake(final Class<T> type, final Object... kv) {
        final Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            values.put((String) kv[i], kv[i + 1]);
        }
        final Object[] self = new Object[1];
        self[0] = Proxy.newProxyInstance(InfightingTest.class.getClassLoader(), new Class<?>[] {type}, (p, m, args) -> {
            switch (m.getName()) {
                case "equals":
                    return p == args[0];
                case "hashCode":
                    return System.identityHashCode(p);
                case "toString":
                    return type.getSimpleName();
                default:
                    break;
            }
            if (values.containsKey(m.getName())) {
                return values.get(m.getName());
            }
            final Class<?> r = m.getReturnType();
            if (r == boolean.class) {
                return false;
            }
            if (r.isPrimitive() && r != void.class) {
                return r == double.class ? 0.0 : r == float.class ? 0f : r == long.class ? 0L : 0;
            }
            return null;
        });
        return (T) self[0];
    }

    private static <T extends Entity> T shot(final Class<T> type, final Object shooter) {
        return fake(type, "getShooter", shooter);
    }

    @Test
    void meleeAttackerIsItself() {
        final Zombie z = fake(Zombie.class);
        assertSame(z, Infighting.resolve(z));
        final Creeper c = fake(Creeper.class);
        assertSame(c, Infighting.resolve(c));       // взрыв крипера: damager = крипер
        final Vex v = fake(Vex.class);
        assertSame(v, Infighting.resolve(v));
        assertNull(Infighting.resolve(null));
    }

    @Test
    void projectilesResolveToShooter() {
        final Skeleton sk = fake(Skeleton.class);
        assertSame(sk, Infighting.resolve(shot(Arrow.class, sk)));
        final Ghast g = fake(Ghast.class);
        assertSame(g, Infighting.resolve(shot(LargeFireball.class, g)));
        final Blaze b = fake(Blaze.class);
        assertSame(b, Infighting.resolve(shot(SmallFireball.class, b)));
        final Zombie drowned = fake(Zombie.class);
        assertSame(drowned, Infighting.resolve(shot(Trident.class, drowned)));
        final Entity shulker = fake(org.bukkit.entity.Shulker.class);
        assertSame(shulker, Infighting.resolve(shot(ShulkerBullet.class, shulker)));
        final Entity wither = fake(org.bukkit.entity.Wither.class);
        assertSame(wither, Infighting.resolve(shot(WitherSkull.class, wither)));
        final Witch w = fake(Witch.class);
        assertSame(w, Infighting.resolve(shot(ThrownPotion.class, w)));
        final Llama l = fake(Llama.class);
        assertSame(l, Infighting.resolve(shot(LlamaSpit.class, l)));
    }

    @Test
    void unknownOrBlockSourceIsNull() {
        assertNull(Infighting.resolve(shot(Arrow.class, null)));
        assertNull(Infighting.resolve(shot(Arrow.class, fake(BlockProjectileSource.class))));   // раздатчик
        assertNull(Infighting.resolve(fake(TNTPrimed.class)));
    }

    @Test
    void indirectSources() {
        final Witch w = fake(Witch.class);
        assertSame(w, Infighting.resolve(fake(AreaEffectCloud.class, "getSource", w)));
        final Evoker ev = fake(Evoker.class);
        assertSame(ev, Infighting.resolve(fake(EvokerFangs.class, "getOwner", ev)));
        final Skeleton sk = fake(Skeleton.class);
        assertSame(sk, Infighting.resolve(fake(TNTPrimed.class, "getSource", sk)));
        // динамит, подожжённый стрелой: TNT → стрела → скелет
        assertSame(sk, Infighting.resolve(fake(TNTPrimed.class, "getSource", shot(Arrow.class, sk))));
    }

    @Test
    void longChainStops() {
        // цепочку длиннее MAX_DEPTH (динамит от динамита от …) до конца не разворачиваем — без зацикливания
        Entity e = fake(Skeleton.class);
        for (int i = 0; i < 10; i++) {
            e = fake(TNTPrimed.class, "getSource", e);
        }
        assertTrue(Infighting.resolve(e) instanceof TNTPrimed);
    }

    @Test
    void hostileVsHostileBlocked() {
        final Entity[] hostile = {fake(Zombie.class), fake(Skeleton.class), fake(Slime.class), fake(MagmaCube.class),
            fake(Phantom.class), fake(Ghast.class), fake(Blaze.class), fake(Zoglin.class), fake(PiglinBrute.class),
            fake(WitherSkeleton.class), fake(Witch.class), fake(Evoker.class), fake(Vex.class), fake(Creeper.class)};
        for (final Entity a : hostile) {
            for (final Entity v : hostile) {
                assertEquals(a != v, HOSTILE.blocked(a, v, NONE), a + " -> " + v);
            }
            assertTrue(HOSTILE.protects(a, NONE));
        }
    }

    @Test
    void othersUnaffected() {
        final Skeleton sk = fake(Skeleton.class);
        final Entity[] others = {fake(Player.class), fake(Wolf.class), fake(IronGolem.class), fake(Villager.class),
            fake(Llama.class)};
        for (final Entity o : others) {
            assertFalse(HOSTILE.blocked(sk, o, NONE), "skeleton -> " + o);
            assertFalse(HOSTILE.blocked(o, sk, NONE), o + " -> skeleton");
            assertFalse(HOSTILE.protects(o, NONE));
        }
        assertFalse(HOSTILE.blocked(null, sk, NONE));
        assertFalse(HOSTILE.blocked(sk, null, NONE));
    }

    @Test
    void enabledMeansVanilla() {
        final Infighting on = new Infighting(true, Infighting.Scope.HOSTILE, Set.of());
        assertFalse(on.active());
        assertFalse(on.blocked(fake(Zombie.class), fake(Skeleton.class), NONE));
        assertFalse(on.protects(fake(Zombie.class), NONE));
    }

    @Test
    void coreMobsScope() {
        final Infighting core = new Infighting(false, Infighting.Scope.CORE_MOBS, Set.of());
        final Skeleton ours1 = fake(Skeleton.class);
        final Zombie ours2 = fake(Zombie.class);
        final Zombie vanilla = fake(Zombie.class);
        final Predicate<Entity> isCore = e -> e == ours1 || e == ours2;
        assertTrue(core.blocked(ours1, ours2, isCore));
        assertFalse(core.blocked(ours1, vanilla, isCore));
        assertFalse(core.blocked(vanilla, ours2, isCore));
        assertFalse(core.protects(vanilla, isCore));
    }

    @Test
    void config() throws Exception {
        final List<String> warns = new ArrayList<>();
        assertEquals(Infighting.DEFAULTS, Infighting.from(null, warns::add));
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("infighting:\n  enabled: true\n  scope: core-mobs\n");
        final Infighting parsed = Infighting.from(y.getConfigurationSection("infighting"), warns::add);
        assertTrue(parsed.enabled());
        assertEquals(Infighting.Scope.CORE_MOBS, parsed.scope());
        y.loadFromString("infighting:\n  scope: nope\n");
        assertEquals(Infighting.Scope.HOSTILE, Infighting.from(y.getConfigurationSection("infighting"), warns::add).scope());
        assertEquals(1, warns.size());
        assertNull(Infighting.parseScope("x"));
        assertEquals(Infighting.Scope.CORE_MOBS, Infighting.parseScope("CORE_MOBS"));
    }

    @Test
    void nonHostileCoreMobWarns() throws Exception {
        final List<String> w = new ArrayList<>();
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("type: WOLF\n");
        MobCatalog.parseMob("guard_wolf", y, w::add);
        assertEquals(1, w.size(), w.toString());
        w.clear();
        y.loadFromString("type: ZOMBIE\n");
        MobCatalog.parseMob("z", y, w::add);
        assertTrue(w.isEmpty(), w.toString());
    }

    /** Все типы в mobs.yml (ресурсы и продакшен) враждебные — иначе scope core-mobs их не защищает. */
    @Test
    void shippedMobsAreEnemies() {
        for (final String path : new String[] {"src/main/resources/mobs.yml", "deploy/mobs.prod.yml"}) {
            final ConfigurationSection mobs = YamlConfiguration.loadConfiguration(new File(path))
                .getConfigurationSection("mobs");
            for (final String id : mobs.getKeys(false)) {
                final EntityType t = EntityType.valueOf(mobs.getString(id + ".type"));
                assertTrue(Enemy.class.isAssignableFrom(t.getEntityClass()), path + " " + id + ": " + t);
            }
        }
    }
}
