package core.amazake.systems.coreskyrift.view;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreskyrift.Markers;
import core.amazake.systems.coreskyrift.Settings;
import core.amazake.systems.coreskyrift.Text;
import core.amazake.systems.coreskyrift.anim.Timeline;
import core.amazake.systems.coreskyrift.event.SkyEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Один игрок в радиусе события: его дисплей неба (виден только ему), боссбар, «ночь», звуки и пепел вокруг.
 *
 * <p><b>Дисплей неба.</b> Item-дисплей с моделью {@code coreskyrift:sky} (три квада: туман 96, разлом 97,
 * вспышка 98) стоит у глаз игрока, невидим для всех ({@code setVisibleByDefault(false)}) и показан только ему
 * ({@code showEntity}). Шейдер ставит квады на дальнюю плоскость во весь экран и рисует небо по направлению взгляда,
 * так что где стоит дисплей — неважно, лишь бы клиент его рисовал (рядом с игроком — всегда: секция загружена,
 * дисплей отслеживается, в дальности видимости). Трансформация переносит квад в точку над центром разлома
 * ({@code translation = якорь − позиция дисплея}): вершины приходят в шейдер как вектор «камера → якорь», отсюда
 * параллакс. Левый поворот задаёт ось и размер разлома (нормаль квада). Игрок ушёл дальше {@link #MOVE_DIST}
 * блоков — дисплей догоняет его телепортом, перенос меняется так же плавно (одинаковые длительности), и якорь стоит.</p>
 *
 * <p>Потоки: всё — в потоке игрока (задача {@code runForPlayer} каждый тик, события игрока); дисплей спавнится в
 * регионе игрока и трогается, только пока он принадлежит этому потоку (иначе убирается задачей своего региона и
 * ставится заново). Планировщик сущностей не используется.</p>
 */
public final class SkyViewer {

    /** Метка дисплеев в PDC: id экземпляра системы (хвосты прошлых запусков убираются при включении). */
    public static final NamespacedKey PART = new NamespacedKey("coreskyrift", "part");
    static final float SCALE = 0.01f;
    /** Раз во столько тиков проверить, не отстал ли дисплей. */
    static final int MOVE_CHECK = 20;
    static final double MOVE_DIST = 8;
    /** Длительность телепорта дисплея и интерполяции переноса, тики (одинаковые: якорь не дёргается). */
    static final int MOVE_TICKS = 3;

    private final SystemContext context;
    private final Player player;
    private final SkyEvent event;
    private final String instance;
    private final java.util.function.Supplier<Settings> live;
    private final Consumer<SkyViewer> onClosed;

    private volatile ScheduledTask task;
    private volatile boolean closed;
    private volatile ItemDisplay display;
    /** Где дисплей стоит (для уборки из чужого потока). */
    private volatile Location displayAt;
    private int fogByte = -1;
    private int riftByte = -1;
    private int flashByte = -1;
    private int ticks;
    private long lastCue;
    private BossBar bar;
    private float barProgress = -1;
    private boolean timeSaved;
    private boolean savedRelative;
    private long savedOffset;
    private long lastTimeSent = Long.MIN_VALUE;

    public SkyViewer(final SystemContext context, final Player player, final SkyEvent event, final String instance,
                     final java.util.function.Supplier<Settings> live, final Consumer<SkyViewer> onClosed) {
        this.context = context;
        this.player = player;
        this.event = event;
        this.instance = instance;
        this.live = live;
        this.onClosed = onClosed;
        this.lastCue = event.tick();
    }

    public Player player() {
        return this.player;
    }

    public SkyEvent event() {
        return this.event;
    }

    public boolean closed() {
        return this.closed;
    }

    /** Начать: задача в потоке игрока каждый тик. Поток игрока. */
    public void start() {
        this.task = this.context.runForPlayer(this.player, t -> this.tick(), 1L);
        if (this.task == null) {
            this.closed = true;
            this.onClosed.accept(this);
            return;
        }
        this.tick();
    }

    // ------------------------------------------------------------------------------------------------ тик

    private void tick() {
        if (this.closed) {
            final ScheduledTask t = this.task;
            if (t != null) {
                t.cancel();
            }
            return;
        }
        final Player p = this.player;
        if (!p.isOnline()) {
            this.leave();
            return;
        }
        final Settings s = this.event.settings;
        final long t = this.event.tick();
        if (this.event.finished() || !p.getWorld().equals(this.event.world)) {
            this.leave();
            return;
        }
        final Location eye = p.getEyeLocation();
        if (this.event.horizontalDistance(eye.getX(), eye.getZ()) > s.event().leaveRadius()) {
            this.leave();
            return;
        }
        if (t < 0) {
            return;
        }
        this.ticks++;
        final Timeline tl = this.event.timeline();
        final double fog = tl.fog(t);
        final double rift = tl.rift(t);
        final double flash = s.env().screenFlash() ? tl.flash(t) : 0;
        this.updateDisplay(eye, Markers.paramByte(fog), Markers.paramByte(rift), Markers.paramByte(flash), s);
        this.updateBossBar(tl, t, s);
        this.updateTime(fog, s);
        final Settings.Effects fx = this.live.get().effects();
        this.cues(t, tl, s, fx, eye);
        if (fx.ambientParticles() && this.ticks % 4 == 0) {
            this.ambient(eye, fog, rift);
        }
        if (fx.sounds() && tl.stage(t) == Timeline.Stage.ACTIVE && this.ticks % 120 == 60) {
            p.playSound(eye, Sound.AMBIENT_BASALT_DELTAS_MOOD, SoundCategory.AMBIENT, 0.8f, 0.6f);
        }
    }

    // --------------------------------------------------------------------------------------------- дисплей

    private void updateDisplay(final Location eye, final int fog, final int rift, final int flash, final Settings s) {
        ItemDisplay d = this.display;
        if (d != null && (!d.isValid() || !Bukkit.isOwnedByCurrentRegion(d))) {
            this.removeDisplay();
            d = null;
        }
        if (fog == 0 && rift == 0 && flash == 0) {
            // Ещё не появился или уже погас: дисплей не нужен.
            if (d != null && this.event.timeline().stage(this.event.tick()) == Timeline.Stage.FOG_OUT) {
                this.removeDisplay();
            }
            if (d == null) {
                return;
            }
        }
        if (d == null) {
            final Location at = new Location(eye.getWorld(), eye.getX(), eye.getY(), eye.getZ(), 0f, 0f);
            if (!Bukkit.isOwnedByCurrentRegion(at)) {
                return;
            }
            d = this.spawn(at, fog, rift, flash, s);
            this.display = d;
            this.displayAt = at;
            this.fogByte = fog;
            this.riftByte = rift;
            this.flashByte = flash;
            this.player.showEntity(this.context.plugin(), d);
            return;
        }
        if (fog != this.fogByte || rift != this.riftByte || flash != this.flashByte) {
            this.fogByte = fog;
            this.riftByte = rift;
            this.flashByte = flash;
            d.setItemStack(Markers.skyItem(fog, rift, flash));
        }
        if (this.ticks % MOVE_CHECK == 0) {
            final Location at = this.displayAt;
            if (at != null && at.distanceSquared(eye) > MOVE_DIST * MOVE_DIST) {
                this.follow(d, eye);
            }
        }
    }

    private ItemDisplay spawn(final Location at, final int fog, final int rift, final int flash, final Settings s) {
        final Vector3f translation = this.toAnchor(at);
        return at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setVisibleByDefault(false);
            e.setPersistent(false);
            e.setItemStack(Markers.skyItem(fog, rift, flash));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBillboard(Display.Billboard.FIXED);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(s.event().viewRange());
            e.setDisplayWidth(0f);
            e.setDisplayHeight(0f);
            e.setShadowRadius(0f);
            e.setShadowStrength(0f);
            e.setTeleportDuration(MOVE_TICKS);
            e.setInterpolationDelay(0);
            e.setInterpolationDuration(0);
            e.setTransformation(this.transformation(translation));
            e.getPersistentDataContainer().set(PART, PersistentDataType.STRING, this.instance);
        });
    }

    /** Дисплей догоняет глаза: телепорт и новый перенос с одинаковой длительностью. */
    private void follow(final ItemDisplay d, final Location eye) {
        final Location target = new Location(eye.getWorld(), eye.getX(), eye.getY(), eye.getZ(), 0f, 0f);
        if (!Bukkit.isOwnedByCurrentRegion(target)) {
            this.removeDisplay();   // глаза в другом регионе: на следующем тике дисплей встанет заново
            return;
        }
        d.teleportAsync(target).thenAccept(ok -> {
            if (!Boolean.TRUE.equals(ok) || !d.isValid() || !Bukkit.isOwnedByCurrentRegion(d)) {
                return;
            }
            this.displayAt = target;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(MOVE_TICKS);
            d.setTransformation(this.transformation(this.toAnchor(target)));
        });
    }

    private Vector3f toAnchor(final Location at) {
        return new Vector3f((float) (this.event.x - at.getX()), (float) (this.event.anchorY - at.getY()),
            (float) (this.event.z - at.getZ()));
    }

    private Transformation transformation(final Vector3f translation) {
        return new Transformation(translation, new Quaternionf(this.event.rotation), new Vector3f(SCALE, SCALE, SCALE),
            new Quaternionf());
    }

    /** Убрать дисплей из любого потока: свой регион — сразу, иначе задачей региона дисплея. */
    private void removeDisplay() {
        final ItemDisplay d = this.display;
        final Location at = this.displayAt;
        this.display = null;
        this.displayAt = null;
        this.fogByte = -1;
        this.riftByte = -1;
        this.flashByte = -1;
        if (d == null) {
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(d)) {
            d.remove();
        } else if (at != null) {
            Bukkit.getRegionScheduler().execute(this.context.plugin(), at, () -> {
                if (d.isValid()) {
                    d.remove();
                }
            });
        }
    }

    // ------------------------------------------------------------------------------------------- окружение

    private void updateBossBar(final Timeline tl, final long t, final Settings s) {
        final Settings.Env env = s.env();
        if (!env.bossBar()) {
            return;
        }
        final float progress = (float) tl.remaining(t);
        if (this.bar == null) {
            final Set<BossBar.Flag> flags = EnumSet.noneOf(BossBar.Flag.class);
            if (env.darkenSky()) {
                flags.add(BossBar.Flag.DARKEN_SCREEN);
            }
            if (env.createFog()) {
                flags.add(BossBar.Flag.CREATE_WORLD_FOG);
            }
            this.bar = BossBar.bossBar(Text.parse(env.bossBarTitle()), progress, BossBar.Color.RED,
                BossBar.Overlay.NOTCHED_20, flags);
            this.barProgress = progress;
            this.player.showBossBar(this.bar);
        } else if (Math.abs(progress - this.barProgress) >= 0.005f) {
            this.barProgress = progress;
            this.bar.progress(progress);
        }
    }

    /** «Ночь» только этому игроку: от его времени к {@code night-time} по мере тумана, назад — так же. */
    private void updateTime(final double fog, final Settings s) {
        final Settings.Env env = s.env();
        if (!env.night()) {
            return;
        }
        final Player p = this.player;
        if (!this.timeSaved) {
            this.timeSaved = true;
            this.savedRelative = p.isPlayerTimeRelative();
            this.savedOffset = p.getPlayerTimeOffset();
        }
        final long cur = this.savedRelative ? p.getWorld().getFullTime() + this.savedOffset : this.savedOffset;
        final long delta = Math.floorMod(env.nightTime() - Math.floorMod(cur, 24000L), 24000L);
        final long time = cur + Math.round(delta * fog);
        if (fog <= 0.0005) {
            if (this.lastTimeSent != Long.MIN_VALUE) {
                this.restoreTime();
            }
            return;
        }
        if (time != this.lastTimeSent) {
            this.lastTimeSent = time;
            p.setPlayerTime(time, false);
        }
    }

    private void restoreTime() {
        if (!this.timeSaved) {
            return;
        }
        final Player p = this.player;
        if (this.savedRelative && this.savedOffset == 0) {
            p.resetPlayerTime();
        } else {
            p.setPlayerTime(this.savedOffset, this.savedRelative);
        }
        this.lastTimeSent = Long.MIN_VALUE;
    }

    // ------------------------------------------------------------------------------------- звуки и частицы

    private void cues(final long t, final Timeline tl, final Settings s, final Settings.Effects fx, final Location eye) {
        final long from = Math.max(this.lastCue + 1, t - 40);
        this.lastCue = t;
        for (long k = from; k <= t; k++) {
            final Timeline.Cue cue = tl.cue(k);
            if (cue != null) {
                this.play(cue, s, fx, eye);
            }
        }
    }

    private void play(final Timeline.Cue cue, final Settings s, final Settings.Effects fx, final Location eye) {
        final Player p = this.player;
        if (cue == Timeline.Cue.CRACK && s.env().darknessPulseTicks() > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, s.env().darknessPulseTicks(), 0, false, false,
                false));
        }
        if (!fx.sounds()) {
            return;
        }
        final Location sky = this.towardRift(eye, 24);
        switch (cue) {
            case CAST -> {
                p.playSound(eye, Sound.ENTITY_WITHER_SPAWN, SoundCategory.AMBIENT, 0.35f, 0.55f);
                p.playSound(sky, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.AMBIENT, 0.7f, 0.5f);
            }
            case CRACK -> {
                if (fx.thunder()) {
                    p.playSound(sky, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 3.0f, 0.55f);
                }
                p.playSound(sky, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.AMBIENT, 0.6f, 0.45f);
            }
            case RUMBLE -> {
                if (fx.thunder()) {
                    p.playSound(sky, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 1.2f,
                        0.45f + ThreadLocalRandom.current().nextFloat() * 0.2f);
                }
                p.playSound(sky, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.AMBIENT, 0.5f, 0.5f);
            }
            case OPEN -> {
                p.playSound(sky, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.AMBIENT, 1.0f, 0.4f);
                p.playSound(sky, Sound.ENTITY_WITHER_AMBIENT, SoundCategory.AMBIENT, 0.8f, 0.5f);
            }
            case CLOSE -> {
                p.playSound(sky, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, SoundCategory.AMBIENT, 1.0f, 0.5f);
                if (fx.thunder()) {
                    p.playSound(sky, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 1.5f, 0.8f);
                }
            }
            case FADE -> p.playSound(eye, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.AMBIENT, 0.5f, 0.5f);
            default -> {
            }
        }
    }

    /** Точка на {@code dist} блоков от глаз в сторону якоря разлома (звук «с неба»). */
    private Location towardRift(final Location eye, final double dist) {
        final Vector dir = new Vector(this.event.x - eye.getX(), this.event.anchorY - eye.getY(), this.event.z - eye.getZ());
        final double len = dir.length();
        if (len < 1e-3) {
            return eye.clone().add(0, dist, 0);
        }
        return eye.clone().add(dir.multiply(dist / len));
    }

    /** Пепел, кровавая пыль и капли лавы вокруг игрока — только ему. */
    private void ambient(final Location eye, final double fog, final double rift) {
        final Player p = this.player;
        if (fog > 0.3) {
            p.spawnParticle(Particle.ASH, eye.clone().add(0, 3, 0), (int) (6 + 14 * fog), 10, 5, 10, 0);
        }
        if (rift > 0.25) {
            p.spawnParticle(Particle.DUST, eye.clone().add(0, 5, 0), 5, 12, 5, 12, 0,
                new Particle.DustOptions(Color.fromRGB(176, 0, 16), 1.4f));
            if (ThreadLocalRandom.current().nextInt(3) == 0) {
                p.spawnParticle(Particle.FALLING_LAVA, eye.clone().add(0, 14, 0), 3, 16, 2, 16, 0);
            }
        }
    }

    // --------------------------------------------------------------------------------------------- уборка

    /** Игрок вышел из радиуса, событие кончилось и т. п.: вернуть всё и убрать дисплей. Поток игрока. */
    public void leave() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        final ScheduledTask t = this.task;
        if (t != null) {
            t.cancel();
        }
        this.restorePlayer();
        this.removeDisplay();
        this.onClosed.accept(this);
    }

    private void restorePlayer() {
        if (this.bar != null) {
            this.player.hideBossBar(this.bar);
            this.bar = null;
        }
        this.restoreTime();
    }

    /**
     * Выключение системы: задача отменяется, дисплей убирается (свой регион — сразу, иначе задачей его региона),
     * боссбар и время возвращаются в потоке игрока. Любой поток.
     */
    public void shutdown() {
        this.closed = true;
        final ScheduledTask t = this.task;
        if (t != null) {
            t.cancel();
        }
        this.context.runOnceForPlayer(this.player, this::restorePlayer);
        this.removeDisplay();
    }
}
