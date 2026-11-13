package core.amazake.systems.corerpg.combat;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corerpg.CoreRpgSystem;
import core.amazake.systems.corerpg.aspect.Aspect;
import core.amazake.systems.corerpg.aspect.AspectSnapshot;
import core.amazake.systems.corerpg.combat.CombatSettings.ResistanceTarget;
import core.amazake.systems.corerpg.sharpen.SharpenModule;
import core.amazake.systems.corerpg.util.Gear;
import core.amazake.systems.corerpg.util.Keys;
import core.amazake.systems.corerpg.util.Text;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Боевой расчёт. Порядок обработчиков (каждый шаг применяется ровно один раз):
 * <ul>
 *   <li>{@code EntityDamageByEntityEvent} HIGH — уворот цели-игрока (шаг 7), затем усиление атаки игрока (шаги 1–4);</li>
 *   <li>{@code EntityDamageEvent} HIGHEST — защита игрока: заточка брони (8) и сопротивление (9);</li>
 *   <li>{@code EntityDamageByEntityEvent} MONITOR — статусы (5) и вампиризм (6) по итоговому урону;</li>
 *   <li>{@code EntityDeathEvent} MONITOR — здоровье за убийство (6).</li>
 * </ul>
 *
 * <p>Потоки: события урона идут в потоке региона цели. Цель меняется здесь же; атакующего (лечение, сообщения)
 * трогаем только через {@link SystemContext#runOnceForPlayer}. Снаряд несёт снимок атакующего на момент выстрела в
 * своём PDC, поэтому при попадании дальний стрелок не читается вовсе. В ближнем бою атакующий стоит рядом с целью
 * (тот же регион), его предмет в руке и здоровье читаются напрямую.</p>
 */
final class CombatListener implements Listener {

    static final String PASSIVE_RAGE = "rage";
    static final String PASSIVE_HEADSHOT = "headshot";
    static final String PASSIVE_BACKSTAB = "backstab";
    static final String PASSIVE_DUAL_WIELD = "dual_wield";

    private final CoreRpgSystem system;
    private final CombatModule module;

    CombatListener(final CoreRpgSystem system, final CombatModule module) {
        this.system = system;
        this.module = module;
    }

    // ------------------------------------------------------------------ снимок при выстреле

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(final EntityShootBowEvent event) {
        if (event.getEntity() instanceof final Player shooter && event.getProjectile() instanceof final Projectile projectile) {
            this.snapshot(shooter, projectile, event.getBow());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(final ProjectileLaunchEvent event) {
        // Луки и арбалеты уже сняты в EntityShootBowEvent; здесь — брошенный трезубец.
        if (event.getEntity() instanceof final Trident trident && trident.getShooter() instanceof final Player shooter) {
            this.snapshot(shooter, trident, trident.getItemStack());
        }
    }

    /** Поток стрелка: снаряд только что появился рядом с ним. */
    private void snapshot(final Player shooter, final Projectile projectile, final ItemStack weapon) {
        final Gear.Kind kind = Gear.kind(weapon);
        if (kind == null) {
            return;
        }
        final Keys keys = this.system.keys();
        final PersistentDataContainer pdc = projectile.getPersistentDataContainer();
        pdc.set(keys.projectileWeaponKind, PersistentDataType.STRING, kind.name());
        final SharpenModule sharpen = this.system.sharpen();
        final double bonus = kind.attacks() && sharpen != null ? sharpen.bonusPercent(weapon) : 0.0;
        if (bonus > 0.0) {
            pdc.set(keys.projectileSharpen, PersistentDataType.DOUBLE, bonus);
        }
        // Ярость тоже по здоровью на момент выстрела: при попадании стрелок может быть в другом регионе.
        final CombatSettings.Rage rage = this.module.settings().passives().rage();
        if (rage.enabled() && this.passives(shooter).contains(PASSIVE_RAGE)) {
            final double rageBonus = CombatMath.rageBonusPercent(shooter.getHealth(), maxHealth(shooter),
                rage.percentPerStep(), rage.stepPercent(), rage.maxPercent());
            if (rageBonus > 0.0) {
                pdc.set(this.module.projectileRageKey(), PersistentDataType.DOUBLE, rageBonus);
            }
        }
    }

    // ------------------------------------------------------------------ атака (HIGH)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(final EntityDamageByEntityEvent event) {
        final CombatModule.Loaded loaded = this.module.loaded();
        final CombatSettings s = loaded.settings();
        final Entity victim = event.getEntity();
        final Entity damager = event.getDamager();
        final Projectile projectile = damager instanceof final Projectile p ? p : null;
        final Player attacker = attackingPlayer(damager);
        final DamageCause cause = event.getCause();

        // 7. Уворот цели-игрока (против любого атакующего; у мобов попадание 0).
        if (victim instanceof final Player defender && s.evasion().enabled() && s.evasion().causes().contains(cause)
            && attacker != defender) {
            final double evasion = this.aspects(defender).get(Aspect.EVASION);
            final double accuracy = attacker == null ? 0.0 : this.aspects(attacker).get(Aspect.ACCURACY);
            final double chance = CombatMath.evasionChance(evasion, accuracy, s.evasion().cap());
            if (chance > 0.0 && ThreadLocalRandom.current().nextDouble() < chance) {
                event.setCancelled(true);
                this.dodgeFeedback(s, defender, attacker);
                return;
            }
        }

        if (attacker == null || attacker == victim || !s.attack().causes().contains(cause)
            || !(victim instanceof final LivingEntity target)) {
            return;
        }
        final boolean melee = projectile == null;
        if (!melee && !this.fromWeapon(projectile)) {
            return; // снежки, яйца и прочее без снимка оружия не усиливаются
        }
        final AspectSnapshot a = this.aspects(attacker);
        final Set<String> passives = this.passives(attacker);
        final CombatSettings.Passives ps = s.passives();
        final double vanillaDamage = event.getDamage();
        double damage = vanillaDamage;

        // 1. Заточка оружия: ближний бой — основная рука, снаряд — снимок при выстреле.
        if (s.attack().sharpenWeapon()) {
            final double bonus;
            if (melee) {
                final ItemStack weapon = attacker.getInventory().getItemInMainHand();
                final Gear.Kind kind = Gear.kind(weapon);
                final SharpenModule sharpen = this.system.sharpen();
                bonus = kind != null && kind.attacks() && sharpen != null ? sharpen.bonusPercent(weapon) : 0.0;
            } else {
                bonus = readDouble(projectile.getPersistentDataContainer(), this.system.keys().projectileSharpen);
            }
            damage *= CombatMath.sharpenMultiplier(bonus);
        }

        // 2. Пассивки.
        final List<String> notes = new ArrayList<>(3);
        // Двойное оружие — только прямой удар (не размашистый), с той же долей заряда, что и удар основной рукой.
        if (melee && cause == DamageCause.ENTITY_ATTACK && ps.dualWield().enabled() && passives.contains(PASSIVE_DUAL_WIELD)) {
            final ItemStack offhand = attacker.getInventory().getItemInOffHand();
            if (isSwordOrAxe(offhand)) {
                final double charge = CombatMath.attackCharge(vanillaDamage, mainHandAttack(attacker));
                damage += charge * CombatMath.dualWieldBonus(offhandAttack(offhand, ps.dualWield()), ps.dualWield().percent());
            }
        }
        if (ps.rage().enabled() && passives.contains(PASSIVE_RAGE)) {
            final double rageBonus = melee
                ? CombatMath.rageBonusPercent(attacker.getHealth(), maxHealth(attacker), ps.rage().percentPerStep(),
                    ps.rage().stepPercent(), ps.rage().maxPercent())
                : readDouble(projectile.getPersistentDataContainer(), this.module.projectileRageKey());
            damage *= 1.0 + Math.max(0.0, rageBonus) / 100.0;
        }
        if (!melee && ps.headshot().enabled() && passives.contains(PASSIVE_HEADSHOT)
            && CombatMath.isHeadshot(impactY(projectile, target), target.getEyeLocation().getY(), ps.headshot().eyeOffset())) {
            damage *= ps.headshot().multiplier();
            notes.add(ps.headshot().message());
        }
        boolean backstab = false;
        if (ps.backstab().enabled() && passives.contains(PASSIVE_BACKSTAB) && (melee || !ps.backstab().meleeOnly())) {
            final Vector look = target.getLocation().getDirection();
            final Vector toAttacker = melee
                ? attacker.getLocation().toVector().subtract(target.getLocation().toVector())
                : projectile.getVelocity().clone().multiply(-1.0);
            backstab = CombatMath.isBackstab(look.getX(), look.getZ(), toAttacker.getX(), toAttacker.getZ(), ps.backstab().angle());
            if (backstab) {
                notes.add(ps.backstab().message());
            }
        }

        // 3. Урон по монстрам и стихийный урон (уменьшается сопротивлением цели-игрока той же стихии).
        double vs = 1.0;
        if (s.damageVs().monster() && target instanceof Monster) {
            vs += Math.max(0.0, a.targeted("damage_vs:monster")) / 100.0;
        }
        final AspectSnapshot targetAspects = target instanceof final Player tp ? this.aspects(tp) : AspectSnapshot.EMPTY;
        for (final String element : s.damageVs().elements()) {
            vs += CombatMath.elementalBonus(a.targeted("damage_vs:" + element), targetAspects.targeted("resistance:" + element));
        }
        damage *= vs;

        // 4. Крит (удар в спину — всегда крит).
        final boolean crit = backstab || (s.crit().enabled()
            && ThreadLocalRandom.current().nextDouble() < CombatMath.critChance(a.get(Aspect.CRIT_CHANCE), s.crit().capPercent()));
        if (crit) {
            damage *= CombatMath.critMultiplier(a.get(Aspect.CRIT_DAMAGE), s.crit().minDamagePercent());
            this.critEffects(s.crit(), target);
            notes.add(0, s.crit().message());
        }

        if (Double.isFinite(damage) && damage >= 0.0) {
            event.setDamage(damage);
        }
        this.actionBarLater(attacker, notes);
    }

    // ------------------------------------------------------------------ защита (HIGHEST)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDefend(final EntityDamageEvent event) {
        if (!(event.getEntity() instanceof final Player victim)) {
            return;
        }
        final CombatSettings s = this.module.settings();
        final DamageCause cause = event.getCause();
        final AspectSnapshot v = this.aspects(victim);
        double damage = event.getDamage();

        // 8. Заточка брони: урон ÷ (1 + armor_sharpen/100).
        if (s.armorSharpen().enabled() && !s.armorSharpen().ignoreCauses().contains(cause)) {
            damage /= CombatMath.armorDivisor(v.get(Aspect.ARMOR_SHARPEN));
        }

        // 9. Сопротивление: общее + целевое по причине/атакующему + PvP, не больше потолка.
        if (s.resistance().enabled() && !s.resistance().ignoreCauses().contains(cause)) {
            final Entity source = event instanceof final EntityDamageByEntityEvent byEntity ? sourceEntity(byEntity.getDamager()) : null;
            double total = v.get(Aspect.RESISTANCE);
            for (final ResistanceTarget target : s.resistance().targets()) {
                if (matches(target, cause, source)) {
                    total += v.targeted("resistance:" + target.id());
                }
            }
            if (source instanceof Player && source != victim) {
                total += v.get(Aspect.RESISTANCE_PVP);
            }
            damage *= CombatMath.resistanceMultiplier(total, s.resistance().cap());
        }

        if (damage != event.getDamage() && Double.isFinite(damage) && damage >= 0.0) {
            event.setDamage(damage);
        }
    }

    static boolean matches(final ResistanceTarget target, final DamageCause cause, final Entity source) {
        if (target.causes().contains(cause)) {
            return true;
        }
        if (source == null) {
            return false;
        }
        if (target.attackers().contains(source.getType())) {
            return true;
        }
        for (final AttackerGroup group : target.groups()) {
            if (group.matches(source)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ итог удара (MONITOR)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAfterAttack(final EntityDamageByEntityEvent event) {
        final CombatModule.Loaded loaded = this.module.loaded();
        final CombatSettings s = loaded.settings();
        final Player attacker = attackingPlayer(event.getDamager());
        if (attacker == null || attacker == event.getEntity() || !s.attack().causes().contains(event.getCause())
            || !(event.getEntity() instanceof final LivingEntity target)) {
            return;
        }
        // Только удары оружием, которые реально нанесли урон (не снежок, не полный блок щитом).
        if (event.getDamager() instanceof final Projectile projectile && !this.fromWeapon(projectile)) {
            return;
        }
        if (!(event.getFinalDamage() > 0.0)) {
            return;
        }
        final AspectSnapshot a = this.aspects(attacker);

        // 5. Статусы (цель в этом потоке).
        if (!target.isDead()) {
            final ThreadLocalRandom random = ThreadLocalRandom.current();
            this.status(target, s.statuses().bleed(), loaded.bleed(), a.get(Aspect.STATUS_BLEED), random);
            this.status(target, s.statuses().poison(), loaded.poison(), a.get(Aspect.STATUS_POISON), random);
            final CombatSettings.Status burn = s.statuses().burn();
            final double burnChance = a.get(Aspect.STATUS_BURN);
            if (burn.enabled() && burnChance > 0.0 && random.nextDouble() * 100.0 < burnChance) {
                target.setFireTicks(Math.max(target.getFireTicks(), burn.durationTicks()));
            }
        }

        // 6. Вампиризм: лечим атакующего только в его потоке.
        if (s.lifesteal().enabled()) {
            double dealt = event.getFinalDamage();
            if (s.lifesteal().limitToTargetHealth()) {
                dealt = Math.min(dealt, Math.max(0.0, target.getHealth() + target.getAbsorptionAmount()));
            }
            final double heal = CombatMath.lifestealHeal(dealt, a.get(Aspect.LIFESTEAL), s.lifesteal().capPercent());
            if (heal > 0.0) {
                this.healLater(attacker, heal);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(final EntityDeathEvent event) {
        if (!this.module.settings().healOnKill()) {
            return;
        }
        final Player killer = event.getEntity().getKiller();
        if (killer == null || killer == event.getEntity()) {
            return;
        }
        final double heal = this.aspects(killer).get(Aspect.HEAL_ON_KILL);
        if (heal > 0.0) {
            this.healLater(killer, heal);
        }
    }

    // ------------------------------------------------------------------ помощники

    private void status(final LivingEntity target, final CombatSettings.Status status, final PotionEffectType type,
                        final double chancePercent, final ThreadLocalRandom random) {
        if (!status.enabled() || type == null || !(chancePercent > 0.0) || random.nextDouble() * 100.0 >= chancePercent) {
            return;
        }
        final PotionEffect current = target.getPotionEffect(type);
        if (current != null && (current.getAmplifier() > status.amplifier()
            || (current.getAmplifier() == status.amplifier() && current.getDuration() >= status.durationTicks()))) {
            return; // уже есть такой же или сильнее
        }
        target.addPotionEffect(new PotionEffect(type, status.durationTicks(), status.amplifier(), false, true, true));
    }

    /** Лечение игрока в его потоке: не мёртвого, не выше максимума. Любой поток. */
    private void healLater(final Player player, final double amount) {
        final SystemContext context = this.system.context();
        context.runOnceForPlayer(player, () -> {
            if (!player.isOnline() || player.isDead()) {
                return;
            }
            final double health = player.getHealth();
            final double healed = CombatMath.healed(health, maxHealth(player), amount);
            if (healed > health) {
                player.setHealth(healed);
            }
        });
    }

    private void dodgeFeedback(final CombatSettings s, final Player defender, final Player attacker) {
        final CombatSettings.Evasion ev = s.evasion();
        if (!ev.message().isEmpty()) {
            defender.sendActionBar(Text.mm(ev.message())); // событие идёт в потоке цели
        }
        if (ev.sound().enabled()) {
            final Location at = defender.getLocation();
            defender.getWorld().playSound(at, ev.sound().key(), ev.sound().volume(), ev.sound().pitch());
        }
        if (attacker != null && !ev.attackerMessage().isEmpty()) {
            final String message = ev.attackerMessage();
            this.system.context().runOnceForPlayer(attacker, () -> attacker.sendActionBar(Text.mm(message)));
        }
    }

    private void critEffects(final CombatSettings.Crit crit, final LivingEntity target) {
        final World world = target.getWorld();
        final Location at = target.getLocation().add(0.0, target.getHeight() * 0.6, 0.0);
        if (crit.particle() != null && crit.particleCount() > 0) {
            world.spawnParticle(crit.particle(), at, crit.particleCount(), 0.3, 0.3, 0.3, 0.1);
        }
        if (crit.sound().enabled()) {
            world.playSound(at, crit.sound().key(), crit.sound().volume(), crit.sound().pitch());
        }
    }

    private void actionBarLater(final Player player, final List<String> notes) {
        final StringBuilder sb = new StringBuilder();
        for (final String note : notes) {
            if (note != null && !note.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(" <dark_gray>·</dark_gray> ");
                }
                sb.append(note);
            }
        }
        if (sb.isEmpty()) {
            return;
        }
        final String message = sb.toString();
        this.system.context().runOnceForPlayer(player, () -> player.sendActionBar(Text.mm(message)));
    }

    /** Снаряд выпущен из оружия игрока (есть снимок при выстреле). */
    private boolean fromWeapon(final Projectile projectile) {
        return projectile.getPersistentDataContainer().has(this.system.keys().projectileWeaponKind, PersistentDataType.STRING);
    }

    /**
     * Высота точки попадания снаряда. Во время события урона снаряд ещё стоит там, где был в начале тика (ваниль
     * двигает его после обработки попадания), поэтому пересекаем отрезок полёта за тик с рамкой цели (расширенной на
     * 0.3, как в ванильном поиске попадания) и берём высоту точки входа, зажатую в реальную рамку цели.
     */
    static double impactY(final Projectile projectile, final LivingEntity target) {
        final BoundingBox real = target.getBoundingBox();
        final Vector start = projectile.getLocation().toVector();
        final Vector velocity = projectile.getVelocity();
        double y = start.getY();
        final double length = velocity.length();
        if (length > 1e-6 && Double.isFinite(length)) {
            final BoundingBox inflated = real.clone().expand(0.3);
            final RayTraceResult hit = inflated.rayTrace(start, velocity.clone().multiply(1.0 / length), length + 0.5);
            if (hit != null) {
                y = hit.getHitPosition().getY();
            }
        }
        return CombatMath.clamp(y, real.getMinY(), real.getMaxY());
    }

    /** Атака основной руки (атрибут ATTACK_DAMAGE): по ней видно, какая доля заряда вошла в удар. */
    static double mainHandAttack(final Player player) {
        final AttributeInstance attack = player.getAttribute(Attribute.ATTACK_DAMAGE);
        return attack == null ? 1.0 : attack.getValue();
    }

    private AspectSnapshot aspects(final Player player) {
        return this.system.aspects().get(player.getUniqueId());
    }

    private Set<String> passives(final Player player) {
        if (this.system.progression() == null) {
            return Set.of();
        }
        final Set<String> set = this.system.progression().passives(player.getUniqueId());
        return set == null ? Set.of() : set;
    }

    /** Игрок, который бьёт: сам или стрелок снаряда; null для мобов и прочего. */
    static Player attackingPlayer(final Entity damager) {
        if (damager instanceof final Player player) {
            return player;
        }
        if (damager instanceof final Projectile projectile && projectile.getShooter() instanceof final Player shooter) {
            return shooter;
        }
        return null;
    }

    /** Источник урона для целей сопротивления: стрелок снаряда, иначе сам нанёсший урон. */
    static Entity sourceEntity(final Entity damager) {
        if (damager instanceof final Projectile projectile) {
            final ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof final Entity entity) {
                return entity;
            }
        }
        return damager;
    }

    static boolean isSwordOrAxe(final ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        final String name = item.getType().name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE");
    }

    /** Атака предмета: его модификаторы ATTACK_DAMAGE (сложение + базовая 1), иначе таблица материала. */
    static double offhandAttack(final ItemStack item, final CombatSettings.DualWield settings) {
        final ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasAttributeModifiers()) {
            final Collection<AttributeModifier> modifiers = meta.getAttributeModifiers(Attribute.ATTACK_DAMAGE);
            if (modifiers != null && !modifiers.isEmpty()) {
                double sum = 1.0;
                for (final AttributeModifier modifier : modifiers) {
                    if (modifier.getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                        sum += modifier.getAmount();
                    }
                }
                return Math.max(0.0, sum);
            }
        }
        final Material material = item.getType();
        final Double value = settings.attack().get(material);
        return value == null ? 0.0 : value;
    }

    static double maxHealth(final LivingEntity entity) {
        final AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? 20.0 : max.getValue();
    }

    private static double readDouble(final PersistentDataContainer pdc, final org.bukkit.NamespacedKey key) {
        final Double value = pdc.get(key, PersistentDataType.DOUBLE);
        return value == null || !Double.isFinite(value) ? 0.0 : value;
    }
}
