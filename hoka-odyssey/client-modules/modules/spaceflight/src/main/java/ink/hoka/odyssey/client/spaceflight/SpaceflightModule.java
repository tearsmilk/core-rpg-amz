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
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
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
 *       петля через верх проходит без упора в ±90°. Поворот игрока (прицел, ломание блоков) следует за взглядом.</li>
 * </ul>
 * Вне вакуума камера плавно возвращается «в горизонт» и дальше не трогается; всё восстанавливается при выключении.
 *
 * <p>Протокол (сервер → клиент, big-endian): op 1 — тела: u8 op, i32 count, на тело f64 x, y, z, mu, radius,
 * softening; op 2 — физика: u8 op, u8 on.
 */
public final class SpaceflightModule implements ClientModule {
    static final int OP_BODIES = 1;
    static final int OP_PHYSICS = 2;
    static final int MAX_BODIES = 4096;
    private static final int BODY_BYTES = 6 * 8;

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

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        cfg = FlightConfig.load(ctx.dataDir(), ctx.logger());
        rollLeftKey = key(cfg.rollLeft, "key.keyboard.z");
        rollRightKey = key(cfg.rollRight, "key.keyboard.c");
        ctx.onMessage(this::onMessage);
        ctx.onTick(this::tick);
        ctx.onMouse(this::onMouse);
        ctx.onCamera(this::onCamera);
        ctx.logger().info("spaceflight: загружен (тяга {} б/с², крен {}/{})", cfg.thrust, cfg.rollLeft, cfg.rollRight);
    }

    @Override
    public void disable() {
        if (active) deactivate();
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
                case OP_BODIES -> field = readBodies(in, data.length - 1);
                case OP_PHYSICS -> {
                    if (data.length < 2) return;
                    serverOn = in.readUnsignedByte() != 0;
                }
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
        if (mc.level == null) {                                    // вышли с сервера
            serverOn = false;
            field = Field.EMPTY;
        }
        if (active && p != bound) deactivate();                    // смена мира / возрождение — новый объект игрока
        boolean want = serverOn && p != null && !p.isSpectator() && !p.isPassenger()
                && !p.getAbilities().flying && p.isAlive();
        if (want && !active) activate(p);
        else if (!want && active) deactivate();
        if (active) physics(mc, p);
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
    }

    private void deactivate() {
        LocalPlayer p = bound;
        active = false;
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
        if (input) {
            fwd = (down(mc, o.keyUp) ? 1 : 0) - (down(mc, o.keyDown) ? 1 : 0);
            side = (down(mc, o.keyRight) ? 1 : 0) - (down(mc, o.keyLeft) ? 1 : 0);
            vert = (down(mc, o.keyJump) ? 1 : 0) - (down(mc, o.keyShift) ? 1 : 0);
            // ходьбу, прыжок, присед и бег vanilla не видит — движением управляет модуль
            for (KeyMapping k : new KeyMapping[] {o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift, o.keySprint}) {
                k.setDown(false);
            }
        }

        // база — скорость после vanilla move: удары о блоки (обнулённые оси) и толчки сервера уже в ней
        Vec3 d = p.getDeltaMovement();
        state.set(p.getX(), p.getY() + p.getBbHeight() * 0.5, p.getZ(),
                Units.fromPerTick(d.x), Units.fromPerTick(d.y), Units.fromPerTick(d.z));

        Quat q = look.orientation();
        double ax = 0, ay = 0, az = 0;
        boolean grounded = p.onGround();
        if (!grounded && (fwd != 0 || side != 0 || vert != 0)) {
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
        jumpHeld = vert > 0;

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
