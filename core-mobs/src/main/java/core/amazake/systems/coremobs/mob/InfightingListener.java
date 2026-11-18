package core.amazake.systems.coremobs.mob;

import core.amazake.systems.coremobs.Settings;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.potion.PotionType;

/**
 * Мобы не трогают друг друга (config.yml, {@code infighting}): не выбирают целью, не наносят урон (и отдачу — отменённый
 * урон её не даёт, взрывы тоже), не поджигают, не накладывают вредные зелья и облака. События — в потоке региона жертвы
 * или нападающего; у других сущностей только instanceof и чтение метки core-mobs. Меняем только своё: событие и
 * нападающего, если он принадлежит текущему потоку ({@link Bukkit#isOwnedByCurrentRegion(Entity)}).
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
            forget(attacker, victim);
            return;
        }
        // запасной путь: источник урона знает виновника, когда сущность-посредник его потеряла
        final Entity causing = event.getDamageSource().getCausingEntity();
        if (causing != null && causing != attacker && rules.blocked(Infighting.resolve(causing), victim, CORE_MOB)) {
            event.setCancelled(true);
        }
    }

    /**
     * Цель, выбранная до запрета (до запуска сервера или до {@code /mobs reload} с enabled true → false), сама не
     * сбросится — моб гонялся бы за «своим» вечно, нанося пустые удары. Сбрасываем, если нападающий в нашем потоке.
     */
    private static void forget(final Entity attacker, final Entity victim) {
        if (attacker instanceof final Mob mob && Bukkit.isOwnedByCurrentRegion(attacker) && mob.getTarget() == victim) {
            mob.setTarget(null);
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

    /**
     * Облако зелья (оседающее зелье ведьмы, облако взорвавшегося крипера с эффектами): вредные эффекты «своим» не
     * накладываем — убираем их из списка задетых. Урон облака (мгновенный урон) отменяет ещё и onDamage.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCloud(final AreaEffectCloudApplyEvent event) {
        final Infighting rules = this.settings.get().infighting();
        if (!rules.active()) {
            return;
        }
        final AreaEffectCloud cloud = event.getEntity();
        final Entity source = Infighting.resolve(cloud);
        if (!rules.protects(source, CORE_MOB) || !harmful(cloud)) {
            return;
        }
        final List<LivingEntity> affected = event.getAffectedEntities();
        for (final Iterator<LivingEntity> it = affected.iterator(); it.hasNext(); ) {
            if (rules.blocked(source, it.next(), CORE_MOB)) {
                it.remove();
            }
        }
    }

    private static boolean harmful(final AreaEffectCloud cloud) {
        final PotionType base = cloud.getBasePotionType();
        if (base != null) {
            for (final PotionEffect effect : base.getPotionEffects()) {
                if (effect.getType().getCategory() == PotionEffectTypeCategory.HARMFUL) {
                    return true;
                }
            }
        }
        if (cloud.hasCustomEffects()) {
            for (final PotionEffect effect : cloud.getCustomEffects()) {
                if (effect.getType().getCategory() == PotionEffectTypeCategory.HARMFUL) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Есть ли во всплеске вредный эффект. Всплеск гасим целиком: смешанное зелье не действует на «своих» вовсе. */
    private static boolean harmful(final ThrownPotion potion) {
        for (final PotionEffect effect : potion.getEffects()) {
            if (effect.getType().getCategory() == PotionEffectTypeCategory.HARMFUL) {
                return true;
            }
        }
        return false;
    }
}
