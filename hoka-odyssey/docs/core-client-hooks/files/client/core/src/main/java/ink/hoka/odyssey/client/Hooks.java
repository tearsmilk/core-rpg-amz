package ink.hoka.odyssey.client;

import com.mojang.blaze3d.vertex.PoseStack;
import ink.hoka.odyssey.client.api.CameraState;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.FogState;
import ink.hoka.odyssey.client.api.InputState;
import ink.hoka.odyssey.client.api.SkyFrame;
import ink.hoka.odyssey.client.api.WorldStage;
import net.fabricmc.fabric.api.client.rendering.v1.world.AbstractWorldRenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Pose;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Крючки, которые модули вешают через {@link ClientContext}. Каждый помечен владельцем — при выгрузке модуля
 * снимается всё его. Вызываются из клиентского потока (тик, HUD, миксины мыши, камеры, рендера мира).
 */
public final class Hooks {

    private Hooks() {}

    record Owned<T>(String owner, T hook) {}

    static final List<Owned<Runnable>> TICK = new ArrayList<>();
    static final List<Owned<ClientContext.HudLayer>> HUD = new ArrayList<>();
    static final List<Owned<ClientContext.MouseHook>> MOUSE = new ArrayList<>();
    static final List<Owned<ClientContext.CameraHook>> CAMERA = new ArrayList<>();
    static final List<Owned<ClientContext.SkyHook>> SKY = new ArrayList<>();
    static final List<Owned<ClientContext.FogHook>> FOG = new ArrayList<>();
    static final List<Owned<BooleanSupplier>> CLOUDS = new ArrayList<>();
    static final List<Owned<BooleanSupplier>> WEATHER = new ArrayList<>();
    static final Map<WorldStage, List<Owned<ClientContext.WorldPassHook>>> WORLD_PASS = new EnumMap<>(WorldStage.class);
    static final List<Owned<Consumer<Identifier>>> WORLD = new ArrayList<>();
    static final List<Owned<ClientContext.AvatarRotHook>> AVATAR_ROT = new ArrayList<>();
    static final List<Owned<ClientContext.AvatarStateHook>> AVATAR_STATE = new ArrayList<>();
    static final List<Owned<Pose>> POSE = new ArrayList<>();                    // последний — действующий
    static final List<Owned<ClientContext.InputHook>> INPUT = new ArrayList<>();
    static final List<Owned<ClientContext.TravelHook>> TRAVEL = new ArrayList<>();
    static final List<Owned<AutoCloseable>> RESOURCES = new ArrayList<>();

    static {
        for (WorldStage s : WorldStage.values()) WORLD_PASS.put(s, new ArrayList<>());
    }

    /** Крен текущего кадра, радианы (миксин камеры подставляет его в поворот). */
    public static float roll;

    /** Модуль забрал небо в этом кадре — ванильный SkyRenderer ничего не рисует (сбрасывается каждый кадр). */
    public static boolean skyClaimed;

    /** Мир, о котором последний раз сообщили крючкам onWorld (сравнение по объекту: Minestom пересоздаёт мир). */
    private static ClientLevel lastLevel;

    static void removeOwner(String owner) {
        TICK.removeIf(o -> o.owner().equals(owner));
        HUD.removeIf(o -> o.owner().equals(owner));
        MOUSE.removeIf(o -> o.owner().equals(owner));
        CAMERA.removeIf(o -> o.owner().equals(owner));
        SKY.removeIf(o -> o.owner().equals(owner));
        FOG.removeIf(o -> o.owner().equals(owner));
        CLOUDS.removeIf(o -> o.owner().equals(owner));
        WEATHER.removeIf(o -> o.owner().equals(owner));
        for (var l : WORLD_PASS.values()) l.removeIf(o -> o.owner().equals(owner));
        WORLD.removeIf(o -> o.owner().equals(owner));
        AVATAR_ROT.removeIf(o -> o.owner().equals(owner));
        AVATAR_STATE.removeIf(o -> o.owner().equals(owner));
        POSE.removeIf(o -> o.owner().equals(owner));
        INPUT.removeIf(o -> o.owner().equals(owner));
        TRAVEL.removeIf(o -> o.owner().equals(owner));
        for (var o : List.copyOf(RESOURCES)) if (o.owner().equals(owner)) close(o);
        RESOURCES.removeIf(o -> o.owner().equals(owner));
        Pipelines.removeOwner(owner);
    }

    static void clear() {
        TICK.clear();
        HUD.clear();
        MOUSE.clear();
        CAMERA.clear();
        SKY.clear();
        FOG.clear();
        CLOUDS.clear();
        WEATHER.clear();
        for (var l : WORLD_PASS.values()) l.clear();
        WORLD.clear();
        AVATAR_ROT.clear();
        AVATAR_STATE.clear();
        POSE.clear();
        INPUT.clear();
        TRAVEL.clear();
        for (var o : List.copyOf(RESOURCES)) close(o);
        RESOURCES.clear();
        Pipelines.clear();
        roll = 0;
        skyClaimed = false;
        lastLevel = null;
    }

    private static void close(Owned<AutoCloseable> o) {
        try {
            o.hook().close();
        } catch (Throwable e) {
            HokaOdysseyClient.LOG.warn("Модуль {}: ресурс не закрылся", o.owner(), e);
        }
    }

    static void tick() {
        world();
        for (var o : List.copyOf(TICK)) safe(o.owner(), () -> o.hook().run());
    }

    static void hud(GuiGraphics g, DeltaTracker d) {
        for (var o : List.copyOf(HUD)) safe(o.owner(), () -> o.hook().render(g, d));
    }

    /** true — движение мыши забрано модулем (игрок не поворачивается). */
    public static boolean mouse(double yawDeg, double pitchDeg) {
        boolean taken = false;
        for (var o : List.copyOf(MOUSE)) {
            boolean[] r = {false};
            safe(o.owner(), () -> r[0] = o.hook().onMouse(yawDeg, pitchDeg));
            taken |= r[0];
        }
        return taken;
    }

    public static boolean hasMouse() { return !MOUSE.isEmpty(); }

    public static boolean hasCamera() { return !CAMERA.isEmpty(); }

    /** Применить крючки камеры: state на входе — как насчитала игра, на выходе — что нарисовать. */
    public static void camera(CameraState state, float partialTick) {
        for (var o : List.copyOf(CAMERA)) safe(o.owner(), () -> o.hook().apply(state, partialTick));
    }

    // ───────────────────────── небо и туман ─────────────────────────

    public static boolean hasSky() { return !SKY.isEmpty(); }

    /** Крючки неба (в проходе кадра). true — кто-то из модулей забрал небо. */
    public static boolean sky(SkyFrame frame) {
        if (shaderPackActive()) return false;                                   // небо рисует шейдерпак Iris
        boolean claimed = false;
        for (var o : List.copyOf(SKY)) {
            boolean[] r = {false};
            safe(o.owner(), () -> r[0] = o.hook().render(frame));
            claimed |= r[0];
        }
        return claimed;
    }

    public static boolean hasFog() { return !FOG.isEmpty(); }

    public static void fog(FogState state, Camera camera, float partialTick) {
        for (var o : List.copyOf(FOG)) safe(o.owner(), () -> o.hook().apply(state, camera, partialTick));
    }

    public static boolean hideClouds() { return any(CLOUDS); }

    public static boolean hideWeather() { return any(WEATHER); }

    private static boolean any(List<Owned<BooleanSupplier>> list) {
        if (list.isEmpty()) return false;
        boolean hide = false;
        for (var o : List.copyOf(list)) {
            boolean[] r = {false};
            safe(o.owner(), () -> r[0] = o.hook().getAsBoolean());
            hide |= r[0];
        }
        return hide;
    }

    static void worldPass(WorldStage stage, AbstractWorldRenderContext ctx) {
        var list = WORLD_PASS.get(stage);
        if (list.isEmpty()) return;
        for (var o : List.copyOf(list)) safe(o.owner(), () -> o.hook().render(ctx));
    }

    // ───────────────────────── мир ─────────────────────────

    /** Каждый тик: сменился объект мира — сообщить модулям. */
    static void world() {
        ClientLevel l = Minecraft.getInstance().level;
        if (l == lastLevel) return;
        lastLevel = l;
        Identifier id = l == null ? null : l.dimension().identifier();
        for (var o : List.copyOf(WORLD)) safe(o.owner(), () -> o.hook().accept(id));
    }

    /** Новый крючок onWorld: сразу сообщить текущий мир, если он есть. */
    static void worldNow(Owned<Consumer<Identifier>> o) {
        ClientLevel l = Minecraft.getInstance().level;
        if (l != null) safe(o.owner(), () -> o.hook().accept(l.dimension().identifier()));
    }

    // ───────────────────────── игроки ─────────────────────────

    public static boolean hasAvatarRot() { return !AVATAR_ROT.isEmpty(); }

    /** true — ванильный поворот тела не делать. */
    public static boolean avatarRotations(AvatarRenderState s, PoseStack pose, float bodyRot, float scale) {
        boolean cancel = false;
        for (var o : List.copyOf(AVATAR_ROT)) {
            boolean[] r = {false};
            safe(o.owner(), () -> r[0] = o.hook().apply(s.id, s, pose, bodyRot, scale));
            cancel |= r[0];
        }
        return cancel;
    }

    public static boolean hasAvatarState() { return !AVATAR_STATE.isEmpty(); }

    public static void avatarState(Avatar e, AvatarRenderState s, float partialTick) {
        for (var o : List.copyOf(AVATAR_STATE)) safe(o.owner(), () -> o.hook().apply(e, s, partialTick));
    }

    /** Поза своего игрока, заданная модулями (последняя), или null. */
    public static Pose forcedPose() {
        return POSE.isEmpty() ? null : POSE.getLast().hook();
    }

    static void forcePose(String owner, Pose pose) {
        POSE.removeIf(o -> o.owner().equals(owner));
        if (pose != null) POSE.add(new Owned<>(owner, pose));
    }

    public static boolean hasInput() { return !INPUT.isEmpty(); }

    public static void input(InputState s) {
        for (var o : List.copyOf(INPUT)) safe(o.owner(), () -> o.hook().apply(s));
    }

    public static boolean hasTravel() { return !TRAVEL.isEmpty(); }

    public static void travel(LocalPlayer p) {
        for (var o : List.copyOf(TRAVEL)) safe(o.owner(), () -> o.hook().before(p));
    }

    // ───────────────────────── шейдерпаки ─────────────────────────

    private static Method irisInUse;
    private static Object irisApi;
    private static boolean irisChecked;

    /** Включён шейдерпак Iris (тогда небо модулей не рисуем: шейдерпак рисует своё). */
    public static boolean shaderPackActive() {
        if (!irisChecked) {
            irisChecked = true;
            if (FabricLoader.getInstance().isModLoaded("iris")) {
                try {
                    Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    irisApi = c.getMethod("getInstance").invoke(null);
                    irisInUse = c.getMethod("isShaderPackInUse");
                } catch (Throwable e) {
                    HokaOdysseyClient.LOG.warn("Hoka Odyssey: Iris найден, но его API не ответил — считаю, что шейдерпака нет", e);
                }
            }
        }
        if (irisInUse == null) return false;
        try {
            return (Boolean) irisInUse.invoke(irisApi);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Ошибка в модуле не должна ронять игру — пишем в лог и снимаем модуль. */
    private static void safe(String owner, Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            HokaOdysseyClient.LOG.error("Модуль {} упал в крючке — выгружаю", owner, e);
            ModuleManager.failed(owner);
        }
    }
}
