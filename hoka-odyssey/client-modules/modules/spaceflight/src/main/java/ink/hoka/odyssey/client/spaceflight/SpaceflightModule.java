package ink.hoka.odyssey.client.spaceflight;

import com.mojang.blaze3d.platform.InputConstants;
import ink.hoka.odyssey.client.api.CameraState;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import ink.hoka.odyssey.spacephys.Body;
import ink.hoka.odyssey.spacephys.Field;
import ink.hoka.odyssey.spacephys.Integrator;
import ink.hoka.odyssey.spacephys.Quat;
import ink.hoka.odyssey.spacephys.Recoil;
import ink.hoka.odyssey.spacephys.State;
import ink.hoka.odyssey.spacephys.Thrust;
import ink.hoka.odyssey.spacephys.Units;
import ink.hoka.odyssey.spacephys.VanillaMotion;
import ink.hoka.odyssey.spacephys.Vec3d;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Живая невесомость в вакууме (мод 0.48+). Включает и выключает её сервер (модуль gravity), он же присылает
 * список тяготеющих тел. Пока включено:
 * <ul>
 *   <li>инерция: ни сопротивления, ни «ванильной» гравитации 0,08 — скорость меняют только тяготение тел (1/r²,
 *       общая библиотека spacephys), тяга ранца и удары о блоки;</li>
 *   <li>тяга: W/S, A/D, пробел/shift — вдоль осей взгляда (вперёд, вбок, вверх головы), без «горизонта»;
 *       стоя на блоке — шаг и прыжок с отталкиванием от поверхности (только по новому нажатию пробела);</li>
 *   <li>стабилизатор: без клавиш скорость плавно гаснет (stabilizer), X — тормоз (brake);</li>
 *   <li>свободная камера: ориентация — кватернион, мышь вращает в своих осях, крен — клавиши (Z/C по умолчанию),
 *       петля через верх проходит без упора в ±90°. Поворот игрока (прицел, ломание блоков) следует за взглядом;</li>
 *   <li>тяготение грани ({@link SurfaceGravity}): коснулся ногами любой грани блока (пол, стена, потолок) — она
 *       тянет к себе, как земля: шаг, бег, прыжок и падение обратно — как у vanilla, камера ровная в системе грани.
 *       Грань заперта (поворот головы её не меняет), внутренний угол и ребро переводят на соседнюю. Shift+пробел или
 *       двойной пробел — оттолкнуться в свободный полёт;</li>
 *   <li>рамка (мод 0.54+, крючки onEntityBox/onEntityEye): стоя на стене или потолке, рамка игрока повёрнута —
 *       1.8 вдоль нормали грани ({@link SurfaceBox}), глаза у головы; по блокам мира её считает сама игра (и сервер
 *       — модуль gravity ставит такую же рамку). В свободном полёте рамка обычная, как только для неё есть место;</li>
 *   <li>тело ({@link BodyFrame}, {@link BodyPoses}; крючок ядра onEntityPose): стоишь на поверхности (тяготение
 *       грани или касаешься блока, который «под ногами» — его нормаль близка к верху камеры) — ноги на ней, лицом
 *       по взгляду; свободно в пространстве — тело целиком повёрнуто к прицелу. «Лёжа» — переключает R. Поворот
 *       тела уходит серверу, тот раздаёт его другим игрокам.</li>
 * </ul>
 * Вне вакуума камера плавно возвращается «в горизонт» и дальше не трогается; всё восстанавливается при выключении.
 *
 * <p>Протокол (big-endian). Сервер → клиент: op 1 — тела: u8 op, i32 count, на тело f64 x, y, z, mu, radius,
 * softening; op 2 — физика: u8 op, u8 on [, f64 thrust, f64 maxSpeed — пределы сервера]; op 3 — поворот тела
 * другого игрока: u8 op, i32 entityId, f32 w, x, y, z, u8 flags (бит 0 — лёжа, бит 1 — действует, биты 2–4 — грань
 * под ногами + 1, 0 — свободно; бит 5 — рамка повёрнута по этой грани; бит 6 — тяготение грани); op 4 — поворот больше не действует: u8 op,
 * i32 entityId.
 * Клиент → сервер: op 3 — свой поворот: u8 op, f32 w, x, y, z (кватернион ТЕЛА без «лёжа», соглашение FreeLook),
 * u8 flags; op 5 — возможности: u8 op, u8 caps (бит 0 — рисуем повёрнутые тела сами); op 6 — «модуль загружен»:
 * u8 op (сервер заново шлёт тела и «физика вкл.» — после /hoclient reload).
 *
 * <p>Скорость модуль ведёт сам ({@link VanillaMotion#reconcile}): от vanilla берётся только удар о блок и толчки
 * сервера. Иначе vanilla добавлял бы свой ввод WASD и обнулял слабую тягу (меньше 0,003 блока/тик).
 */
public final class SpaceflightModule implements ClientModule {
    static final int OP_BODIES = 1;
    static final int OP_PHYSICS = 2;
    static final int OP_HELLO = 6;
    /** Сдвиг за тик больше ожидаемого на столько блоков — телепорт (сервер вернул назад, /tp): скорость — от vanilla. */
    private static final double TELEPORT_JUMP = 1.0;
    static final int MAX_BODIES = 4096;
    private static final int BODY_BYTES = 6 * 8;
    /** Касание блока: зазор между рамкой игрока и блоком меньше этого, блоки. */
    private static final double CONTACT = 0.05;
    /** Сошёл с грани (ребро): касание боковой грани того же блока ищем с таким зазором, блоки. */
    private static final double LOST_GAP = 0.35;
    /** Сошёл с грани, а ниже (вдоль −n) блок ближе этого — это ступенька: падаем на неё, а не переходим на ребро. */
    private static final double STEP_DEPTH = 1.3;
    /** Без опоры, и ниже (вдоль −n) нет блока ближе этого — «пустота»: через SurfaceGravity.GRACE тиков — полёт. */
    private static final double VOID_DEPTH = 3.0;
    /** Внутренний угол — только если соседняя грань выше шага: касание на этой высоте от ног и выше, блоки. */
    private static final double WALL_FROM = 1.0;
    /** После выхода из тяготения грани (оттолкнулся, сошёл в пустоту) — столько тиков не входить снова. */
    private static final int REENTRY = 10;
    /** Шаг, которым новая рамка придвигается к грани при смене у ребра, блоки (меньше зазора касания 2·CONTACT). */
    private static final double SNAP_STEP = 0.05;
    /** Свой поворот серверу — не чаще чем раз в столько тиков (~10 раз в секунду)… */
    private static final int ORIENT_EVERY = 2;
    /** …и только если повернулся больше чем на столько градусов; без изменений — раз в столько тиков. */
    private static final double ORIENT_MIN_DEG = 1.0;
    private static final int ORIENT_KEEPALIVE = 40;
    /** Касание считается «под ногами», если нормаль блока ближе к верху камеры, чем на 60°. */
    private static final double FEET_DOT = 0.5;
    /** Поверхность под ногами пропала — ещё столько тиков считать, что стоим (без дрожи на отскоках). */
    private static final int STAND_HOLD = 4;

    private ClientContext ctx;
    private FlightConfig cfg;
    private InputConstants.Key rollLeftKey;
    private InputConstants.Key rollRightKey;
    private InputConstants.Key brakeKey;
    private InputConstants.Key proneKey;

    private volatile Field field = Field.EMPTY;
    private volatile boolean serverOn;
    /** Пределы сервера из op 2: тяга (б/с², &lt;0 — не прислал) и скорость (б/с, 0 — нет предела). */
    private volatile double serverThrust = -1;
    private volatile double serverMaxSpeed;
    /** «Модуль загружен» серверу уже сказали (на этом подключении). */
    private boolean helloSent;

    private final FreeLook look = new FreeLook();
    /** Камера под управлением модуля: в вакууме и пока она возвращается «в горизонт» после него. */
    private boolean lookOwned;
    private long lastFrame;

    private boolean active;
    private LocalPlayer bound;
    private boolean savedNoGravity;
    private boolean savedDiscardFriction;
    private boolean jumpHeld;
    private final State state = new State();
    /** Скорость, поставленная игроку в прошлый тик, блоки/тик, и позиция после неё (null — первый тик). */
    private Vec3 lastSet;
    private Vec3 lastPos;
    /**
     * Отдача выстрелов (модуль guns, {@link #RECOIL}): затухающая скорость поверх своей, только в свободном полёте.
     * recoilSent — её доля в lastSet (блоки/тик), freeFlight — прошлый тик был свободный полёт (без опоры и касаний).
     */
    private final Recoil recoil = new Recoil();
    private Vec3d recoilSent = Vec3d.ZERO;
    private boolean freeFlight;

    /** Тяготение грани: встал ногами на пол/стену/потолок — ходишь по ней, как по земле. */
    private final SurfaceGravity grav = new SurfaceGravity();
    /** Тиков до того, как можно снова встать на грань (после выхода). */
    private int reentry;
    /** Тяготение грани: тики без опоры, «пустота» ниже, пауза прыжка, прыжок был, тик прыжка. */
    private int airTicks;
    private int voidTicks;
    private int jumpDelay;
    private boolean jumped;
    private long jumpTick;
    /** Ядро умеет свою рамку и глаза (onEntityBox/onEntityEye, мод 0.54+). */
    private boolean boxApi;
    /** Крючки рамки ещё действуют (модуль не выгружается). */
    private volatile boolean boxHooksOn = true;
    /** Грань, по которой повёрнута рамка своего игрока (стена/потолок), −1 — рамка обычная. */
    private volatile int boxSurface = -1;
    /** Поправка камеры после смены рамки (мир, блоки): плавно гаснет — глаза не прыгают вместе с рамкой. */
    private Vec3d eyeCorr = Vec3d.ZERO;
    /** Переезд глаз после смены рамки (по дуге вокруг ребра при смене грани); null — не едем. */
    private Glide eyeGlide;
    /** Переходов на грань / между гранями / смен рамки — для плавного перехода позы тела ({@link BodyPoses}). */
    private int transitions;
    /** Последний переход — смена грани (тело едет вместе с камерой), а не вход на грань. */
    private boolean transferred;
    /** Рамка сменилась — поворот и флаги серверу сразу (до пакета движения со сдвигом позиции). */
    private boolean orientNow;
    /** Лёжа (тело вдоль взгляда) — переключается клавишей R в невесомости. */
    private boolean prone;
    private boolean sprintHeld;
    /** Плавная доля «лёжа» — для запасной позы плавания (ядро без onEntityPose). */
    private float proneAmount;
    /** Грань под ногами (0..5 — {@link BodyFrame#NORMALS}) или −1 — свободно в пространстве. */
    private int standSurface = -1;
    private int standLost;

    private BodyPoses bodies;
    private long ticks;
    private Quat sentBody;
    private int sentFlags = -1;
    private long sentAt;

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        cfg = FlightConfig.load(ctx.dataDir(), ctx.logger());
        rollLeftKey = key(cfg.rollLeft, "key.keyboard.z");
        rollRightKey = key(cfg.rollRight, "key.keyboard.c");
        brakeKey = key(cfg.brakeKey, "key.keyboard.x");
        proneKey = key(cfg.proneKey, "key.keyboard.r");
        bodies = new BodyPoses(ctx, new BodyPoses.Self() {
            @Override public Quat base() {
                if (!active) return null;
                return grav.active() ? grav.body(System.nanoTime()) : BodyFrame.base(look.orientation(), standSurface);
            }

            @Override public int surface() { return standSurface; }

            @Override public boolean prone() { return prone; }

            @Override public int box() { return boxSurface; }

            @Override public int transitions() { return transitions; }

            @Override public boolean continuous() { return grav.active() && transferred && !prone; }
        });
        bodies.surfaceBlend(cfg.surfaceTurnTime);
        grav.turnSeconds(cfg.surfaceTurnTime);
        ctx.onMessage(this::onMessage);
        ctx.onTick(this::tick);
        ctx.onMouse(this::onMouse);
        System.getProperties().put(LOOK_KICK, lookKick);
        System.getProperties().put(RECOIL, recoilHandOff);
        ctx.onCamera(this::onCamera);
        ctx.onInput(this::onInput);
        bodies.install();
        if (BoxHooks.available(ctx)) {
            BoxHooks.install(ctx, this::entityBox, this::entityEye);
            boxApi = true;
            ctx.logger().info("spaceflight: на стене и потолке рамка игрока поворачивается (onEntityBox/onEntityEye)");
        } else {
            ctx.logger().info("spaceflight: в ядре нет onEntityBox/onEntityEye — рамка на стенах остаётся ровной "
                    + "(docs/mod-additions, мод 0.54+)");
        }
        ctx.logger().info("spaceflight: загружен (тяга {} б/с², предел {} б/с, стабилизатор {} б/с², тормоз {} — {}, "
                + "крен {}/{})", cfg.thrust, cfg.maxSpeed, cfg.stabilizer, cfg.brake, cfg.brakeKey, cfg.rollLeft,
                cfg.rollRight);
    }

    @Override
    public void disable() {
        System.getProperties().remove(LOOK_KICK, lookKick);
        System.getProperties().remove(RECOIL, recoilHandOff);
        if (active) deactivate();
        boxHooksOn = false;                                        // крючки ядро снимет после disable — уже сейчас null
        refreshRemoteBoxes(bodies == null ? List.of() : bodies.boxed());
        if (bodies != null) bodies.disable();
        lookOwned = false;
        serverOn = false;
        field = Field.EMPTY;
    }

    /**
     * Поворот взгляда от другого модуля (отдача оружия модуля guns): в невесомости взгляд держит spaceflight, и
     * поворот игрока «снаружи» он бы затёр — поэтому guns поворачивает через нас. Тот же путь, что у мыши: true —
     * повернули (взгляд наш), false — взгляд обычный, пусть поворачивает сам. Связь через System.getProperties —
     * загрузчики классов модулей разные, BiFunction из JDK общий.
     */
    static final String LOOK_KICK = "hoka.spaceflight.lookKick";
    private final java.util.function.BiFunction<Double, Double, Boolean> lookKick =
            (yaw, pitch) -> yaw != null && pitch != null && onMouse(yaw, pitch);

    /**
     * Отдача выстрела от модуля guns: double[] {dx, dy, dz} — куда и на сколько блоков отнести (длина — путь). Только
     * в свободном полёте (в невесомости, не на грани, не на земле, не касаясь блоков): true — приняли (отнесём сами,
     * сервер про это знает и не толкает), false — не в свободном полёте, отдачи нет. Связь, как у {@link #LOOK_KICK}.
     */
    static final String RECOIL = "hoka.spaceflight.recoil";
    private final java.util.function.Predicate<double[]> recoilHandOff = d -> {
        if (d == null || d.length < 3 || !active || !freeFlight || grav.active()) return false;
        recoil.add(d[0], d[1], d[2]);
        return true;
    };

    /** В основной руке оружие модуля guns (метка hoka_gun в custom_data) — тогда R у него (перезарядка), не «лечь». */
    static boolean holdingGun(LocalPlayer p) {
        var cd = p.getMainHandItem().get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return cd != null && !cd.isEmpty() && !cd.copyTag().getStringOr("hoka_gun", "").isEmpty();
    }

    private InputConstants.Key key(String name, String fallback) {
        try {
            return InputConstants.getKey(name);
        } catch (RuntimeException e) {
            ctx.logger().warn("spaceflight: неизвестная клавиша «{}» — используется {}", name, fallback);
            return InputConstants.getKey(fallback);
        }
    }

    // ---------------------------------------------------------------- сеть

    private void onMessage(byte[] data) {
        if (data == null || data.length < 1) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int op = in.readUnsignedByte();
            switch (op) {
                case OP_BODIES -> {
                    field = readBodies(in, data.length - 1);
                    sendCaps();                                    // сервер (пере)знакомится с нами
                }
                case OP_PHYSICS -> {
                    if (data.length < 2) return;
                    boolean on = in.readUnsignedByte() != 0;
                    if (on && data.length >= 18) {                 // пределы сервера (новая версия gravity)
                        double t = in.readDouble(), m = in.readDouble();
                        serverThrust = Double.isFinite(t) && t >= 0 ? t : -1;
                        serverMaxSpeed = Double.isFinite(m) && m > 0 ? m : 0;
                    } else if (on) {
                        serverThrust = -1;                         // старый сервер — свои настройки
                        serverMaxSpeed = 0;
                    }
                    serverOn = on;
                }
                case BodyPoses.OP_ORIENT -> bodies.readOrientation(in, data.length - 1);
                case BodyPoses.OP_ORIENT_STOP -> bodies.readStop(in, data.length - 1);
                default -> { }                                     // незнакомые операции — от новой версии сервера
            }
        } catch (IOException | RuntimeException e) {
            ctx.logger().warn("spaceflight: битое сообщение сервера ({} байт): {}", data.length, e.toString());
        }
    }

    static Field readBodies(DataInputStream in, int available) throws IOException {
        if (available < 4) throw new IOException("нет числа тел");
        int count = in.readInt();
        if (count < 0 || count > MAX_BODIES || (long) count * BODY_BYTES > available - 4L) {
            throw new IOException("неверное число тел: " + count);
        }
        List<Body> bodies = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            double mu = in.readDouble(), radius = in.readDouble(), softening = in.readDouble();
            if (!(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z))
                    || !(mu >= 0 && mu < 1e15) || !(radius >= 0 && radius < 1e9) || !(softening >= 0 && softening < 1e9)) {
                continue;                                          // мусор — пропускаем тело, остальные берём
            }
            String name = "b" + i;
            bodies.add(softening > 0
                    ? Body.small(name, x, y, z, mu, softening, 0)
                    : Body.planet(name, x, y, z, mu, radius));
        }
        return bodies.isEmpty() ? Field.EMPTY : Field.of(bodies);
    }

    // ---------------------------------------------------------------- тик

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ticks++;
        if (mc.level == null) {                                    // вышли с сервера
            serverOn = false;
            field = Field.EMPTY;
            bodies.clear();
            sentFlags = -1;
            helloSent = false;
        } else if (!helloSent && p != null) {
            // модуль (пере)загрузился: пусть сервер заново пришлёт тела и «физика вкл.» (после /hoclient reload
            // новый экземпляр модуля ничего не знает; старый сервер op 6 просто не читает)
            helloSent = true;
            send(new byte[] {OP_HELLO});
        }
        if (ticks % 20 == 0) bodies.prune();
        bodies.drainBoxChanges(id -> refreshRemoteBoxes(List.of(id)));
        if (active && p != bound) deactivate();                    // смена мира / возрождение — новый объект игрока
        boolean want = serverOn && p != null && !p.isSpectator() && !p.isPassenger()
                && !p.getAbilities().flying && p.isAlive();
        if (want && !active) activate(p);
        else if (!want && active) deactivate();
        if (active) {
            physics(mc, p);
            sendOrientation(false);
            Vec3d n = grav.normal();
            if (n != null && n.y() == 0) walkAlong(p, n);          // ноги по шагу вдоль стены
        }
        if (mc.level != null && bodies.hooked()) {
            for (Player o : mc.level.players()) {                  // ноги других игроков на стенах
                if (o == p) continue;
                Vec3d n = BodyFrame.normal(bodies.surface(o.getId()));
                if (n != null && n.y() == 0) walkAlong(o, n);
            }
        }
    }

    private void activate(LocalPlayer p) {
        bound = p;
        active = true;
        savedNoGravity = p.isNoGravity();
        savedDiscardFriction = p.shouldDiscardFriction();
        p.setNoGravity(true);
        p.setDiscardFriction(true);
        if (!lookOwned) look.reset(p.getYRot(), p.getXRot());
        lookOwned = true;
        grav.leave();
        reentry = 0;
        boxSurface = -1;
        eyeCorr = Vec3d.ZERO;
        eyeGlide = null;
        jumpHeld = true;                                           // не прыгать от пробела, зажатого до входа
        sprintHeld = true;                                         // и не ложиться от R, зажатой до входа
        prone = false;
        proneAmount = 0;
        standSurface = -1;
        standLost = 0;
        lastSet = null;                                            // первый тик — скорость как есть у vanilla
        lastPos = null;
        clearRecoil();
    }

    private void deactivate() {
        LocalPlayer p = bound;
        if (p != null && boxSurface >= 0 && !switchBox(p, -1, false)) {
            boxSurface = -1;                                       // места стоя нет — всё равно обычная рамка:
            p.setPos(p.getX(), p.getY(), p.getZ());                // игра сама вытолкнет из блоков
        }
        boxSurface = -1;
        eyeCorr = Vec3d.ZERO;
        eyeGlide = null;
        grav.leave();
        clearRecoil();
        sendOrientation(true);                                     // «поворот больше не действует»
        active = false;
        prone = false;
        proneAmount = 0;
        standSurface = -1;
        bound = null;
        if (p != null) {
            p.setNoGravity(savedNoGravity);
            p.setDiscardFriction(savedDiscardFriction);
        }
    }

    /**
     * Ввод vanilla (крючок ядра onInput, после того как игра прочла клавиши): в невесомости — пустой, ни ходьбы, ни
     * приседа, ни бега, ни прыжка. Движением управляет модуль, клавиши он читает сам (с учётом переназначения).
     * Вне невесомости — не трогаем: ввод снова обычный уже со следующего тика.
     */
    private void onInput(ClientInput input) {
        if (!active || input == null) return;
        input.keyPresses = Input.EMPTY;
        ctx.setMove(input, 0, 0);
    }

    private void physics(Minecraft mc, LocalPlayer p) {
        Options o = mc.options;
        boolean input = mc.screen == null;
        int fwd = 0, side = 0, vert = 0;
        boolean jump = false, shift = false, proneDown = false;
        if (input) {
            fwd = (down(mc, o.keyUp) ? 1 : 0) - (down(mc, o.keyDown) ? 1 : 0);
            side = (down(mc, o.keyRight) ? 1 : 0) - (down(mc, o.keyLeft) ? 1 : 0);
            jump = down(mc, o.keyJump);
            shift = down(mc, o.keyShift);
            proneDown = physical(mc, proneKey) && !holdingGun(p);
            vert = (jump ? 1 : 0) - (shift ? 1 : 0);
        }
        // ввод vanilla пустой (onInput): ходьбу, прыжок, присед и бег игра не видит, движением управляет модуль.
        // Бег и присед, включённые до входа в вакуум, — снять.
        if (p.isSprinting()) p.setSprinting(false);
        if (p.isShiftKeyDown()) p.setShiftKeyDown(false);
        if (proneDown && !sprintHeld) {                            // R — лечь / встать
            prone = !prone;
            p.displayClientMessage(Component.literal(prone ? "Лёжа: тело вдоль взгляда (R — встать)"
                    : "Стоя (R — лечь)"), true);
        }
        sprintHeld = proneDown;

        // база — своя скорость прошлого тика; от vanilla move берём только удар о блок (ось обнулилась) и толчки
        // сервера (заметно больше ввода клавиш). Ввод WASD vanilla и обнуление мелочи (< 0,003 блока/тик) — нет:
        // иначе ввод был бы «бесплатной» тягой до 10 б/с², а слабая тяга из покоя не разгоняла бы вовсе.
        Vec3 d = p.getDeltaMovement();
        Vec3 pos = p.position();
        Vec3d base;
        if (lastSet == null || lastPos == null
                || pos.subtract(lastPos).subtract(lastSet).length() > TELEPORT_JUMP) {
            base = new Vec3d(d.x, d.y, d.z);                       // первый тик или телепорт — как есть
            clearRecoil();
            if (grav.active()) {                                   // телепорт — грань заново по месту, куда попал
                grav.leave();
                reentry = 0;
            }
        } else {
            base = VanillaMotion.reconcile(new Vec3d(lastSet.x, lastSet.y, lastSet.z), new Vec3d(d.x, d.y, d.z),
                    p.horizontalCollision, p.verticalCollision);
            // отдача прошлого тика — не своя скорость: вычесть (упёрся в блок — ось и так 0)
            base = new Vec3d(Recoil.own(base.x(), lastSet.x, recoilSent.x()), Recoil.own(base.y(), lastSet.y,
                    recoilSent.y()), Recoil.own(base.z(), lastSet.z, recoilSent.z()));
        }
        recoilSent = Vec3d.ZERO;
        state.set(p.getX(), p.getY() + p.getBbHeight() * 0.5, p.getZ(),
                Units.fromPerTick(base.x()), Units.fromPerTick(base.y()), Units.fromPerTick(base.z()));
        boolean brake = input && physical(mc, brakeKey);

        Quat q = look.orientation();
        if (reentry > 0) reentry--;
        if (!grav.active() && reentry == 0) {
            // свободный полёт: коснулся грани стороной ног (или приземлился на неё) — встать на неё
            AABB bb = p.getBoundingBox();
            double gap = p.horizontalCollision || p.verticalCollision ? CONTACT * 2 : CONTACT;
            Vec3d was = lastSet == null ? base : new Vec3d(lastSet.x, lastSet.y, lastSet.z);
            int f = SurfaceGravity.entry(i -> touching(p, bb, i, gap), q.up(), was);
            if (f >= 0) enterSurface(p, f);
        }
        if (grav.active()) {
            clearRecoil();                                         // на грани отдача не толкает
            surfaceStep(mc, p, base, fwd, side, jump, shift);
            return;
        }
        // касание блока: из граней рядом — та, чья нормаль ближе всего к верху камеры («на ней и стоим»)
        Vec3d contact = surface(p, q.up());
        // рамка в свободном полёте — обычная, как только для неё есть место (после выхода со стены/потолка)
        if (boxApi && boxSurface >= 0) switchBox(p, -1, false);
        if (boxSurface >= 0) {
            standSurface = boxSurface;                             // рамка ещё повёрнута — тело по ней
            standLost = 0;
        } else {
            updateStand(contact != null && contact.dot(q.up()) > FEET_DOT ? contact : null);
        }
        double ax = 0, ay = 0, az = 0;
        boolean grounded = p.onGround();
        // свободный полёт: ни на земле, ни у блока (касание любой гранью), рамка обычная — только тут отдача толкает
        freeFlight = !grounded && contact == null && boxSurface < 0;
        if (!freeFlight) recoil.clear();
        boolean keys = fwd != 0 || side != 0 || vert != 0;
        if (!grounded && brake) {
            Vec3d a = Thrust.damp(state.velocity(), cfg.brake, Units.TICK);   // тормоз — поверх ранца
            ax = a.x();
            ay = a.y();
            az = a.z();
        } else if (!grounded && keys) {
            Vec3d f = q.forward(), r = q.right(), u = q.up();
            double tx = f.x() * fwd + r.x() * side + u.x() * vert;
            double ty = f.y() * fwd + r.y() * side + u.y() * vert;
            double tz = f.z() * fwd + r.z() * side + u.z() * vert;
            double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (len > 1e-9) {
                double k = thrust() / Math.max(1, len);
                ax = tx * k;
                ay = ty * k;
                az = tz * k;
            }
        } else if (!grounded) {
            // без клавиш — стабилизатор: гасит скорость относительно тел (они неподвижны — просто скорость)
            Vec3d a = Thrust.damp(state.velocity(), cfg.stabilizer, Units.TICK);
            ax = a.x();
            ay = a.y();
            az = a.z();
        }
        Integrator.DEFAULT.kick(field, state, Units.TICK, ax, ay, az);

        if (grounded) {
            // на поверхности — шаг (трение подошв) и прыжок; в самом вакууме трения нет
            look.decompose();
            Quat h = Quat.fromYawPitchRoll(look.yaw, 0, 0);
            Vec3d hf = h.forward(), hr = h.right();
            double wx = (hf.x() * fwd + hr.x() * side), wz = (hf.z() * fwd + hr.z() * side);
            double wl = Math.sqrt(wx * wx + wz * wz);
            if (wl > 1e-9) {
                wx = wx / wl * cfg.walkSpeed;
                wz = wz / wl * cfg.walkSpeed;
            }
            state.vx += (wx - state.vx) * cfg.groundGrip;
            state.vz += (wz - state.vz) * cfg.groundGrip;
            if (vert > 0 && !jumpHeld) state.vy = Math.max(state.vy, cfg.jumpSpeed);
        }
        jumpHeld = jump;

        Vec3d capped = Thrust.capSpeed(state.velocity(), maxSpeed());
        // отдача — поверх своей скорости (не в state: стабилизатор и предел скорости её не трогают)
        Vec3d r = recoil.velocity();
        recoil.step();
        recoilSent = new Vec3d(Units.toPerTick(r.x()), Units.toPerTick(r.y()), Units.toPerTick(r.z()));
        setVelocity(p, new Vec3(Units.toPerTick(capped.x()) + recoilSent.x(),
                Units.toPerTick(capped.y()) + recoilSent.y(), Units.toPerTick(capped.z()) + recoilSent.z()));
    }

    private void clearRecoil() {
        recoil.clear();
        recoilSent = Vec3d.ZERO;
        freeFlight = false;
    }

    /** Скорость на следующий тик (блоки/тик): игроку и себе — для сверки после шага. */
    private void setVelocity(LocalPlayer p, Vec3 v) {
        lastSet = v;
        lastPos = p.position();                                    // после смены рамки — уже новая позиция
        p.setDeltaMovement(lastSet);
        p.resetFallDistance();
    }

    // ---------------------------------------------------------------- тяготение грани

    /** Встать на грань f (рамка — по ней; не влезла — не встаём). */
    private boolean enterSurface(LocalPlayer p, int f) {
        if (!switchBox(p, f, true)) return false;
        grav.enter(f, look.orientation(), System.nanoTime());
        transitions++;
        transferred = false;
        standSurface = f;
        standLost = 0;
        airTicks = 0;
        voidTicks = 0;
        jumpDelay = 0;
        jumped = false;
        orientNow = true;
        return true;
    }

    /** Сойти с грани в свободный полёт (рамка вернётся обычной, как только для неё будет место). */
    private void leaveSurface() {
        grav.leave();
        reentry = REENTRY;
        orientNow = true;
    }

    /**
     * Тик на грани: как vanilla на земле, только «вниз» — к грани. Шаг и трение вдоль грани, тяжесть и прыжок вдоль
     * её нормали; внутренний угол и ребро — переход на соседнюю грань; Shift+пробел или двойной пробел — оттолкнуться.
     *
     * @param v скорость после шага (с ударами о блоки), блоки/тик
     */
    private void surfaceStep(Minecraft mc, LocalPlayer p, Vec3d v, int fwd, int side, boolean jump, boolean shift) {
        long now = System.nanoTime();
        int face = grav.face();
        Vec3d n = grav.normal();
        AABB bb = p.getBoundingBox();
        boolean grounded = touching(p, bb, face, CONTACT) && v.dot(n) <= 1e-3;
        boolean jumpPress = jump && !jumpHeld;
        jumpHeld = jump;
        // оттолкнуться: Shift+пробел или второе нажатие пробела в прыжке
        if (jumpPress && (shift || (!grounded && jumped && ticks - jumpTick <= SurfaceGravity.DOUBLE_TAP))) {
            Vec3d out = SurfaceGravity.pushOff(v, n, Units.toPerTick(cfg.pushOff));
            leaveSurface();
            setVelocity(p, toVec3(Thrust.capSpeed(out, Units.toPerTick(maxSpeed()))));
            return;
        }
        Vec3d wish = grav.wish(fwd, side);
        if (grounded) {
            airTicks = 0;
            jumped = false;
            // внутренний угол: шагнул в стену выше шага — встать на неё
            Vec3d n0 = n;
            int c = SurfaceGravity.innerCorner(face, wish, i -> touchingHigh(p, bb, i, n0));
            if (c >= 0 && switchBox(p, c, true)) {
                grav.transfer(c, look.orientation(), now);
                transitions++;
                transferred = true;
                face = c;
                n = grav.normal();
                wish = grav.wish(fwd, side);
            }
        } else {
            airTicks++;
            // сошёл с ребра (не прыжок) и ниже не ступенька — на боковую грань того же блока
            if (!jumped && !blockBelow(p, bb, n, STEP_DEPTH)) {
                Vec3d vt = v.sub(n.scale(v.dot(n)));
                AABB b0 = bb;
                int e = SurfaceGravity.outerEdge(face, vt, i -> touching(p, b0, i, LOST_GAP));
                if (e >= 0 && switchBox(p, e, true)) {
                    v = SurfaceGravity.carry(face, e, v);
                    grav.transfer(e, look.orientation(), now);
                    transitions++;
                    transferred = true;
                    face = e;
                    n = grav.normal();
                    wish = grav.wish(fwd, side);
                    grounded = true;
                    airTicks = 0;
                }
            }
        }
        // сошёл в пустоту (некуда перейти, земли ниже нет) — через GRACE тиков свободный полёт
        if (!grounded && !blockBelow(p, p.getBoundingBox(), n, VOID_DEPTH)) {
            if (++voidTicks > SurfaceGravity.GRACE) {
                leaveSurface();
                setVelocity(p, toVec3(v));
                return;
            }
        } else {
            voidTicks = 0;
        }
        if (boxApi && boxSurface != (SurfaceBox.turned(face) ? face : -1)) switchBox(p, face, true);
        standSurface = face;
        standLost = 0;

        boolean sprint = mc.screen == null && down(mc, mc.options.keySprint) && fwd > 0 && !shift;
        double speed = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
        Vec3d w = shift ? wish.scale(SNEAK) : wish;
        Vec3d next = SurfaceGravity.step(v, n, grounded, friction(p, n), w, speed, sprint);
        if (jumpDelay > 0) jumpDelay--;
        if (!jump) jumpDelay = 0;
        if (grounded && jump && !shift && jumpDelay == 0) {
            next = SurfaceGravity.jump(next, n, grav.forward(), sprint);
            jumpDelay = SurfaceGravity.JUMP_DELAY;
            jumped = true;
            jumpTick = ticks;
        }
        setVelocity(p, toVec3(Thrust.capSpeed(next, Units.toPerTick(maxSpeed()))));
    }

    /** Присед: шаг медленнее (как у vanilla). */
    private static final double SNEAK = 0.3;

    private static Vec3 toVec3(Vec3d v) { return new Vec3(v.x(), v.y(), v.z()); }

    /** Трение блока под ногами (за гранью, у середины ног). */
    private static double friction(LocalPlayer p, Vec3d n) {
        AABB b = p.getBoundingBox();
        Vec3 c = b.getCenter();
        double half = Math.abs(n.x()) * b.getXsize() / 2 + Math.abs(n.y()) * b.getYsize() / 2
                + Math.abs(n.z()) * b.getZsize() / 2;
        BlockPos at = BlockPos.containing(c.x - n.x() * (half + 0.5), c.y - n.y() * (half + 0.5),
                c.z - n.z() * (half + 0.5));
        float f = p.level().getBlockState(at).getBlock().getFriction();
        return f > 0 && f <= 1.2f ? f : 0.6;
    }

    /** Есть ли блок «под ногами» вдоль −n не дальше depth (проба — сечение рамки, чуть уже). */
    private static boolean blockBelow(LocalPlayer p, AABB b, Vec3d n, double depth) {
        int i = BodyFrame.normalIndex(n);
        return i >= 0 && touching(p, b, i, depth);
    }

    /** Касание грани i только на уровне головы: от WALL_FROM вдоль n от ног и выше (стена выше шага). */
    private static boolean touchingHigh(LocalPlayer p, AABB b, int i, Vec3d n) {
        int axis = BodyFrame.normalIndex(n) / 2;
        double[] lo = {b.minX, b.minY, b.minZ}, hi = {b.maxX, b.maxY, b.maxZ};
        double along = n.x() + n.y() + n.z();                      // ±1 по своей оси
        if (along > 0) lo[axis] = Math.min(hi[axis], lo[axis] + WALL_FROM);
        else hi[axis] = Math.max(lo[axis], hi[axis] - WALL_FROM);
        return touching(p, new AABB(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]), i, CONTACT * 2);
    }

    /** Ноги по движению вдоль грани (тело других игроков и своё): ванильная анимация шага считает только X/Z. */
    static void walkAlong(LivingEntity e, Vec3d n) {
        Vec3d d = new Vec3d(e.getX() - e.xo, e.getY() - e.yo, e.getZ() - e.zo);
        d = d.sub(n.scale(d.dot(n)));
        double len = d.length();
        float target = len > 2 ? 0 : (float) Math.min(len * 4, 1);       // скачок (смена рамки, телепорт) — не шаг
        WalkAnimationState w = e.walkAnimation;
        float s1 = w.speed(), s0 = w.speed(0f);
        // отменить шаг, который игра только что сделала (по X/Z), и сделать свой — тем же способом, что игра
        w.setSpeed(-s1);
        w.update(-s1, 0f, 1f);
        w.setSpeed(s0);
        w.update(target, 0.4f, 1f);
    }

    /**
     * Поверхность под ногами для позы тела в свободном полёте: касаемся грани «под ногами» — она; иначе свободно. Пропала — держим ещё {@link #STAND_HOLD} тиков (отскоки, зазор в доли блока не дёргают тело).
     */
    private void updateStand(Vec3d feet) {
        int idx = BodyFrame.normalIndex(feet);
        if (idx >= 0) {
            standSurface = idx;
            standLost = 0;
        } else if (standSurface >= 0 && ++standLost > STAND_HOLD) {
            standSurface = -1;
            standLost = 0;
        }
    }

    /** Тяга ранца: своя настройка, но не больше серверной (если сервер прислал). */
    private double thrust() {
        double t = serverThrust;
        return t >= 0 ? Math.min(cfg.thrust, t) : cfg.thrust;
    }

    /** Предел скорости: свой, но не больше серверного. */
    private double maxSpeed() {
        double m = serverMaxSpeed;
        return m > 0 ? Math.min(cfg.maxSpeed, m) : cfg.maxSpeed;
    }

    /**
     * Блок вплотную к рамке игрока с одной из шести сторон (зазор меньше {@link #CONTACT}; после удара — вдвое больше).
     * Из касаний берём то, чья нормаль ближе всего к верху камеры, — «на нём и стоим». null — ничего рядом.
     */
    private static Vec3d surface(LocalPlayer p, Vec3d up) {
        AABB b = p.getBoundingBox();
        double gap = p.horizontalCollision || p.verticalCollision ? CONTACT * 2 : CONTACT;
        Vec3d best = null;
        double bestDot = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < BodyFrame.NORMALS.length; i++) {
            if (!touching(p, b, i, gap)) continue;
            Vec3d n = BodyFrame.NORMALS[i];
            double dot = n.dot(up);
            if (dot > bestDot) {
                bestDot = dot;
                best = n;
            }
        }
        return best;
    }

    /**
     * Блок вплотную к рамке b со стороны −NORMALS[i] (на грани i можно стоять): тонкий слой толщиной gap за этой
     * стороной рамки, чуть уже её (не цеплять соседние грани — пол у стены).
     */
    private static boolean touching(LocalPlayer p, AABB b, int i, double gap) {
        int axis = i / 2;
        int sign = (i % 2 == 0) ? -1 : 1;                          // блок — со стороны, противоположной нормали
        double e = 0.01;
        double x0 = b.minX + e, x1 = b.maxX - e, y0 = b.minY + e, y1 = b.maxY - e, z0 = b.minZ + e, z1 = b.maxZ - e;
        switch (axis) {
            case 0 -> { x0 = sign < 0 ? b.minX - gap : b.maxX; x1 = sign < 0 ? b.minX : b.maxX + gap; }
            case 1 -> { y0 = sign < 0 ? b.minY - gap : b.maxY; y1 = sign < 0 ? b.minY : b.maxY + gap; }
            default -> { z0 = sign < 0 ? b.minZ - gap : b.maxZ; z1 = sign < 0 ? b.minZ : b.maxZ + gap; }
        }
        return !p.level().noCollision(p, new AABB(x0, y0, z0, x1, y1, z1));
    }

    // ---------------------------------------------------------------- рамка (мод 0.54+)

    /**
     * Сменить рамку своего игрока на рамку грани target (стена/потолок — повёрнутая, пол или −1 — обычная) со
     * сдвигом позиции ({@link SurfaceBox#candidates}): первое положение, где новая рамка не в блоках (и, если
     * contact, касается грани target). true — рамка такая (уже была или поставлена); false — места нет, рамка прежняя.
     * Ядро без крючка рамки — всегда true (тяготение грани работает с ровной рамкой).
     */
    private boolean switchBox(LocalPlayer p, int target, boolean contact) {
        int next = SurfaceBox.turned(target) ? target : -1;
        if (!boxApi || next == boxSurface) return true;
        EntityDimensions d = p.getDimensions(Pose.STANDING);
        double h = d.height(), w = d.width();
        AABB b = p.getBoundingBox();
        Vec3 pos = p.position();
        long now = System.nanoTime();
        Vec3d shift = eyeGlide == null ? Vec3d.ZERO : eyeGlide.offset(now);
        Vec3 eye0 = p.getEyePosition().add(shift.x(), shift.y(), shift.z());   // где глаза показаны сейчас
        List<Vec3d> cands = SurfaceBox.candidates(new double[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ},
                new Vec3d(pos.x, pos.y, pos.z), new Vec3d(eye0.x, eye0.y, eye0.z), target, contact, h, w,
                d.eyeHeight());
        Vec3d n = BodyFrame.normal(target);
        boolean snap = contact && n != null;
        for (Vec3d c0 : cands) {
            // contact: сторона рамки к −n — на плоскости прежней рамки; но у ребра (сошёл с грани, LOST_GAP) прежняя
            // рамка могла не доставать до новой грани — тогда придвинуть к ней (шагами, до LOST_GAP), пока не коснётся
            Vec3d c = null;
            for (double gap = 0; gap <= LOST_GAP + 1e-9; gap += SNAP_STEP) {
                Vec3d ci = snap ? c0.sub(n.scale(gap)) : c0;
                if (ci.distance(new Vec3d(pos.x, pos.y, pos.z)) > SurfaceBox.MAX_SHIFT + 1e-9) break;
                double[] nb = SurfaceBox.box(next, ci.x(), ci.y(), ci.z(), h, w);
                AABB box = new AABB(nb[0], nb[1], nb[2], nb[3], nb[4], nb[5]);
                if (!p.level().noCollision(p, box.deflate(1e-6))) break;          // дальше — в блоке
                if (!snap || touching(p, box, target, CONTACT * 2)) {
                    c = ci;
                    break;
                }
            }
            if (c == null) continue;
            double dx = c.x() - pos.x, dy = c.y() - pos.y, dz = c.z() - pos.z;
            boxSurface = next;
            p.setPos(c.x(), c.y(), c.z());                         // рамку строит крючок ядра — уже новую
            p.xo += dx;                                            // без размазанного «пролёта» между кадрами
            p.yo += dy;
            p.zo += dz;
            p.xOld += dx;
            p.yOld += dy;
            p.zOld += dz;
            Vec3 eye1 = p.getEyePosition();
            // камера доезжает от прежних глаз к новым за surface_move_time; при смене грани — по дуге вокруг ребра
            // (тем же поворотом, что и камера: n → n')
            Vec3d nFrom = grav.active() ? grav.normal() : null;
            Quat turn = nFrom != null && n != null ? SurfaceGravity.between(nFrom, n) : Quat.IDENTITY;
            eyeGlide = Glide.of(new Vec3d(eye0.x, eye0.y, eye0.z), new Vec3d(eye1.x, eye1.y, eye1.z), turn, now,
                    cfg.surfaceMoveTime);
            eyeCorr = eyeGlide.offset(now);
            transitions++;
            if (next >= 0) {
                standSurface = next;
                standLost = 0;
            }
            orientNow = true;
            return true;
        }
        return false;
    }

    /** Крючок ядра onEntityBox: повёрнутая рамка своего игрока и чужих (по флагам op 3) или null — как у игры. */
    private AABB entityBox(Entity e, Vec3 pos) {
        int s = boxSurfaceOf(e);
        if (s < 0) return null;
        EntityDimensions d = e.getDimensions(Pose.STANDING);
        double[] b = SurfaceBox.box(s, pos.x, pos.y, pos.z, d.height(), d.width());
        return new AABB(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    /** Крючок ядра onEntityEye: глаза у головы вдоль нормали грани или null — как у игры. */
    private Vec3 entityEye(Entity e) {
        int s = boxSurfaceOf(e);
        if (s < 0) return null;
        EntityDimensions d = e.getDimensions(Pose.STANDING);
        Vec3d o = SurfaceBox.eye(s, d.height(), d.width(), d.eyeHeight());
        return new Vec3(o.x(), o.y(), o.z());
    }

    private int boxSurfaceOf(Entity e) {
        if (!boxHooksOn || !(e instanceof Player)) return -1;
        if (e == bound) return active ? boxSurface : -1;
        if (e == Minecraft.getInstance().player || bodies == null) return -1;
        return bodies.boxSurface(e.getId());
    }

    /** Пересобрать рамку чужих игроков (сменились флаги; выгрузка модуля): setPos строит её заново. */
    private static void refreshRemoteBoxes(List<Integer> ids) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        for (int id : ids) {
            Entity e = level.getEntity(id);
            if (e != null) e.setPos(e.getX(), e.getY(), e.getZ());
        }
    }

    // ---------------------------------------------------------------- поворот тела — серверу

    /**
     * Свой поворот серверу (C2S op 3): кватернион ТЕЛА без «лёжа» ({@link BodyFrame#base}) и флаги (лёжа, грань под
     * ногами) — зрители по ним строят ту же позу. Не чаще ~10 раз в секунду и только при изменении больше чем на
     * {@link #ORIENT_MIN_DEG}° или флагов; без изменений — изредка, чтобы сервер не забыл. stop — «больше не действует».
     */
    private void sendOrientation(boolean stop) {
        if (stop) {
            if (sentFlags > 0 && (sentFlags & BodyPoses.FLAG_ACTIVE) != 0) {
                send(orientation(sentBody != null ? sentBody : look.orientation(), 0));
            }
            sentFlags = -1;
            sentBody = null;
            return;
        }
        boolean now = orientNow;
        orientNow = false;
        if (!now && ticks % ORIENT_EVERY != 0) return;
        Quat q = BodyFrame.base(look.orientation(), standSurface);
        int flags = BodyFrame.flags(true, prone, standSurface, boxSurface >= 0)
                | (grav.active() ? BodyFrame.FLAG_GRAVITY : 0);
        boolean changed = flags != sentFlags || sentBody == null
                || Math.toDegrees(FreeLook.angle(q, sentBody)) > ORIENT_MIN_DEG;
        if (!changed && ticks - sentAt < ORIENT_KEEPALIVE) return;
        send(orientation(q, flags));
        sentBody = q;
        sentFlags = flags;
        sentAt = ticks;
    }

    static byte[] orientation(Quat q, int flags) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(18);
        try (DataOutputStream out = new DataOutputStream(b)) {
            out.writeByte(BodyPoses.OP_ORIENT);
            out.writeFloat((float) q.w());
            out.writeFloat((float) q.x());
            out.writeFloat((float) q.y());
            out.writeFloat((float) q.z());
            out.writeByte(flags);
        } catch (IOException e) {
            throw new IllegalStateException(e);                    // ByteArrayOutputStream не бросает
        }
        return b.toByteArray();
    }

    /** Сказать серверу, что умеем рисовать повёрнутые тела (тогда он не шлёт нам запасную позу). */
    private void sendCaps() {
        if (bodies != null && bodies.hooked()) send(new byte[] {BodyPoses.OP_CAPS, BodyPoses.CAP_AVATAR_ROTATION});
    }

    private void send(byte[] data) {
        try {
            ctx.send(data);
        } catch (RuntimeException e) {
            ctx.logger().debug("spaceflight: не отправить серверу: {}", e.toString());
        }
    }

    private static boolean down(Minecraft mc, KeyMapping k) {
        return physical(mc, KeyBindingHelper.getBoundKeyOf(k));
    }

    private static boolean physical(Minecraft mc, InputConstants.Key key) {
        if (key == null || key.equals(InputConstants.UNKNOWN)) return false;
        int v = key.getValue();
        if (v < 0) return false;
        return switch (key.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(mc.getWindow(), v);
            case MOUSE -> GLFW.glfwGetMouseButton(mc.getWindow().handle(), v) == GLFW.GLFW_PRESS;
            default -> false;
        };
    }

    // ---------------------------------------------------------------- камера

    private boolean onMouse(double yawDeg, double pitchDeg) {
        if (!active || !lookOwned) return false;                   // вне вакуума — обычная мышь
        if (!Double.isFinite(yawDeg) || !Double.isFinite(pitchDeg)) return true;
        if (grav.active()) {                                       // на грани — как на земле, в системе грани
            grav.turn(yawDeg, pitchDeg);
            look.set(grav.shown(System.nanoTime()));
        } else {
            look.turn(yawDeg, pitchDeg);
        }
        look.decompose();
        syncPlayer(bound);
        return true;
    }

    private void onCamera(CameraState cam, float partialTick) {
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : FreeLook.clamp((now - lastFrame) / 1e9, 0, 0.1);
        lastFrame = now;
        if (!lookOwned) return;
        Minecraft mc = Minecraft.getInstance();
        if (active) {
            if (grav.active()) {
                // на грани — камера ровная в её системе (верх = нормаль грани), после входа — плавно
                look.set(grav.shown(now));
            } else if (mc.screen == null && cfg.rollSpeed > 0) {
                int roll = (physical(mc, rollRightKey) ? 1 : 0) - (physical(mc, rollLeftKey) ? 1 : 0);
                look.roll(roll * cfg.rollSpeed * dt);
            }
            proneAmount += (float) (((prone ? 1 : 0) - proneAmount) * (1 - Math.exp(-8 * dt)));
            eyeAt(cam, partialTick, dt);
            // ядро без onEntityPose — лёжа показываем ванильной анимацией плавания (видно в F5); с крючком позу
            // целиком задаёт BodyPoses (иначе плавание повернуло бы тело второй раз)
            if (!bodies.hooked() && (prone || proneAmount > 0.01f)) SwimLook.apply(mc.player, proneAmount);
            look.decompose();
            syncPlayer(bound);
        } else {
            LocalPlayer p = mc.player;
            if (p == null) {
                lookOwned = false;
                return;
            }
            // плавно «в горизонт» к обычному взгляду игрока, потом камера снова vanilla
            Quat target = Quat.fromYawPitchRoll(p.getYRot(), p.getXRot(), 0);
            double left = look.approach(target, 1 - Math.exp(-cfg.uprightRate * dt));
            if (left < Math.toRadians(0.3)) {
                lookOwned = false;
                return;
            }
            look.decompose();
        }
        // Камера игры строит поворот как Ry(π−yaw)·Rx(−pitch)·Rz(roll) и смотрит вдоль −Z, а FreeLook —
        // Ry(−yaw)·Rx(pitch)·Rz(roll) со взглядом вдоль +Z: Qcam = Qlook·Ry(π) (крен меняет знак). Полный поворот —
        // кватернионом (CameraState.rotation): без особых точек у «прямо вверх/вниз»; углы — тоже (звук и прочее).
        // F5 «лицом к себе» (зеркальный вид): игра берёт yaw+180, −pitch — это Qcam·Ry(π) = Qlook.
        boolean mirrored = cam.detached && mc.options.getCameraType().isMirrored();
        Quat q = mirrored ? look.orientation() : BodyFrame.camera(look.orientation());
        cam.rotation = new Quaternionf((float) q.x(), (float) q.y(), (float) q.z(), (float) q.w());
        cam.yaw = mirrored ? look.yaw + 180 : look.yaw;
        cam.pitch = mirrored ? -look.pitch : look.pitch;
        cam.roll = mirrored ? look.roll : -look.roll;
        if (active && cam.detached && boxApi && (boxSurface >= 0 || eyeCorr != Vec3d.ZERO)) orbit(cam, partialTick, mirrored);
    }

    /**
     * Вид от третьего лица (F5) с повёрнутой рамкой: игра ставит камеру позади ВАНИЛЬНЫХ глаз (позиция + рост по Y)
     * и проверяет блоки оттуда же — на стене это внутри стены или под полом. Ставим сами: от глаз рамки назад по
     * взгляду (вид спереди — вперёд) на 4 блока, ближе, если мешают блоки.
     */
    private void orbit(CameraState cam, float pt, boolean front) {
        LocalPlayer p = bound;
        Minecraft mc = Minecraft.getInstance();
        if (p == null || mc.level == null) return;
        Vec3 eye = p.getEyePosition(pt).add(eyeCorr.x(), eyeCorr.y(), eyeCorr.z());
        Vec3d f = look.orientation().forward();
        Vec3 dir = front ? new Vec3(f.x(), f.y(), f.z()) : new Vec3(-f.x(), -f.y(), -f.z());
        double dist = 4.0 * p.getScale();
        Vec3 to = eye.add(dir.scale(dist));
        var hit = mc.level.clip(new ClipContext(eye, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p));
        if (hit.getType() != HitResult.Type.MISS) dist = Math.max(0, hit.getLocation().distanceTo(eye) - 0.2);
        cam.x = eye.x + dir.x * dist;
        cam.y = eye.y + dir.y * dist;
        cam.z = eye.z + dir.z * dist;
    }

    /**
     * Камера — у глаз повёрнутой рамки (на стене — у головы, в 1.6 блока от стены), после смены рамки — плавно
     * от прежних глаз. Игра ставит камеру по позиции и росту (Camera.setup глаза сущности не спрашивает) — сдвигаем
     * на разницу, в виде от третьего лица тоже.
     */
    private void eyeAt(CameraState cam, float pt, double dt) {
        LocalPlayer p = bound;
        if (!boxApi || p == null) return;
        if (eyeGlide != null && eyeGlide.done(System.nanoTime())) eyeGlide = null;
        eyeCorr = eyeGlide == null ? Vec3d.ZERO : eyeGlide.offset(System.nanoTime());
        int s = boxSurface;
        if (s < 0 && eyeCorr == Vec3d.ZERO) return;
        EntityDimensions d = p.getDimensions(Pose.STANDING);
        Vec3d off = SurfaceBox.eye(s, d.height(), d.width(), d.eyeHeight());
        // игра: позиция кадра + рост глаз по вертикали; нам: позиция кадра + глаза рамки + поправка
        cam.x += off.x() + eyeCorr.x();
        cam.y += off.y() + eyeCorr.y() - p.getEyeHeight();
        cam.z += off.z() + eyeCorr.z();
    }

    /** Поворот игрока — за взглядом (прицел, ломание блоков, то, что видят другие). pitch уже в [−90; 90]. */
    private void syncPlayer(LocalPlayer p) {
        if (p == null) return;
        float oldYaw = p.getYRot();
        float yaw = oldYaw + Mth.wrapDegrees(look.yaw - oldYaw);  // без скачков на 360° — vanilla копит угол
        float dYaw = yaw - oldYaw;
        float dPitch = look.pitch - p.getXRot();
        p.setYRot(yaw);
        p.setXRot(look.pitch);
        p.yRotO += dYaw;
        p.xRotO += dPitch;
        p.setYHeadRot(yaw);
        p.yHeadRotO += dYaw;
    }
}
