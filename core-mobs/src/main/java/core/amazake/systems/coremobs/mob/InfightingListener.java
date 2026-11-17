package core.amazake.systems.coremobs.mob;

import core.amazake.systems.coremobs.Settings;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectTypeCategory;

/**
 * Мобы не трогают друг друга (config.yml, {@code infighting}): не выбирают целью, не наносят урон (и отдачу — отменённый
 * урон её не даёт, взрывы тоже), не поджигают, не накладывают вредные зелья. События — в потоке региона жертвы или
 * нападающего; у других сущностей только instanceof и чтение метки core-mobs.
 */
public final class InfightingListener implements Listener {

    private static final Predicate<Entity> CORE_MOB = e -> MobService.mobId(e) != null;

    private final Supplier<Settings> settings;

    public InfightingListener(final Supplier<Settings> settings) {
        this.settings = settings;
    }

    /** Скелет после стрелы «своего», зоглин, иссушитель, пиглин против скелета-иссушителя, разбойники и т. д. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(final EntityTargetEvent event) {
        final Entity target = event.getTarget();
        if (target == null) {
            return;
        }
        final Infighting rules = this.settings.get().infighting();
        if (rules.blocked(event.getEntity(), target, CORE_MOB)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        final Infighting rules = this.settings.get().infighting();
        final Entity victim = event.getEntity();
        if (!rules.protects(victim, CORE_MOB)) {
            return;
        }
        final Entity attacker = Infighting.resolve(event.getDamager());
        if (rules.blocked(attacker, victim, CORE_MOB)) {
            event.setCancelled(true);
            return;
        }
        // запасной путь: источник урона знает виновника, когда сущность-посредник его потеряла
        final Entity causing = event.getDamageSource().getCausingEntity();
        if (causing != null && causing != attacker && rules.blocked(Infighting.resolve(causing), victim, CORE_MOB)) {
            event.setCancelled(true);
        }
    }

    /** Огненные шары гаста и блейза, горящие стрелы, удар горящим мечом. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombust(final EntityCombustByEntityEvent event) {
        final Infighting rules = this.settings.get().infighting();
        final Entity victim = event.getEntity();
        if (rules.protects(victim, CORE_MOB) && rules.blocked(Infighting.resolve(event.getCombuster()), victim, CORE_MOB)) {
            event.setCancelled(true);
        }
    }

    /** Вредное зелье ведьмы задело моба: эффект не накладываем (мгновенный урон отменяет onDamage). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(final PotionSplashEvent event) {
        final Infighting rules = this.settings.get().infighting();
        if (!rules.active()) {
            return;
        }
        final ThrownPotion potion = event.getPotion();
        final Entity thrower = Infighting.resolve(potion);
        if (!rules.protects(thrower, CORE_MOB) || !harmful(potion)) {
            return;
        }
        for (final LivingEntity victim : event.getAffectedEntities()) {
            if (rules.blocked(thrower, victim, CORE_MOB)) {
                event.setIntensity(victim, 0.0);
            }
        }
    }

    private static boolean harmful(final ThrownPotion potion) {
        for (final PotionEffect effect : potion.getEffects()) {
            if (effect.getType().getCategory() == PotionEffectTypeCategory.HARMFUL) {
                return true;
            }
        }
        return false;
    }
}
