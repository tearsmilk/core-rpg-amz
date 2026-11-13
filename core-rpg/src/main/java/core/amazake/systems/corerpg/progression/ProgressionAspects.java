package core.amazake.systems.corerpg.progression;

import core.amazake.systems.corerpg.aspect.Aspect;
import core.amazake.systems.corerpg.aspect.AspectContributor;
import core.amazake.systems.corerpg.aspect.AspectSnapshot;
import core.amazake.systems.corerpg.profile.PlayerProfile;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

/** Вкладчики аспектов модуля: бонусы класса (0), формулы параметров (100), закрытые аспекты профессии (200). */
final class ProgressionAspects {

    private ProgressionAspects() {
    }

    /** order 0: аспектные бонусы класса и профессии. */
    static AspectContributor bonuses(final Supplier<ClassCatalog> catalog) {
        return new AspectContributor() {
            @Override
            public void contribute(final Player player, final PlayerProfile profile, final AspectSnapshot.Builder out) {
                for (final Bonus bonus : catalog.get().bonuses(profile.classId(), profile.professionId())) {
                    if (bonus instanceof Bonus.AspectBonus a) {
                        out.add(a.key(), a.value());
                    }
                }
            }

            @Override
            public int order() {
                return 0;
            }
        };
    }

    /** order 100: очки параметров (вложенные + stat_* от печатей и заточки) → аспекты. */
    static AspectContributor stats(final Supplier<ProgressionSettings> settings) {
        return new AspectContributor() {
            @Override
            public void contribute(final Player player, final PlayerProfile profile, final AspectSnapshot.Builder out) {
                final StatFormulas.Bonuses b = StatFormulas.total(settings.get().stats(), profile.level(),
                    profile.primary() + out.get(Aspect.STAT_PRIMARY),
                    profile.agility() + out.get(Aspect.STAT_AGILITY),
                    profile.stamina() + out.get(Aspect.STAT_STAMINA),
                    profile.luck() + out.get(Aspect.STAT_LUCK));
                out.add(Aspect.ATTACK_DAMAGE, b.attackDamage());
                out.add(Aspect.ACCURACY, b.accuracy());
                out.add(Aspect.MAX_HEALTH, b.maxHealth());
                out.add(Aspect.ATTACK_SPEED, b.attackSpeed());
                out.add(Aspect.EVASION, b.evasion());
                out.add(Aspect.CRIT_CHANCE, b.critChance());
                out.add(Aspect.CRIT_DAMAGE, b.critDamage());
            }

            @Override
            public int order() {
                return 100;
            }
        };
    }

    /** order 200: закрытый аспект профессии не может быть больше 0. */
    static AspectContributor locked(final Supplier<ClassCatalog> catalog) {
        return new AspectContributor() {
            @Override
            public void contribute(final Player player, final PlayerProfile profile, final AspectSnapshot.Builder out) {
                final ClassCatalog.RpgProfession p = catalog.get().profession(profile.professionId());
                if (p == null) {
                    return;
                }
                for (final Aspect aspect : p.lockedAspects()) {
                    if (out.get(aspect) > 0.0) {
                        out.clear(aspect);
                    }
                }
            }

            @Override
            public int order() {
                return 200;
            }
        };
    }
}
