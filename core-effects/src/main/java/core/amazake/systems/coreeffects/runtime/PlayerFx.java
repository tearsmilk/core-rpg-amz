package core.amazake.systems.coreeffects.runtime;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreeffects.Settings;
import core.amazake.systems.coreeffects.Text;
import core.amazake.systems.coreeffects.effect.Active;
import core.amazake.systems.coreeffects.effect.EffectBook;
import core.amazake.systems.coreeffects.effect.EffectType;
import core.amazake.systems.coreeffects.hud.Overlay;
import core.amazake.systems.coreeffects.item.Items;
import core.amazake.systems.coreeffects.item.Keys;
import core.amazake.systems.coreeffects.logic.BleedingRules;
import core.amazake.systems.coreeffects.logic.CurseFilter;
import core.amazake.systems.coreeffects.logic.Disorient;
import core.amazake.systems.coreeffects.logic.Durations;
import core.amazake.systems.coreeffects.logic.WarmthRules;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Эффекты одного онлайн-игрока: состояние ({@link EffectBook}), задача каждого тика в его потоке ({@code runForPlayer}),
 * модификаторы атрибутов, вспомогательные ванильные эффекты, накладка на экран, иконки, фантомы.
 *
 * <p>Все методы, кроме помеченных «любой поток», вызываются только в потоке игрока.</p>
 */
public final class PlayerFx {

    private static final Key OVERLAY_FONT = Key.key("coreeffects", "overlay");
    private static final BlockData BLOOD = Material.REDSTONE_BLOCK.createBlockData();
    private static final Particle.DustOptions BLOOD_DUST = new Particle.DustOptions(Color.fromRGB(120, 0, 8), 1.1f);

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final Player player;
    private final EffectBook book;
    private final Phantoms phantoms;

    private volatile ScheduledTask task;
    private volatile boolean closed;
    /** Причина смерти от эффекта (для сообщения в PlayerDeathEvent). Ставится прямо перед setHealth(0). */
    private volatile EffectType deathCause;

    private long now;
    private boolean dirty;
    private double twist;
    private long nextTwist;
    private long nauseaUntil;
    private long bloodPulseUntil;
    private String overlaySent = "";
    private long overlaySentAt = -10_000;
    private boolean ownDarkness;
    private boolean ownBlindness;
    private boolean ownNausea;
    private long lastBandage = -10_000;

    PlayerFx(final SystemContext context, final Supplier<Settings> settings, final Player player, final EffectBook book,
             final String instance) {
        this.context = context;
        this.settings = settings;
        this.player = player;
        this.book = book;
        this.phantoms = new Phantoms(context, player, instance);
    }

    public Player player() {
        return this.player;
    }

    /** Любой поток: копия-снимок для команд и PlaceholderAPI. */
    public EffectBook book() {
        return this.book;
    }

    /** Любой поток. */
    public boolean unconscious() {
        final Active a = this.book.get(EffectType.BLEEDING);
        return a != null && a.b() > 0;
    }

    /** Любой поток. */
    public EffectType deathCause() {
        return this.deathCause;
    }

    void start() {
        this.stripStale();
        this.task = this.context.runForPlayer(this.player, t -> this.tick(), 1L);
        this.apply(this.settings.get());
    }

    // ---------------------------------------------------------------------------------------- управление

    /** Наложить/продлить. {@code ticks} −1 — бесконечно. */
    public void give(final EffectType type, final long ticks) {
        final Settings s = this.settings.get();
        final long initialA = type == EffectType.CURSE ? 20L * s.curse().intervalSeconds().pick(ThreadLocalRandom.current()) : 0;
        if (this.book.give(type, ticks, initialA)) {
            Text.send(this.player, s, "got-" + type.id());
            if (type == EffectType.PANIC) {
                this.player.playSound(this.player.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1f, 0.8f);
                this.nextTwist = 0;
            } else if (type == EffectType.BLEEDING) {
                this.player.playSound(this.player.getLocation(), Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, SoundCategory.PLAYERS, 1f, 0.7f);
            } else if (type == EffectType.CURSE) {
                this.player.playSound(this.player.getLocation(), Sound.ENTITY_WITHER_AMBIENT, SoundCategory.PLAYERS, 0.5f, 1.6f);
            }
        }
        this.dirty = true;
        this.apply(s);
        this.save();
    }

    /** Снять один эффект; {@code cured} — сообщение «снят» вместо «прошёл». */
    public boolean remove(final EffectType type, final boolean cured) {
        if (!this.book.remove(type)) {
            return false;
        }
        this.ended(type, cured, this.settings.get());
        this.dirty = true;
        this.save();
        return true;
    }

    /** Снять все; число снятых. */
    public int clearAll(final boolean announce) {
        final List<EffectType> was = this.book.clear();
        final Settings s = this.settings.get();
        for (final EffectType t : was) {
            if (announce) {
                this.ended(t, true, s);
            }
        }
        this.restoreAll();
        this.dirty = true;
        this.save();
        return was.size();
    }

    /** Предмет съеден/выпит/применён: снять эффекты, которые им лечатся, и «утолить» тепло. Список снятых. */
    public List<EffectType> useItem(final Set<String> tokens) {
        final Settings s = this.settings.get();
        final List<EffectType> cured = new ArrayList<>();
        for (final EffectType t : EffectType.values()) {
            if (this.book.has(t) && !java.util.Collections.disjoint(s.of(t).curedBy(), tokens)) {
                if (this.remove(t, true)) {
                    cured.add(t);
                }
            }
        }
        if (this.book.has(EffectType.WARMTH) && !java.util.Collections.disjoint(s.warmth().quenchedBy(), tokens)) {
            this.book.edit(EffectType.WARMTH, a -> {
                a.setA(0);
                a.setB(0);
            });
            this.ownNauseaOff();
            Text.send(this.player, s, "quenched");
            this.player.playSound(this.player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.6f, 1.4f);
            this.dirty = true;
        }
        return cured;
    }

    /** Бинт: есть ли что перевязать (кулдаун учитывается). */
    public boolean bandageReady(final int cooldown) {
        return this.now - this.lastBandage >= cooldown;
    }

    public void markBandage() {
        this.lastBandage = this.now;
    }

    public boolean curableBy(final String token) {
        final Settings s = this.settings.get();
        for (final EffectType t : EffectType.values()) {
            if (this.book.has(t) && s.of(t).curedBy().contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** Ударили фантома: растворить. */
    public boolean hitPhantom(final Entity e) {
        return this.phantoms.hit(e);
    }

    // ---------------------------------------------------------------------------------------- тик

    private void tick() {
        if (this.closed || !this.player.isValid()) {
            return;
        }
        this.now++;
        final Settings s = this.settings.get();
        final boolean dead = this.player.isDead();
        if (!dead) {
            for (final EffectType t : this.book.advance(1)) {
                this.ended(t, false, s);
                this.dirty = true;
            }
        }
        if (this.book.isEmpty()) {
            if (this.ownAnything()) {
                this.restoreAll();
            }
            this.overlay(s, List.of());
            if (this.dirty) {
                this.save();
            }
            return;
        }
        if (dead) {
            return;
        }
        final boolean survival = this.player.getGameMode() == GameMode.SURVIVAL || this.player.getGameMode() == GameMode.ADVENTURE;
        final Map<EffectType, Active> snap = this.book.snapshot();
        if (snap.containsKey(EffectType.BLEEDING)) {
            this.bleeding(s, survival);
        }
        if (this.player.isDead() || this.book.isEmpty()) {
            return;
        }
        if (snap.containsKey(EffectType.PANIC)) {
            this.panic(s, survival);
        }
        if (snap.containsKey(EffectType.WARMTH)) {
            this.warmth(s, survival);
        }
        if (this.player.isDead() || this.book.isEmpty()) {
            return;
        }
        if (snap.containsKey(EffectType.CURSE)) {
            this.curse(s, survival);
        }
        if (this.now % 10 == 0) {
            this.apply(s);
        }
        this.overlay(s, this.overlayParts(s));
        if (s.hud().actionbar() && this.now % s.hud().actionbarIntervalTicks() == 0) {
            this.actionbar(s);
        }
        if (this.now % 100 == 0) {
            this.save();
        }
    }

    private void bleeding(final Settings s, final boolean survival) {
        final Settings.Bleeding b = s.bleeding();
        final BleedingRules.Step[] step = {BleedingRules.Step.NONE};
        this.book.edit(EffectType.BLEEDING, a -> {
            final BleedingRules.Result r = BleedingRules.tick(a.a(), a.b(), a.elapsed(), b.damageIntervalTicks(),
                survival ? b.faintAfterTicks() : 0, b.refaintAfterTicks(), b.faintTicks());
            a.setA(r.untreated());
            a.setB(r.faint());
            step[0] = r.step();
        });
        switch (step[0]) {
            case DAMAGE -> {
                if (survival) {
                    this.bleed(b);
                }
            }
            case FAINT -> {
                Text.send(this.player, s, "faint");
                this.player.setSprinting(false);
                this.player.playSound(this.player.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1f, 0.6f);
                this.apply(s);
                this.dirty = true;
            }
            case WAKE -> {
                Text.send(this.player, s, "wake");
                this.apply(s);
                this.dirty = true;
            }
            default -> { }
        }
        if (this.unconscious()) {
            final Vector v = this.player.getVelocity();
            if (this.onGround() && (v.getX() != 0 || v.getZ() != 0)) {
                this.player.setVelocity(new Vector(0, v.getY(), 0));
            }
        }
        if (b.particles() && this.now % b.particleIntervalTicks() == 0) {
            final Location l = this.player.getLocation();
            final var w = this.player.getWorld();
            w.spawnParticle(Particle.FALLING_DUST, l.clone().add(0, 0.9, 0), 2, 0.18, 0.25, 0.18, 0, BLOOD);
            if (ThreadLocalRandom.current().nextInt(3) == 0) {
                w.spawnParticle(Particle.DUST, l.clone().add(0, 0.05, 0), 3, 0.25, 0.02, 0.25, 0, BLOOD_DUST);
            }
            if (ThreadLocalRandom.current().nextInt(4) == 0) {
                w.spawnParticle(Particle.BLOCK, l.clone().add(0, 0.1, 0), 4, 0.15, 0.05, 0.15, 0, BLOOD);
            }
        }
    }

    /** На земле (по словам клиента — для толчков этого хватает). */
    @SuppressWarnings("deprecation")
    private boolean onGround() {
        return this.player.isOnGround();
    }

    private void bleed(final Settings.Bleeding b) {
        final double h = this.player.getHealth();
        final double dmg = BleedingRules.allowedDamage(h, b.damage(), b.minHealth());
        if (dmg <= 0) {
            return;
        }
        this.bloodPulseUntil = this.now + 10;
        if (h - dmg <= 0.0001) {
            this.kill(EffectType.BLEEDING);
            return;
        }
        this.player.setHealth(h - dmg);
        this.player.playHurtAnimation(0f);
        this.player.playSound(this.player.getLocation(), Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 0.6f, 0.8f);
    }

    private void kill(final EffectType cause) {
        this.deathCause = cause;
        try {
            this.player.setHealth(0);
        } finally {
            this.deathCause = null;
        }
    }

    private void panic(final Settings s, final boolean survival) {
        final Settings.Panic p = s.panic();
        if (p.nauseaEveryTicks() > 0 && s.screen().nausea()) {
            final Active a = this.book.get(EffectType.PANIC);
            if (a != null && a.elapsed() % p.nauseaEveryTicks() == p.nauseaEveryTicks() / 3) {
                this.nauseaUntil = this.now + p.nauseaTicks();
            }
        }
        if (this.now >= this.nextTwist) {
            final ThreadLocalRandom r = ThreadLocalRandom.current();
            this.twist = p.twistDegrees().pick(r);
            this.nextTwist = this.now + 20L * p.twistChangeSeconds().pick(r);
        }
        if (p.disorient() && survival && !this.unconscious() && !this.player.isFlying() && !this.player.isGliding()
            && !this.player.isInsideVehicle() && !this.player.isSwimming() && this.onGround()) {
            final Input in = this.player.getCurrentInput();
            final int f = (in.isForward() ? 1 : 0) - (in.isBackward() ? 1 : 0);
            final int l = (in.isLeft() ? 1 : 0) - (in.isRight() ? 1 : 0);
            final double[] dir = Disorient.direction(f, l, this.player.getLocation().getYaw(), this.twist);
            if (dir != null) {
                final Vector v = this.player.getVelocity();
                final double[] nv = Disorient.blend(v.getX(), v.getZ(), dir, p.disorientSpeed(), p.disorientStrength());
                this.player.setVelocity(new Vector(nv[0], v.getY(), nv[1]));
            }
        }
        this.phantoms.tick(this.now, p.phantoms());
    }

    private void warmth(final Settings s, final boolean survival) {
        final Settings.Warmth w = s.warmth();
        final int[] stage = {-1};
        final boolean[] lethal = {false};
        final boolean[] heal = {false};
        this.book.edit(EffectType.WARMTH, a -> {
            if (survival) {
                a.setA(a.a() + 1);
            }
            final int st = WarmthRules.stage(a.a(), w.deadlineTicks(), w.warnAtPercent());
            if (st > a.b()) {
                stage[0] = st;
                a.setB(st);
            }
            lethal[0] = WarmthRules.lethal(a.a(), w.deadlineTicks());
            heal[0] = WarmthRules.healNow(a.elapsed(), w.healIntervalTicks());
        });
        if (stage[0] > 0) {
            Text.send(this.player, s, "warmth-warn-" + Math.min(3, stage[0]));
            this.player.playSound(this.player.getLocation(), Sound.BLOCK_FIRE_AMBIENT, SoundCategory.PLAYERS, 1f, 0.7f);
            this.dirty = true;
        }
        if (lethal[0] && survival) {
            this.kill(EffectType.WARMTH);
            return;
        }
        if (heal[0] && w.heal() > 0) {
            final AttributeInstance max = this.player.getAttribute(Attribute.MAX_HEALTH);
            final double cap = max == null ? 20 : max.getValue();
            final double h = this.player.getHealth();
            if (h > 0 && h < cap) {
                this.player.setHealth(Math.min(cap, h + w.heal()));
            }
        }
    }

    private void curse(final Settings s, final boolean survival) {
        final Settings.Curse c = s.curse();
        final boolean[] fire = {false};
        this.book.edit(EffectType.CURSE, a -> {
            a.setA(a.a() - 1);
            if (a.a() <= 0) {
                a.setA(20L * c.intervalSeconds().pick(ThreadLocalRandom.current()));
                fire[0] = true;
            }
        });
        if (fire[0] && survival) {
            this.takeItem(s);
            this.dirty = true;
        }
    }

    /** Проклятье: забрать немного случайного обычного предмета. */
    private void takeItem(final Settings s) {
        final Settings.Curse c = s.curse();
        final PlayerInventory inv = this.player.getInventory();
        final List<Integer> slots = new ArrayList<>();
        for (int i = c.hotbar() ? 0 : 9; i < 36; i++) {
            final ItemStack it = inv.getItem(i);
            if (it != null && !it.isEmpty() && CurseFilter.ordinary(Items.facts(it), c.protectMaterials(),
                c.protectNbt(), c.protectNamed(), c.protectCustomModel()) && !Items.isBandage(it)) {
                slots.add(i);
            }
        }
        if (c.offhand()) {
            final ItemStack it = inv.getItemInOffHand();
            if (!it.isEmpty() && CurseFilter.ordinary(Items.facts(it), c.protectMaterials(), c.protectNbt(),
                c.protectNamed(), c.protectCustomModel())) {
                slots.add(40);
            }
        }
        if (slots.isEmpty()) {
            return;
        }
        final ThreadLocalRandom r = ThreadLocalRandom.current();
        final int slot = slots.get(r.nextInt(slots.size()));
        final ItemStack it = inv.getItem(slot);
        if (it == null || it.isEmpty()) {
            return;
        }
        final int n = CurseFilter.take(it.getAmount(), c.amount().pick(r), r.nextDouble() < c.wholeStackChance());
        final ItemStack taken = it.clone();
        taken.setAmount(n);
        final Component name = it.effectiveName();
        if (n >= it.getAmount()) {
            inv.setItem(slot, null);
        } else {
            it.setAmount(it.getAmount() - n);
            inv.setItem(slot, it);
        }
        final Location l = this.player.getLocation();
        if (c.dropInstead()) {
            this.player.getWorld().dropItemNaturally(l, taken);
        }
        this.player.getWorld().spawnParticle(Particle.WITCH, l.clone().add(0, 1, 0), 14, 0.3, 0.5, 0.3, 0.02);
        this.player.playSound(l, Sound.ENTITY_ALLAY_ITEM_TAKEN, SoundCategory.PLAYERS, 0.8f, 0.5f);
        this.player.playSound(l, Sound.ENTITY_VEX_AMBIENT, SoundCategory.PLAYERS, 0.5f, 0.6f);
        Text.send(this.player, s, "curse-took", Placeholder.component("item", name),
            Placeholder.unparsed("amount", Integer.toString(n)));
    }

    // ---------------------------------------------------------------------------- атрибуты и эффекты

    /** Привести атрибуты и ванильные эффекты к нужным (каждые 10 тиков и при изменениях). */
    private void apply(final Settings s) {
        final boolean panic = this.book.has(EffectType.PANIC);
        final boolean faint = this.unconscious();
        final Settings.Panic p = s.panic();
        this.modifier(Attribute.ATTACK_DAMAGE, Keys.PANIC_DAMAGE, p.bonusDamage(), AttributeModifier.Operation.ADD_NUMBER,
            panic && p.bonusDamage() > 0);
        double penalty = p.maxHealthPenalty();
        final AttributeInstance max = this.player.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) {
            final AttributeModifier mine = max.getModifier(Keys.PANIC_HEALTH);
            final double without = max.getValue() - (mine == null ? 0 : mine.getAmount());
            penalty = Math.min(penalty, Math.max(0, without - 2));   // не меньше 1 сердца
        }
        this.modifier(Attribute.MAX_HEALTH, Keys.PANIC_HEALTH, -penalty, AttributeModifier.Operation.ADD_NUMBER,
            panic && penalty > 0);
        this.modifier(Attribute.MOVEMENT_SPEED, Keys.PANIC_SPEED, -p.slowness(),
            AttributeModifier.Operation.MULTIPLY_SCALAR_1, panic && p.slowness() > 0 && !faint);
        this.modifier(Attribute.MOVEMENT_SPEED, Keys.FAINT_SPEED, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, faint);
        this.modifier(Attribute.JUMP_STRENGTH, Keys.FAINT_JUMP, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, faint);

        // Тьма — пока паника.
        if (panic && p.darkness() && s.screen().darkness()) {
            this.ownDarkness = this.ensurePotion(PotionEffectType.DARKNESS, 0) || this.ownDarkness;
        } else if (this.ownDarkness) {
            this.dropPotion(PotionEffectType.DARKNESS);
            this.ownDarkness = false;
        }
        // Слепота — пока без сознания.
        if (faint && s.screen().blindnessWhenFainted()) {
            this.ownBlindness = this.ensurePotion(PotionEffectType.BLINDNESS, 0) || this.ownBlindness;
        } else if (this.ownBlindness) {
            this.dropPotion(PotionEffectType.BLINDNESS);
            this.ownBlindness = false;
        }
        // Тошнота — приступы паники и сильный жар.
        boolean nausea = panic && this.now < this.nauseaUntil;
        final Active w = this.book.get(EffectType.WARMTH);
        if (w != null && s.warmth().nauseaAtPercent() > 0
            && WarmthRules.fraction(w.a(), s.warmth().deadlineTicks()) * 100 >= s.warmth().nauseaAtPercent()) {
            nausea = true;
        }
        if (nausea && s.screen().nausea()) {
            this.ownNausea = this.ensurePotion(PotionEffectType.NAUSEA, 0) || this.ownNausea;
        } else {
            this.ownNauseaOff();
        }
    }

    private void ownNauseaOff() {
        if (this.ownNausea) {
            this.dropPotion(PotionEffectType.NAUSEA);
            this.ownNausea = false;
        }
    }

    private void modifier(final Attribute attribute, final NamespacedKey key, final double amount,
                          final AttributeModifier.Operation op, final boolean want) {
        final AttributeInstance inst = this.player.getAttribute(attribute);
        if (inst == null) {
            return;
        }
        final AttributeModifier cur = inst.getModifier(key);
        if (!want) {
            if (cur != null) {
                inst.removeModifier(key);
            }
            return;
        }
        if (cur != null && cur.getAmount() == amount && cur.getOperation() == op) {
            return;
        }
        if (cur != null) {
            inst.removeModifier(key);
        }
        inst.addTransientModifier(new AttributeModifier(key, amount, op, EquipmentSlotGroup.ANY));
    }

    /** Наш ванильный эффект: скрытый (без частиц и значка), «окружающий». true — наложен нами. */
    private boolean ensurePotion(final PotionEffectType type, final int amp) {
        final PotionEffect cur = this.player.getPotionEffect(type);
        if (cur != null && !ours(cur)) {
            return false;   // чужой эффект (зелье, другой плагин) — не трогаем
        }
        if (cur == null || cur.getDuration() < 30) {
            this.player.addPotionEffect(new PotionEffect(type, 80, amp, true, false, false));
        }
        return true;
    }

    private void dropPotion(final PotionEffectType type) {
        final PotionEffect cur = this.player.getPotionEffect(type);
        if (cur != null && ours(cur)) {
            this.player.removePotionEffect(type);
        }
    }

    private static boolean ours(final PotionEffect e) {
        return e.isAmbient() && !e.hasParticles() && !e.hasIcon() && e.getDuration() <= 80;
    }

    /** Держим ли что-то после конца эффектов (модификаторы проверяются реже — это поиск по атрибутам). */
    private boolean ownAnything() {
        return this.ownDarkness || this.ownBlindness || this.ownNausea || this.phantoms.alive() > 0
            || !this.overlaySent.isEmpty() || (this.now % 100 == 0 && this.hasModifiers());
    }

    private boolean hasModifiers() {
        for (final Attribute a : List.of(Attribute.ATTACK_DAMAGE, Attribute.MAX_HEALTH, Attribute.MOVEMENT_SPEED,
            Attribute.JUMP_STRENGTH)) {
            final AttributeInstance inst = this.player.getAttribute(a);
            if (inst != null && (inst.getModifier(Keys.PANIC_DAMAGE) != null || inst.getModifier(Keys.PANIC_HEALTH) != null
                || inst.getModifier(Keys.PANIC_SPEED) != null || inst.getModifier(Keys.FAINT_SPEED) != null
                || inst.getModifier(Keys.FAINT_JUMP) != null)) {
                return true;
            }
        }
        return false;
    }

    /** Убрать модификаторы с нашими ключами, оставшиеся от прошлого запуска (вход, включение). */
    private void stripStale() {
        for (final Attribute a : List.of(Attribute.ATTACK_DAMAGE, Attribute.MAX_HEALTH, Attribute.MOVEMENT_SPEED,
            Attribute.JUMP_STRENGTH)) {
            final AttributeInstance inst = this.player.getAttribute(a);
            if (inst == null) {
                continue;
            }
            for (final NamespacedKey k : List.of(Keys.PANIC_DAMAGE, Keys.PANIC_HEALTH, Keys.PANIC_SPEED, Keys.FAINT_SPEED,
                Keys.FAINT_JUMP)) {
                if (inst.getModifier(k) != null) {
                    inst.removeModifier(k);
                }
            }
        }
    }

    /** Конец одного эффекта: сообщение и уборка того, что он держал. */
    private void ended(final EffectType type, final boolean cured, final Settings s) {
        Text.send(this.player, s, cured ? "cured" : "ended", Placeholder.parsed("effect", s.of(type).name()));
        if (type == EffectType.PANIC) {
            this.phantoms.clear();
            this.nauseaUntil = 0;
        }
        if (type == EffectType.WARMTH) {
            this.ownNauseaOff();
        }
        this.apply(s);
    }

    /** Вернуть игроку всё: атрибуты, ванильные эффекты, заголовок, фантомы. */
    private void restoreAll() {
        this.stripStale();
        if (this.ownDarkness) {
            this.dropPotion(PotionEffectType.DARKNESS);
        }
        if (this.ownBlindness) {
            this.dropPotion(PotionEffectType.BLINDNESS);
        }
        if (this.ownNausea) {
            this.dropPotion(PotionEffectType.NAUSEA);
        }
        this.ownDarkness = false;
        this.ownBlindness = false;
        this.ownNausea = false;
        this.phantoms.clear();
        if (!this.overlaySent.isEmpty()) {
            this.player.clearTitle();
            this.overlaySent = "";
        }
    }

    // ------------------------------------------------------------------------------------------- экран

    private List<Overlay.Part> overlayParts(final Settings s) {
        if (!s.screen().enabled()) {
            return List.of();
        }
        final List<Overlay.Part> parts = new ArrayList<>(4);
        final Active bleed = this.book.get(EffectType.BLEEDING);
        if (bleed != null && bleed.b() > 0) {
            parts.add(new Overlay.Part(Overlay.Layer.DARK_FILL, 7));
            parts.add(new Overlay.Part(Overlay.Layer.BLOOD, 2));
            return parts;
        }
        if (this.book.has(EffectType.PANIC)) {
            parts.add(new Overlay.Part(Overlay.Layer.DARK_FILL, 2));
            parts.add(new Overlay.Part(Overlay.Layer.PANIC, 2));
        }
        if (bleed != null) {
            int level = BleedingRules.bloodLevel(bleed.a(), s.bleeding().faintAfterTicks());
            if (this.now < this.bloodPulseUntil) {
                level++;
            }
            parts.add(new Overlay.Part(Overlay.Layer.BLOOD, level));
        }
        final Active w = this.book.get(EffectType.WARMTH);
        if (w != null) {
            parts.add(new Overlay.Part(Overlay.Layer.HEAT,
                1 + WarmthRules.stage(w.a(), s.warmth().deadlineTicks(), s.warmth().warnAtPercent())));
        }
        if (this.book.has(EffectType.CURSE)) {
            parts.add(new Overlay.Part(Overlay.Layer.CURSE, 1));
        }
        return parts;
    }

    private void overlay(final Settings s, final List<Overlay.Part> parts) {
        final String text = Overlay.compose(parts);
        if (text.isEmpty()) {
            if (!this.overlaySent.isEmpty()) {
                this.player.clearTitle();
                this.overlaySent = "";
            }
            return;
        }
        final int refresh = s.screen().refreshTicks();
        if (text.equals(this.overlaySent) && this.now - this.overlaySentAt < refresh) {
            return;
        }
        final boolean first = this.overlaySent.isEmpty();
        this.overlaySent = text;
        this.overlaySentAt = this.now;
        final Component c = Component.text(text).font(OVERLAY_FONT).color(NamedTextColor.WHITE)
            .shadowColor(ShadowColor.none());
        this.player.showTitle(Title.title(c, Component.empty(), Title.Times.times(
            first ? Duration.ofMillis(250) : Duration.ZERO, Duration.ofMillis(50L * (refresh + 40)), Duration.ofMillis(500))));
    }

    private void actionbar(final Settings s) {
        final Map<EffectType, Active> snap = this.book.snapshot();
        if (snap.isEmpty()) {
            return;
        }
        final Active bleed = snap.get(EffectType.BLEEDING);
        if (bleed != null && bleed.b() > 0) {
            this.player.sendActionBar(Text.parse(s.hud().unconsciousFormat(),
                Placeholder.unparsed("time", Durations.format(bleed.b()))));
            return;
        }
        Component bar = Component.empty();
        boolean firstPart = true;
        for (final var e : snap.entrySet()) {
            final String fmt = s.hud().format().get(e.getKey());
            if (fmt == null || fmt.isBlank()) {
                continue;
            }
            String thirst = "";
            if (e.getKey() == EffectType.WARMTH && s.warmth().deadlineTicks() > 0) {
                thirst = Math.round(WarmthRules.fraction(e.getValue().a(), s.warmth().deadlineTicks()) * 100) + "%";
            }
            if (!firstPart) {
                bar = bar.append(Text.parse(s.hud().separator()));
            }
            firstPart = false;
            bar = bar.append(Text.parse(fmt, Placeholder.unparsed("time", Durations.format(e.getValue().remaining())),
                Placeholder.unparsed("thirst", thirst)));
        }
        this.player.sendActionBar(bar);
    }

    // ---------------------------------------------------------------------------------------- сохранение

    /** Записать состояние в PDC игрока. */
    void save() {
        final var pdc = this.player.getPersistentDataContainer();
        if (this.book.isEmpty()) {
            pdc.remove(Keys.STATE);
        } else {
            pdc.set(Keys.STATE, PersistentDataType.STRING, this.book.encode());
        }
        this.dirty = false;
    }

    /** Выход игрока: сохранить и убрать всё показанное (поток игрока). */
    void quit() {
        this.closed = true;
        final ScheduledTask t = this.task;
        if (t != null) {
            t.cancel();
        }
        this.save();
        this.restoreAll();
    }

    /** Выключение системы: любой поток — работа уходит в поток игрока. */
    void shutdown() {
        this.closed = true;
        final ScheduledTask t = this.task;
        if (t != null) {
            t.cancel();
        }
        this.context.runOnceForPlayer(this.player, () -> {
            this.save();
            this.restoreAll();
        });
    }

    /** Смерть: снять всё (если так настроено). */
    void death(final Settings s) {
        if (s.clearOnDeath()) {
            this.book.clear();
            this.restoreAll();
            this.save();
        }
    }
}
