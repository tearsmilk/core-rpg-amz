package ink.hoka.odyssey.client.spaceflight;

import com.mojang.blaze3d.platform.InputConstants;
import ink.hoka.odyssey.client.api.CameraState;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import ink.hoka.odyssey.spacephys.Body;
import ink.hoka.odyssey.spacephys.Field;
import ink.hoka.odyssey.spacephys.Integrator;
import ink.hoka.odyssey.spacephys.Quat;
import ink.hoka.odyssey.spacephys.State;
import ink.hoka.odyssey.spacephys.Units;
import ink.hoka.odyssey.spacephys.Vec3d;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Живая невесомость в вакууме (мод 0.17+). Включает и выключает её сервер (модуль gravity), он же присылает
 * список тяготеющих тел. Пока включено:
 * <ul>
 *   <li>инерция: ни сопротивления, ни «ванильной» гравитации 0,08 — скорость меняют только тяготение тел (1/r²,
 *       общая библиотека spacephys), тяга ранца и удары о блоки;</li>
 *   <li>тяга: W/S, A/D, пробел/shift — вдоль осей взгляда (вперёд, вбок, вверх головы), без «горизонта»;
 *       стоя на блоке — шаг и прыжок с отталкиванием от поверхности;</li>
 *   <li>свободная камера: ориентация — кватернион, мышь вращает в своих осях, крен — клавиши (Z/C по умолчанию),
 *       петля через верх проходит без упора в ±90°. Поворот игрока (прицел, ломание блоков) следует за взглядом;</li>
 *   <li>«магнитные ботинки»: Shift у любого блока (пол, стена, потолок) прижимает к нему — по нему можно ходить,
 *       пробел отталкивает, камера плавно выравнивается так, что поверхность становится «полом»;</li>
 *   <li>тело: свой поворот (и «лёжа» — бег переключает) уходит серверу, тот раздаёт его другим игрокам
 *       ({@link BodyPoses}; рисовать повёрнутые тела мод начнёт с крючком onAvatarRotations).</li>
 * </ul>
 * Вне вакуума камера плавно возвращается «в горизонт» и дальше не трогается; всё восстанавливается при выключении.
 *
 * <p>Протокол (big-endian). Сервер → клиент: op 1 — тела: u8 op, i32 count, на тело f64 x, y, z, mu, radius,
 * softening; op 2 — физика: u8 op, u8 on; op 3 — поворот тела другого игрока: u8 op, i32 entityId, f32 w, x, y, z,
 * u8 flags (бит 0 — лёжа, бит 1 — действует); op 4 — поворот больше не действует: u8 op, i32 entityId.
 * Клиент → сервер: op 3 — свой поворот: u8 op, f32 w, x, y, z (кватернион взгляда FreeLook), u8 flags;
 * op 5 — возможности: u8 op, u8 caps (бит 0 — умеем рисовать повёрнутые тела).
 */
public final class SpaceflightModule implements ClientModule {
    static final int OP_BODIES = 1;
    static final int OP_PHYSICS = 2;
    static final int MAX_BODIES = 4096;
    private static final int BODY_BYTES = 6 * 8;
    /** Касание блока: зазор между рамкой игрока и блоком меньше этого, блоки. */
    private static final double CONTACT = 0.05;
    /** После прыжка от поверхности — столько тиков без прижима (иначе ботинки сразу вернут назад). */
    private static final int GRIP_COOLDOWN = 6;
    /** Свой поворот серверу — не чаще чем раз в столько тиков (~10 раз в секунду)… */
    private static final int ORIENT_EVERY = 2;
    /** …и только если повернулся больше чем на столько градусов; без изменений — раз в столько тиков. */
    private static final double ORIENT_MIN_DEG = 1.0;
    private static final int ORIENT_KEEPALIVE = 40;

    private ClientContext ctx;
    private FlightConfig cfg;
    private InputConstants.Key rollLeftKey;
    private InputConstants.Key rollRightKey;

    private volatile Field field = Field.EMPTY;
    private volatile boolean serverOn;

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

    /** Нормаль поверхности, к которой сейчас прижимают ботинки (null — не прижимают). */
    private Vec3d gripNormal;
    private int gripCooldown;
    /** Лёжа (тело вдоль взгляда) — переключается бегом в невесомости. */
    private boolean prone;
    private boolean sprintHeld;
    /** Плавная доля «лёжа» для своего тела в виде от третьего лица. */
    private float proneAmount;

    private BodyPoses bodies;
    private long ticks;
    private Quat sentLook;
    private int sentFlags = -1;
    private long sentAt;

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        cfg = FlightConfig.load(ctx.dataDir(), ctx.logger());
        rollLeftKey = key(cfg.rollLeft, "key.keyboard.z");
        rollRightKey = key(cfg.rollRight, "key.keyboard.c");
        bodies = new BodyPoses(ctx, new BodyPoses.Self() {
            @Override public Quat look() { return active ? look.orientation() : null; }

            @Override public float prone() { return proneAmount; }
        });
        ctx.onMessage(this::onMessage);
        ctx.onTick(this::tick);
        ctx.onMouse(this::onMouse);
        ctx.onCamera(this::onCamera);
        bodies.install();
        ctx.logger().info("spaceflight: загружен (тяга {} б/с², крен {}/{})", cfg.thrust, cfg.rollLeft, cfg.rollRight);
    }

    @Override
    public void disable() {
        if (active) deactivate();
        if (bodies != null) bodies.disable();
        lookOwned = false;
        serverOn = false;
        field = Field.EMPTY;
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
                    serverOn = in.readUnsignedByte() != 0;
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
        }
        if (ticks % 20 == 0) bodies.prune();
        if (active && p != bound) deactivate();                    // смена мира / возрождение — новый объект игрока
        boolean want = serverOn && p != null && !p.isSpectator() && !p.isPassenger()
                && !p.getAbilities().flying && p.isAlive();
        if (want && !active) activate(p);
        else if (!want && active) deactivate();
        if (active) {
            physics(mc, p);
            sendOrientation(false);
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
        jumpHeld = true;                                           // не прыгать от пробела, зажатого до входа
        sprintHeld = true;                                         // и не ложиться от бега, зажатого до входа
        prone = false;
        proneAmount = 0;
        gripNormal = null;
        gripCooldown = 0;
    }

    private void deactivate() {
        LocalPlayer p = bound;
        sendOrientation(true);                                     // «поворот больше не действует»
        active = false;
        gripNormal = null;
        prone = false;
        proneAmount = 0;
        bound = null;
        if (p != null) {
            p.setNoGravity(savedNoGravity);
            p.setDiscardFriction(savedDiscardFriction);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null && mc.getWindow() != null) KeyMapping.setAll();   // вернуть зажатые клавиши ходьбе
    }

    private void physics(Minecraft mc, LocalPlayer p) {
        Options o = mc.options;
        boolean input = mc.screen == null;
        int fwd = 0, side = 0, vert = 0;
        boolean jump = false, shift = false, sprint = false;
        if (input) {
            fwd = (down(mc, o.keyUp) ? 1 : 0) - (down(mc, o.keyDown) ? 1 : 0);
            side = (down(mc, o.keyRight) ? 1 : 0) - (down(mc, o.keyLeft) ? 1 : 0);
            jump = down(mc, o.keyJump);
            shift = down(mc, o.keyShift);
            sprint = down(mc, o.keySprint);
            vert = (jump ? 1 : 0) - (shift ? 1 : 0);
            // ходьбу, прыжок, присед и бег vanilla не видит — движением управляет модуль
            for (KeyMapping k : new KeyMapping[] {o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift, o.keySprint}) {
                k.setDown(false);
            }
        }
        if (sprint && !sprintHeld) {                               // бег — лечь / встать
            prone = !prone;
            p.displayClientMessage(Component.literal(prone ? "Лёжа: тело вдоль взгляда (бег — встать)"
                    : "Стоя (бег — лечь)"), true);
        }
        sprintHeld = sprint;

        // база — скорость после vanilla move: удары о блоки (обнулённые оси) и толчки сервера уже в ней
        Vec3 d = p.getDeltaMovement();
        state.set(p.getX(), p.getY() + p.getBbHeight() * 0.5, p.getZ(),
                Units.fromPerTick(d.x), Units.fromPerTick(d.y), Units.fromPerTick(d.z));

        Quat q = look.orientation();
        // магнитные ботинки: Shift у блока — прижаться к поверхности, «под ногами» у которой верх камеры
        if (gripCooldown > 0) gripCooldown--;
        gripNormal = shift && gripCooldown == 0 ? surface(p, q.up()) : null;
        boolean gripping = gripNormal != null;
        double ax = 0, ay = 0, az = 0;
        boolean grounded = p.onGround();
        if (!gripping && !grounded && (fwd != 0 || side != 0 || vert != 0)) {
            Vec3d f = q.forward(), r = q.right(), u = q.up();
            double tx = f.x() * fwd + r.x() * side + u.x() * vert;
            double ty = f.y() * fwd + r.y() * side + u.y() * vert;
            double tz = f.z() * fwd + r.z() * side + u.z() * vert;
            double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (len > 1e-9) {
                double k = cfg.thrust / Math.max(1, len);
                ax = tx * k;
                ay = ty * k;
                az = tz * k;
            }
        }
        Integrator.DEFAULT.kick(field, state, Units.TICK, ax, ay, az);

        if (gripping) {
            grip(q, gripNormal, fwd, side, jump && !jumpHeld);
        } else if (grounded) {
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

        double sp = state.speed();
        if (sp > cfg.maxSpeed) {
            double k = cfg.maxSpeed / sp;
            state.vx *= k;
            state.vy *= k;
            state.vz *= k;
        }
        p.setDeltaMovement(Units.toPerTick(state.vx), Units.toPerTick(state.vy), Units.toPerTick(state.vz));
        p.resetFallDistance();
    }

    /**
     * Шаг в магнитных ботинках по поверхности с нормалью n (единичная, от блока к игроку): не отлетать (если
     * отлетаешь не слишком быстро), прижаться, идти по WASD вдоль поверхности, пробел — оттолкнуться по нормали.
     */
    private void grip(Quat q, Vec3d n, int fwd, int side, boolean jumpNow) {
        Vec3d v = new Vec3d(state.vx, state.vy, state.vz);
        double vn = v.dot(n);
        if (vn > 0 && vn <= cfg.gripBreakSpeed) v = v.sub(n.scale(vn));      // вакуум уносит — держимся
        v = v.sub(n.scale(cfg.gripPress * Units.TICK));                       // прижим (блок не пустит дальше)
        // направление шага: взгляд вдоль поверхности; смотришь прямо в пол/потолок — «вперёд» по верху камеры
        Vec3d f = q.forward(), u = q.up(), r = q.right();
        Vec3d fd = tangent(f, n).sub(tangent(u, n).scale(f.dot(n)));
        Vec3d rd = tangent(r, n);
        Vec3d wish = fd.normalize().scale(fwd).add(rd.normalize().scale(side));
        double wl = wish.length();
        wish = wl > 1e-9 ? wish.scale(cfg.walkSpeed / wl) : Vec3d.ZERO;
        double vnNow = v.dot(n);
        Vec3d vt = v.sub(n.scale(vnNow));
        vt = vt.add(wish.sub(vt).scale(cfg.groundGrip));
        if (jumpNow) {
            v = vt.add(n.scale(Math.max(0, vnNow) + cfg.jumpSpeed));
            gripCooldown = GRIP_COOLDOWN;
        } else {
            v = vt.add(n.scale(vnNow));
        }
        state.vx = v.x();
        state.vy = v.y();
        state.vz = v.z();
    }

    /** Составляющая вектора вдоль плоскости с нормалью n. */
    private static Vec3d tangent(Vec3d a, Vec3d n) {
        return a.sub(n.scale(a.dot(n)));
    }

    /**
     * Блок вплотную к рамке игрока с одной из шести сторон (зазор меньше {@link #CONTACT}; после удара — вдвое больше).
     * Из касаний берём то, чья нормаль ближе всего к верху камеры, — «на нём и стоим». null — ничего рядом.
     */
    private static Vec3d surface(LocalPlayer p, Vec3d up) {
        AABB b = p.getBoundingBox();
        double gap = p.horizontalCollision || p.verticalCollision ? CONTACT * 2 : CONTACT;
        double e = 0.01;                                           // не цеплять соседние грани (пол у стены)
        Vec3d best = null;
        double bestDot = Double.NEGATIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                double x0 = b.minX + e, x1 = b.maxX - e, y0 = b.minY + e, y1 = b.maxY - e, z0 = b.minZ + e, z1 = b.maxZ - e;
                switch (axis) {
                    case 0 -> { x0 = sign < 0 ? b.minX - gap : b.maxX; x1 = sign < 0 ? b.minX : b.maxX + gap; }
                    case 1 -> { y0 = sign < 0 ? b.minY - gap : b.maxY; y1 = sign < 0 ? b.minY : b.maxY + gap; }
                    default -> { z0 = sign < 0 ? b.minZ - gap : b.maxZ; z1 = sign < 0 ? b.minZ : b.maxZ + gap; }
                }
                if (p.level().noCollision(p, new AABB(x0, y0, z0, x1, y1, z1))) continue;
                // блок со стороны sign по оси — нормаль от него к игроку смотрит в −sign
                Vec3d n = new Vec3d(axis == 0 ? -sign : 0, axis == 1 ? -sign : 0, axis == 2 ? -sign : 0);
                double dot = n.dot(up);
                if (dot > bestDot) {
                    bestDot = dot;
                    best = n;
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- поворот тела — серверу

    /**
     * Свой поворот серверу (C2S op 3): не чаще ~10 раз в секунду и только при изменении больше чем на
     * {@link #ORIENT_MIN_DEG}° или флагов; без изменений — изредка, чтобы сервер не забыл. stop — «больше не действует».
     */
    private void sendOrientation(boolean stop) {
        if (stop) {
            if (sentFlags > 0 && (sentFlags & BodyPoses.FLAG_ACTIVE) != 0) {
                send(orientation(look.orientation(), 0));
            }
            sentFlags = -1;
            sentLook = null;
            return;
        }
        if (ticks % ORIENT_EVERY != 0) return;
        Quat q = look.orientation();
        int flags = BodyPoses.FLAG_ACTIVE | (prone ? BodyPoses.FLAG_PRONE : 0);
        boolean changed = flags != sentFlags || sentLook == null
                || Math.toDegrees(FreeLook.angle(q, sentLook)) > ORIENT_MIN_DEG;
        if (!changed && ticks - sentAt < ORIENT_KEEPALIVE) return;
        send(orientation(q, flags));
        sentLook = q;
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
        look.turn(yawDeg, pitchDeg);
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
            if (mc.screen == null && cfg.rollSpeed > 0) {
                int roll = (physical(mc, rollRightKey) ? 1 : 0) - (physical(mc, rollLeftKey) ? 1 : 0);
                look.roll(roll * cfg.rollSpeed * dt);
            }
            // в ботинках — крен плавно по поверхности: стена или потолок становятся «полом»
            Vec3d n = gripNormal;
            if (n != null && cfg.surfaceAlignRate > 0) look.alignRoll(n, 1 - Math.exp(-cfg.surfaceAlignRate * dt));
            proneAmount += (float) (((prone ? 1 : 0) - proneAmount) * (1 - Math.exp(-8 * dt)));
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
        cam.yaw = look.yaw;
        cam.pitch = look.pitch;
        // Камера игры строит поворот как Ry(π−yaw)·Rx(−pitch)·Rz(roll) и смотрит вдоль −Z, а FreeLook —
        // Ry(−yaw)·Rx(pitch)·Rz(roll) со взглядом вдоль +Z. Это одно и то же, только с поворотом на 180° вокруг Y,
        // который меняет знак крена. Без минуса картинка крутилась в обратную сторону, и мышь «вниз» при крене
        // уводила взгляд вбок.
        cam.roll = -look.roll;
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
