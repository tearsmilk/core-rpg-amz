package core.amazake.systems.coreweapons.runtime;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreweapons.Settings;
import core.amazake.systems.coreweapons.logic.Frames;
import core.amazake.systems.coreweapons.logic.Geometry;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.function.Supplier;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

/**
 * Один онлайн-игрок: задача каждого тика в его потоке ({@code runForPlayer}). Кольцо частиц, пока оружие в основной
 * руке; замах — кадры на предмете в слоте, где был удар, и огненный след. Все поля, кроме помеченных volatile, трогает
 * только поток игрока.
 */
final class Holder {

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final Player player;
    private volatile ScheduledTask task;
    private long tick;

    // замах
    private int swingAge = -1;
    private int swingSlot = -1;
    private String swingWeapon;
    private String shownFrame;
    private long lastSwing = Long.MIN_VALUE / 2;
    private int swings;
    private boolean swingFresh;

    /** Копает блок (замах не показываем): пишется событиями блоков, читается в потоке игрока. */
    volatile boolean digging;
    /** Тик сервера последней дрожи (откат); пишет поток удара. */
    volatile long lastTremor = Long.MIN_VALUE / 2;

    Holder(final SystemContext context, final Supplier<Settings> settings, final Player player) {
        this.context = context;
        this.settings = settings;
        this.player = player;
    }

    Player player() {
        return this.player;
    }

    int swings() {
        return this.swings;
    }

    boolean swinging() {
        return this.swingAge >= 0;
    }

    void start() {
        // хвосты прошлого запуска/сбоя: кадры на предметах
        ItemTools.stripAll(this.player.getInventory());
        this.task = this.context.runForPlayer(this.player, t -> this.tick(), 1L);
    }

    /** Выход/выключение (поток игрока): задача, кадры. */
    void stop() {
        final ScheduledTask t = this.task;
        if (t != null) {
            t.cancel();
        }
        this.endSwing(true);
    }

    void cancelTask() {
        final ScheduledTask t = this.task;
        if (t != null) {
            t.cancel();
        }
    }

    // ------------------------------------------------------------------------------------------------ тик

    private void tick() {
        this.tick++;
        final Settings s = this.settings.get();
        if (this.swingAge >= 0) {
            if (this.swingFresh) {
                this.swingFresh = false; // замах начат в этом же тике (событие раньше задачи) — первый кадр держим
            } else {
                this.swingAge++;
                this.swingStep(s);
            }
        }
        final Settings.Weapon w = s.weapon(ItemTools.id(this.player.getInventory().getItemInMainHand()));
        if (w == null || w.orbit() == null || this.player.isInvisible() || this.player.isDead()) {
            return;
        }
        final Settings.Orbit o = w.orbit();
        if (this.tick % o.period() == 0) {
            this.orbit(o);
        }
    }

    private void orbit(final Settings.Orbit o) {
        final Location at = this.player.getLocation();
        final World world = at.getWorld();
        final float yaw = this.player.getBodyYaw();
        final double[] c = Geometry.local(yaw, o.right(), o.up(), o.forward());
        final double cx = at.getX() + c[0];
        final double cy = at.getY() + c[1] - (this.player.isSneaking() ? 0.3 : 0);
        final double cz = at.getZ() + c[2];
        final Particle.DustTransition dust = new Particle.DustTransition(Color.fromRGB(o.colorFrom()), Color.fromRGB(o.colorTo()), o.size());
        int i = 0;
        for (final double[] p : Geometry.orbit(this.tick, o.points(), o.radius(), o.degreesPerTick(), o.tilt(), o.bob(), yaw)) {
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, cx + p[0], cy + p[1], cz + p[2], 1, 0, 0, 0, 0, dust);
            if (o.flameEvery() > 0 && (i + this.tick / o.period()) % o.flameEvery() == 0) {
                world.spawnParticle(Particle.SMALL_FLAME, cx + p[0], cy + p[1], cz + p[2], 1, 0, 0, 0, 0);
            }
            i++;
        }
        if (o.handEmber() && this.tick % 4 == 0) {
            final double[] h = Geometry.local(yaw, 0.4, 1.0, 0.25);
            world.spawnParticle(Particle.SMALL_FLAME, at.getX() + h[0], at.getY() + h[1], at.getZ() + h[2], 1, 0.06, 0.08, 0.06, 0.004);
        }
    }

    // ------------------------------------------------------------------------------------------------ замах

    /** Взмах рукой (поток игрока). */
    void onArmSwing() {
        final Settings.Weapon w = this.settings.get().weapon(ItemTools.id(this.player.getInventory().getItemInMainHand()));
        if (w == null || w.swing() == null || this.digging) {
            return;
        }
        final Settings.Swing sw = w.swing();
        if (this.tick - this.lastSwing < sw.minInterval()) {
            return;
        }
        this.endSwing(false);
        this.lastSwing = this.tick;
        this.swingAge = 0;
        this.swingFresh = true;
        this.swingSlot = this.player.getInventory().getHeldItemSlot();
        this.swingWeapon = w.id();
        this.swings++;
        if (sw.sound() != null) {
            final Location l = this.player.getLocation();
            l.getWorld().playSound(l, sw.sound().key(), SoundCategory.PLAYERS, sw.sound().volume(), sw.sound().pitch());
        }
        this.swingStep(this.settings.get());
    }

    private void swingStep(final Settings s) {
        final Settings.Weapon w = s.weapon(this.swingWeapon);
        final PlayerInventory inv = this.player.getInventory();
        final ItemStack item = inv.getItem(this.swingSlot);
        if (w == null || w.swing() == null || inv.getHeldItemSlot() != this.swingSlot || !this.swingWeapon.equals(ItemTools.id(item))) {
            this.endSwing(true); // переложили предмет, сменили слот, перечитали настройки — снять кадры везде
            return;
        }
        final Frames frames = w.swing().frames();
        final String frame = frames.at(this.swingAge);
        if (frame == null) {
            this.endSwing(false);
            return;
        }
        if (!frame.equals(this.shownFrame)) {
            final ItemStack next = ItemTools.withFrame(item, frame);
            if (next != null) {
                inv.setItem(this.swingSlot, next);
            }
            this.shownFrame = frame;
        }
        if (w.swing().trail()) {
            this.trail(w.swing(), frames);
        }
    }

    /** Огненный след по дуге: начинается после первого кадра (замах назад) и идёт до конца замаха. */
    private void trail(final Settings.Swing sw, final Frames frames) {
        final double start = frames.ticks().isEmpty() ? 0 : frames.ticks().get(0);
        final double len = frames.total() - start;
        if (len <= 0 || this.swingAge < start) {
            return;
        }
        final double s0 = Math.max(0, (this.swingAge - start) / len);
        final double s1 = Math.min(1, (this.swingAge - start + 1) / len);
        final Location eye = this.player.getEyeLocation();
        final Vector d = eye.getDirection();
        final double[] look = {d.getX(), d.getY(), d.getZ()};
        final float yaw = eye.getYaw();
        final World world = eye.getWorld();
        final Particle.DustTransition dust = new Particle.DustTransition(Color.fromRGB(sw.colorFrom()), Color.fromRGB(sw.colorTo()), 1.3f);
        for (int i = 0; i < sw.trailPoints(); i++) {
            final double s = s0 + (s1 - s0) * (i + 0.5) / sw.trailPoints();
            final double[] p = Geometry.arc(s, sw.trailRadius(), sw.trailDistance(), look, yaw);
            final double x = eye.getX() + p[0];
            final double y = eye.getY() + p[1];
            final double z = eye.getZ() + p[2];
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, x, y, z, 1, 0.03, 0.03, 0.03, 0, dust);
            if (i % 2 == 0) {
                world.spawnParticle(Particle.FLAME, x, y, z, 1, 0.02, 0.02, 0.02, 0.006);
            }
        }
    }

    /** Конец замаха: снять кадр со слота удара; {@code everywhere} — со всего инвентаря (предмет мог переехать). */
    private void endSwing(final boolean everywhere) {
        if (this.swingAge < 0 && !everywhere) {
            return;
        }
        final PlayerInventory inv = this.player.getInventory();
        if (everywhere) {
            ItemTools.stripAll(inv);
            final ItemStack cursor = this.player.getItemOnCursor();
            final ItemStack fixed = ItemTools.withFrame(cursor, null);
            if (fixed != null) {
                this.player.setItemOnCursor(fixed);
            }
        } else if (this.swingSlot >= 0) {
            final ItemStack fixed = ItemTools.withFrame(inv.getItem(this.swingSlot), null);
            if (fixed != null) {
                inv.setItem(this.swingSlot, fixed);
            }
        }
        this.swingAge = -1;
        this.swingSlot = -1;
        this.swingWeapon = null;
        this.shownFrame = null;
    }
}
