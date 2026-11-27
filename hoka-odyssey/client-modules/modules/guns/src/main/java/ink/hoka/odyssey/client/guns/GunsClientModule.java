package ink.hoka.odyssey.client.guns;

import com.mojang.blaze3d.platform.InputConstants;
import ink.hoka.odyssey.client.api.CameraState;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Огнестрел у игрока (пара — серверный модуль guns). Стреляет сервер: модуль только присылает выстрелы с
 * направлением взгляда (раз в тик пачкой) и сразу же, не дожидаясь ответа, показывает всё своё — вспышку, трассер,
 * отдачу камеры, анимацию затвора, звук, счётчик патронов (сервер потом поправит, если отказал).
 * <ul>
 *   <li>ЛКМ — выстрел ({@link ClientContext#onClick}: клик забран, игра не ломает блок и не бьёт); зажата — очередь
 *       с темпом оружия, режим «одиночный» — по нажатию;</li>
 *   <li>ПКМ — прицел (клик тоже забран; серверу — ADS): модуль плавно сводит оружие к глазу (целик и мушка — по
 *       центру экрана) и приближает обзор;</li>
 *   <li>R — перезарядка (только с оружием в руке; spaceflight тогда не «ложится»), V — режим огня.</li>
 * </ul>
 * Модель в руке — {@link ViewModel} (рисуется в onWorld, рука игры спрятана), эффекты — {@link Fx}, HUD —
 * {@link GunHud}, отдача — {@link RecoilSpline}.
 */
public final class GunsClientModule implements ClientModule {
    /** Ключ в System.getProperties: прятать ли прицел игры (общий флаг для обёртки HUD — она одна на клиент). */
    static final String HIDE_CROSSHAIR = "hoka.guns.hideCrosshair";
    private static final String CROSSHAIR_HOOK = "hoka.guns.crosshairHook";
    /** Ключи в System.getProperties: обработчик начала тика игры (Runnable модуля) и признак, что слушатель стоит. */
    static final String PRE_TICK = "hoka.guns.preTick";
    private static final String PRE_TICK_HOOK = "hoka.guns.preTickHook";
    /** Поворот взгляда через spaceflight (в невесомости взгляд держит он): BiFunction(dYaw°, dPitch°) → забрал ли. */
    static final String LOOK_KICK = "hoka.spaceflight.lookKick";
    /** Отдача в вакууме через spaceflight: Predicate(double[] {dx, dy, dz} блоков) → отнёс ли (свободный полёт). */
    static final String RECOIL = "hoka.spaceflight.recoil";
    private static final float INACCURACY_K = 0.0172275f;

    private ClientContext ctx;
    private GunsConfig cfg;
    private MonoFont font;
    private GunHud hud;
    private final Fx fx = new Fx();
    private final Random rnd = new Random();
    private InputConstants.Key reloadKey;
    private InputConstants.Key modeKey;

    private Map<String, Proto.Spec> specs = new HashMap<>();
    /** Вид оружия по id (третий хвост CONFIG): пистолет, ручной затвор, оптика. */
    private Map<String, Proto.Kind> kinds = new HashMap<>();
    /** Отдача в вакууме по id (четвёртый хвост CONFIG), блоков за выстрел. */
    private Map<String, Float> vacRecoil = new HashMap<>();
    /** Дробь, свой прицел, перезарядка по одному патрону по id (пятый хвост CONFIG). */
    private Map<String, Proto.Extra> extras = new HashMap<>();
    private float vacVolume = 0.35f;
    private final Map<String, ViewModel> models = new HashMap<>();
    private final Set<String> watched = new HashSet<>();
    /** Поза тела от третьего лица (своя в F5 и чужих) — через крючок ядра onModelParts, если он есть. */
    private final ThirdPerson third = new ThirdPerson();
    private boolean partsHook;
    private final Map<String, String> playerAnimPaths = new HashMap<>();
    private final Set<String> watchedAnims = new HashSet<>();
    /** Оружие за спиной (слой модели игрока). */
    private BackGun back;

    // ---- что прислал сервер
    private String srvGun = "";
    private int srvAmmo;
    private int srvReserve;
    private int srvMode;
    private int srvFlags;
    private boolean gotState;

    // ---- своё
    private Proto.Spec held;
    private int heldSlot = -1;
    private int ammo;
    private int mode;
    /** Когда режим переключили у себя (нс): до подтверждения сервером его STATE со старым режимом не применяем. */
    private long modeSetNs;
    private int seq;
    /** Отправленные, но ещё не подтверждённые пачки: {seq, выстрелов}. */
    private final Deque<int[]> inflight = new ArrayDeque<>();
    private final List<Proto.Shot> pending = new ArrayList<>();
    private long lastShotNs;
    private long nextShotNs;
    private boolean clickEdge;
    private boolean triggerWas;
    private boolean semiFired;
    private long reloadStartNs = -1;
    private float reloadLen;
    private int reloadKind;
    /** Перезарядка по одному патрону (вид 3): циклов loop, с пустым ли магазином, какая часть играет (−1 — ещё никакая). */
    private int shellLoops;
    private boolean shellEmpty;
    private int shellPhase = -1;
    private long drawUntilNs;
    /** Ручной затвор: когда начать анимацию затвора (нс, −1 — не надо). */
    private long boltAtNs = -1;
    /**
     * Ручной затвор: оружие (серийный номер предмета) → когда закончится передёргивание после выстрела (нс). Убрал
     * оружие раньше — затвор не передёрнут: при доставании сначала draw, потом bolt (как и на сервере).
     */
    private final Map<String, Long> boltDue = new HashMap<>();
    private String heldKey = "";
    private float ads;
    private long lastFrameNs;
    private final RecoilSpline recoilPitch = new RecoilSpline();
    private final RecoilSpline recoilYaw = new RecoilSpline();
    private float bloom;
    private long flashNs = -1;
    private float flashSpin;
    private int hitKind;
    private long hitNs;
    private String hint = "";
    private long hintNs;
    private Vec3 muzzleWorld;
    private boolean handHidden;
    private boolean adsSent;
    private boolean reloadWas;
    private boolean modeWas;
    // ---- приближение (FOV через walkingSpeed своих способностей, только у себя на клиенте)
    private boolean zoomOn;
    private float savedWalk;
    private float lastWalk;
    // ---- угол обзора мира: как его считает игра (GameRenderer.tickFov — множитель с плавным переходом по тикам)
    private float fovMod = 1;
    private float fovModO = 1;
    // ---- покачивание оружия за взглядом
    private float lastYaw = Float.NaN;
    private float lastPitch;
    private float swayX;
    private float swayY;
    private Identifier flashTex;
    private final Map<String, Identifier> hudTex = new HashMap<>();
    private int rejects;

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        cfg = GunsConfig.load(ctx.dataDir(), ctx.logger());
        reloadKey = key(cfg.reloadKey, "key.keyboard.r");
        modeKey = key(cfg.fireModeKey, "key.keyboard.v");
        font = new MonoFont(ctx.logger(), cfg.monoDigits);
        hud = new GunHud(font);
        ctx.onMessage(this::onMessage);
        ctx.onTick(this::tick);
        ctx.onWorld(this::world);
        ctx.onHud(this::hud);
        ctx.onCamera(this::camera);
        ctx.onClick(this::click);
        ctx.onMouse(this::mouse);
        installCrosshairHook();
        installPreTickHook();
        System.getProperties().put(PRE_TICK, (Runnable) this::preTick);
        partsHook = installPartsHook();
        if (cfg.backGun) back = new BackGun(ctx.logger(), cfg.backTune, cfg.holsterTune);
        ctx.send(Proto.hello());
        ctx.logger().info("guns: загружен (перезарядка — {}, режим огня — {}, размер оружия {}; поза тела от третьего"
                + " лица — {})", cfg.reloadKey, cfg.fireModeKey, cfg.viewmodelScale,
                partsHook ? "есть (onModelParts)" : "нет: ядро без onModelParts — поза игры, в прицеле поза лука");
    }

    @Override
    public void disable() {
        if (adsSent) {                                             // иначе сервер так и держит замедление прицела
            adsSent = false;
            try {
                ctx.send(Proto.ads(false));
            } catch (RuntimeException ignored) {
                // соединения уже нет
            }
        }
        System.getProperties().remove(HIDE_CROSSHAIR);
        System.getProperties().remove(PRE_TICK);
        restoreZoom();
        if (back != null) back.close();
        back = null;
        for (ViewModel vm : models.values()) vm.close();
        models.clear();
        fx.close();
        if (font != null) font.close();
        held = null;
    }

    /**
     * Крючок ядра onModelParts (ядро 0.56+): ищем рефлексией — модуль собирается и работает и со старым ядром (тогда
     * от третьего лица поза игры: рука с оружием, в прицеле — нет). Интерфейс крючка — прокси.
     */
    private boolean installPartsHook() {
        if (!cfg.thirdPerson) return false;
        try {
            Class<?> hook = Class.forName("ink.hoka.odyssey.client.api.ClientContext$ModelPartsHook", false,
                    ClientContext.class.getClassLoader());
            // класс контекста ядра закрытый: проверяем по нему (переопределён ли default), а зовём метод интерфейса
            if (ctx.getClass().getMethod("onModelParts", hook).getDeclaringClass().isInterface()) return false;
            java.lang.reflect.Method m = ClientContext.class.getMethod("onModelParts", hook);
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(hook.getClassLoader(), new Class<?>[] {hook},
                    (self, method, args) -> switch (method.getName()) {
                        case "apply" -> {
                            third.apply((Entity) args[0], (net.minecraft.client.model.HumanoidModel<?>) args[1],
                                    (net.minecraft.client.renderer.entity.state.HumanoidRenderState) args[2]);
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(self);
                        case "equals" -> self == args[0];
                        case "toString" -> "guns.ModelPartsHook";
                        default -> null;
                    });
            m.invoke(ctx, proxy);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            ctx.logger().warn("guns: крючок onModelParts не поставлен: {}", e.toString());
            return false;
        }
    }

    private InputConstants.Key key(String name, String fallback) {
        try {
            return InputConstants.getKey(name);
        } catch (RuntimeException e) {
            ctx.logger().warn("guns: клавиша {} не понята — {}", name, fallback);
            return InputConstants.getKey(fallback);
        }
    }

    /**
     * Прицел игры прятать, пока в руке оружие (у оружия свой — по разбросу). Обёртка над слоем HUD «crosshair» Fabric
     * API ставится один раз на весь клиент (снять регистрацию нельзя), а решает она по общему флагу — поэтому
     * перезагрузка модуля не плодит обёрток, а выгруженный модуль прицел не прячет.
     */
    private void installCrosshairHook() {
        if (System.getProperties().containsKey(CROSSHAIR_HOOK)) return;
        try {
            net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.replaceElement(
                    net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CROSSHAIR,
                    orig -> (g, delta) -> {
                        if (!Boolean.TRUE.equals(System.getProperties().get(HIDE_CROSSHAIR))) orig.render(g, delta);
                    });
            System.getProperties().put(CROSSHAIR_HOOK, Boolean.TRUE);
        } catch (Throwable e) {
            ctx.logger().warn("guns: прицел игры не спрятать ({}) — будет виден вместе со своим", e.toString());
        }
    }

    /**
     * Слушатель начала тика игры (Fabric START_CLIENT_TICK) — один на клиент, как обёртка прицела; зовёт то, что
     * лежит в {@link #PRE_TICK} (у выгруженного модуля — ничего).
     */
    private void installPreTickHook() {
        if (System.getProperties().containsKey(PRE_TICK_HOOK)) return;
        try {
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(c -> {
                if (System.getProperties().get(PRE_TICK) instanceof Runnable r) r.run();
            });
            System.getProperties().put(PRE_TICK_HOOK, Boolean.TRUE);
        } catch (Throwable e) {
            ctx.logger().warn("guns: слушатель начала тика не поставлен ({}) — щелчок ЛКМ в прицеле может теряться",
                    e.toString());
        }
    }

    /**
     * Начало тика, до разбора клавиш игрой. В прицеле игрок «использует» предмет (поза лука от третьего лица): игра
     * тогда молча выбрасывает щелчки ЛКМ и startAttack (наш onClick) не зовёт — забираем их сами раньше неё.
     */
    private void preTick() {
        Minecraft mc = Minecraft.getInstance();
        if (held == null || mc.screen != null || mc.player == null || !mc.player.isUsingItem()) return;
        // только «использование» оружия (поза прицела): ест/пьёт другой рукой — щелчки игра выбрасывает, как всегда
        if (mc.player.getUsedItemHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        while (mc.options.keyAttack.consumeClick()) clickEdge = true;
    }

    // ---------------------------------------------------------------- сервер

    private void onMessage(byte[] data) {
        if (data == null || data.length == 0) return;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int op = in.readUnsignedByte();
            switch (op) {
                case Proto.S_CONFIG -> {
                    float[] vv = {0.35f};
                    playerAnimPaths.clear();
                    Map<String, String> gunItems = new HashMap<>();
                    Map<String, Proto.Kind> k = new HashMap<>();
                    Map<String, Float> vr = new HashMap<>();
                    Map<String, Proto.Extra> ex = new HashMap<>();
                    specs = Proto.readConfig(in, vv, playerAnimPaths, gunItems, k, vr, ex);
                    kinds = k;
                    extras = ex;
                    vacRecoil = vr;
                    if (back != null) back.setItems(gunItems, k);
                    vacVolume = vv[0];
                    for (Proto.Spec s : specs.values()) watch(s);
                    third.clearAnims();
                    for (var e : playerAnimPaths.entrySet()) watchAnim(e.getKey(), e.getValue());
                    ctx.logger().info("guns: оружие с сервера: {}", specs.keySet());
                }
                case Proto.S_STATE -> {
                    srvGun = in.readUTF();
                    srvAmmo = in.readInt();
                    srvReserve = in.readInt();
                    srvMode = in.readUnsignedByte();
                    srvFlags = in.readUnsignedByte();
                    int ack = in.readInt();
                    while (!inflight.isEmpty() && inflight.peekFirst()[0] <= ack) inflight.pollFirst();
                    int unacked = pending.size();
                    for (int[] f : inflight) unacked += f[1];
                    ammo = Math.max(0, srvAmmo - unacked);
                    if (srvMode == mode || System.nanoTime() - modeSetNs > 1_000_000_000L) mode = srvMode;
                    gotState = true;
                    if ((srvFlags & Proto.FLAG_RELOADING) == 0 && reloadStartNs >= 0
                            && (System.nanoTime() - reloadStartNs) / 1e9 > Math.min(reloadLen, 0.5)) {
                        reloadStartNs = -1;                        // сервер закончил (или отменил) перезарядку
                    }
                }
                case Proto.S_TRACER -> {
                    int shooter = in.readInt();
                    Vec3 o = new Vec3(in.readDouble(), in.readDouble(), in.readDouble());
                    Vec3 v = new Vec3(in.readFloat(), in.readFloat(), in.readFloat());
                    float life = in.readFloat();
                    int color = in.readInt();
                    otherShot(shooter, o, v, life, color);
                }
                case Proto.S_IMPACT -> {
                    // искры и крошку блока рассылает сервер частицами игры — здесь ничего
                }
                case Proto.S_RELOAD -> {
                    int kind = in.readUnsignedByte();
                    float sec = in.readFloat();
                    if (kind == 3 && in.available() >= 2) {
                        int loops = in.readUnsignedByte();
                        boolean empty = in.readUnsignedByte() != 0;
                        if (reloadStartNs >= 0 && reloadKind == 3) {   // уже идёт у себя — поправить по серверу
                            shellLoops = loops;
                            shellEmpty = empty;
                            reloadLen = Math.max(0.1f, sec);
                            break;
                        }
                        shellLoops = loops;
                        shellEmpty = empty;
                    }
                    if (reloadStartNs < 0 || reloadKind != kind) startReloadLocal(kind, sec);
                }
                case Proto.S_REJECT -> {
                    in.readInt();
                    int reason = in.readUnsignedByte();
                    rejects++;
                    if (reason == 5) ctx.logger().debug("guns: сервер отклонил выстрел (угол)");
                }
                case Proto.S_POSE -> {
                    int id = in.readInt();
                    int flags = in.readUnsignedByte();
                    float since = in.readFloat();
                    float len = in.readFloat();
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null && id == mc.player.getId()) break;
                    ThirdPerson.Signals sg = third.signals(id);
                    sg.aim = (flags & Proto.POSE_AIM) != 0;
                    if ((flags & Proto.POSE_RELOAD) != 0 && len > 0) {
                        long start = System.nanoTime() - (long) (since * 1e9);
                        if (sg.reloadStartNs < 0 || Math.abs(start - sg.reloadStartNs) > 300_000_000L) sg.reloadStartNs = start;
                        sg.reloadLen = len;
                    } else {
                        sg.reloadStartNs = -1;
                    }
                    if ((flags & Proto.POSE_HOLDING) == 0) third.forget(id);
                }
                case Proto.S_BACK -> {
                    int id = in.readInt();
                    String gun = in.readUTF();
                    if (back != null) back.set(id, gun);
                }
                case Proto.S_HIT -> {
                    hitKind = in.readUnsignedByte();
                    hitNs = System.nanoTime();
                    if (held != null) {
                        String s = held.sound(hitKind == 3 ? "kill" : hitKind == 2 ? "head_hit" : "flesh_hit");
                        Sfx.play(s, 0.8f * cfg.volume, 1f);
                    }
                }
                default -> {
                }
            }
        } catch (IOException | RuntimeException e) {
            ctx.logger().warn("guns: сообщение сервера не разобрано: {}", e.toString());
        }
    }

    /** Следить за файлами оружия: модель (разобрать заново при смене), силуэт и вспышка (текстуры ядра). */
    private void watch(Proto.Spec s) {
        if (!s.model().isEmpty() && watched.add(s.model())) {
            String path = s.model();
            ctx.onFile(path, bytes -> {
                ViewModel old = models.remove(path);
                if (old != null) old.close();
                if (bytes != null) {
                    ViewModel vm = ViewModel.load(path, bytes, ctx.logger());
                    if (vm != null) models.put(path, vm);
                }
            });
        }
        if (!s.hud().isEmpty()) hudTex.computeIfAbsent(s.hud(), ctx::texture);
        if (!s.flash().isEmpty() && flashTex == null) flashTex = ctx.texture(s.flash());
    }

    /** Анимации тела для оружия gun — файл с сервера (обновится сам при смене). */
    private void watchAnim(String gun, String path) {
        Runnable apply = () -> {
            byte[] b = ctx.file(path).orElse(null);
            PlayerAnim a = null;
            if (b != null) {
                try {
                    a = PlayerAnim.parse(b);
                } catch (RuntimeException e) {
                    ctx.logger().warn("guns: анимации тела {} не разобраны: {}", path, e.toString());
                }
            }
            for (var e : playerAnimPaths.entrySet()) {
                if (e.getValue().equals(path)) third.setAnim(e.getKey(), a);
            }
            if (a != null) ctx.logger().info("guns: анимации тела от третьего лица {} — {}", path, a.clips.keySet().size());
        };
        if (watchedAnims.add(path)) ctx.onFile(path, bytes -> apply.run());
        else apply.run();
    }

    private void otherShot(int shooterId, Vec3 eye, Vec3 v, float life, int color) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && shooterId == mc.player.getId()) return;
        third.signals(shooterId).shotNs = System.nanoTime();
        Vec3 o = eye;
        Entity e = mc.level == null ? null : mc.level.getEntity(shooterId);
        if (e != null) {                                           // ствол — справа-впереди-ниже глаз
            Vec3 look = v.normalize();
            Vec3 right = look.cross(new Vec3(0, 1, 0));
            right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
            Vec3 up = right.cross(look);
            o = eye.add(look.scale(0.9)).add(right.scale(0.3)).add(up.scale(-0.4));
        }
        if (cfg.tracers) {
            fx.tracer(o, v, life, color, false);
            fx.flash(o, 0.55f);
        }
    }

    // ---------------------------------------------------------------- ввод и тик

    private boolean click(boolean attack, boolean heldDown) {
        Minecraft mc = Minecraft.getInstance();
        if (held == null || mc.screen != null) return false;
        if (attack) {
            if (!heldDown) clickEdge = true;
            return true;                                           // ЛКМ с оружием — только выстрел
        }
        return true;                                               // ПКМ — прицел (держится, пока зажата «использовать»)
    }

    private static Proto.Spec specOf(Map<String, Proto.Spec> specs, ItemStack s) {
        String id = gunId(s);
        return id == null ? null : specs.get(id);
    }

    /** Метка hoka_gun предмета (custom_data) или null. */
    static String gunId(ItemStack s) {
        if (s == null || s.isEmpty()) return null;
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return null;
        String id = cd.copyTag().getStringOr("hoka_gun", "");
        return id.isEmpty() ? null : id;
    }

    /** Ключ оружия для затвора: серийный номер предмета (hoka_gun_sn), без него — слот. */
    private static String gunKey(ItemStack s, int slot) {
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        String sn = cd == null ? "" : cd.copyTag().getStringOr("hoka_gun_sn", "");
        return sn.isEmpty() ? "slot:" + slot : sn;
    }

    private static int itemAmmo(ItemStack s, int def) {
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return def;
        CompoundTag t = cd.copyTag();
        return t.getIntOr("hoka_gun_ammo", def);
    }

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        third.cleanup();
        if (back != null) back.tick(mc);
        tickFov(mc);
        ItemStack stack = p.getMainHandItem();
        Proto.Spec now = specOf(specs, stack);
        int slot = p.getInventory().getSelectedSlot();
        if (now != held || slot != heldSlot) {
            if (held != null && now == null) Sfx.play(held.sound("put_away"), 0.6f * cfg.volume, 1f);
            held = now;
            heldSlot = slot;
            resetGun();
            if (held != null) {
                ammo = itemAmmo(stack, held.magazine());
                long t = System.nanoTime();
                drawUntilNs = t + (long) (held.draw() * 1e9);
                heldKey = gunKey(stack, slot);
                boltDue.values().removeIf(d -> d <= t);
                float bolt = kind().boltTime();
                if (bolt > 0 && boltDue.containsKey(heldKey)) {    // затвор не передёрнут — передёрнуть после draw
                    boltAtNs = drawUntilNs;
                    drawUntilNs += (long) (bolt * 1e9);
                    boltDue.put(heldKey, drawUntilNs);
                }
                ViewModel vm = models.get(held.model());
                if (vm != null) vm.play("draw");
                Sfx.play(held.sound("draw"), 0.7f * cfg.volume, 1f);
            }
        }
        if (held == null) {
            restoreZoom();
            if (handHidden) {
                ctx.hideHand(false);
                handHidden = false;
            }
            System.getProperties().remove(HIDE_CROSSHAIR);
            flush();
            return;
        }
        keys(mc);
        flush();
        ViewModel vm = models.get(held.model());
        if (vm != null) for (String s : vm.dueSounds()) Sfx.play(s, 0.8f * cfg.volume, 1f);
        if (reloadStartNs >= 0 && (System.nanoTime() - reloadStartNs) / 1e9 > reloadLen + 1.5) reloadStartNs = -1;
        zoom(p);
    }

    /** R и V — по фронту нажатия; опрос и в тике, и в каждом кадре (короткое нажатие между тиками не теряется). */
    private void keys(Minecraft mc) {
        boolean input = mc.screen == null;
        boolean r = input && physical(mc, reloadKey);
        if (r && !reloadWas) requestReload();
        reloadWas = r;
        boolean v = input && physical(mc, modeKey);
        if (v && !modeWas) toggleMode();
        modeWas = v;
    }

    private void resetGun() {
        if (adsSent) {
            adsSent = false;
            ctx.send(Proto.ads(false));
        }
        pending.clear();
        inflight.clear();
        reloadStartNs = -1;
        shellPhase = -1;
        boltAtNs = -1;
        ads = 0;
        flashNs = -1;
        clickEdge = false;
        triggerWas = false;
        semiFired = false;
        nextShotNs = 0;
        hint = "";
    }

    private void flush() {
        if (pending.isEmpty()) return;
        seq++;
        int n = 0;
        while (!pending.isEmpty()) {
            List<Proto.Shot> part = new ArrayList<>(pending.subList(0, Math.min(16, pending.size())));
            pending.subList(0, part.size()).clear();
            ctx.send(Proto.fire(seq, part));
            n += part.size();
            if (!pending.isEmpty()) seq++;
        }
        inflight.addLast(new int[] {seq, n});
        while (inflight.size() > 64) inflight.pollFirst();
    }

    private void requestReload() {
        if (held == null || reloading() || drawing()) return;
        if (ammo >= held.magazine() + 1) return;
        if (gotState && srvReserve == 0) {
            setHint("НЕТ ПАТРОНОВ");
            Sfx.play(held.sound("dry_fire"), 0.8f * cfg.volume, 1f);
            return;
        }
        ctx.send(Proto.reload());
        Proto.Extra ex = extra();
        if (ex.shell() != null) {                                  // по одному патрону: вступление, циклы, конец
            shellEmpty = ammo <= 0;
            shellLoops = ex.shellLoops(ammo, held.magazine() + 1, gotState ? srvReserve : -1);
            startReloadLocal(3, ex.shellTotal(shellEmpty, shellLoops));
            return;
        }
        int kind = ammo > 0 ? 1 : 2;
        startReloadLocal(kind, kind == 1 ? held.reloadTactical() : held.reloadEmpty());
    }

    /** Дробь, свой прицел, перезарядка по одному патрону у оружия в руке. */
    private Proto.Extra extra() {
        Proto.Extra e = held == null ? null : extras.get(held.id());
        return e == null ? Proto.Extra.NONE : e;
    }

    /**
     * Перезарядка по одному патрону — анимации TACZ по очереди: reload_intro (reload_intro_empty с пустым магазином),
     * reload_loop на каждый патрон, reload_end. Вызывать каждый кадр.
     */
    private void shellAnim() {
        if (!reloading() || reloadKind != 3 || held == null) return;
        float[] sh = extra().shell();
        if (sh == null) return;
        float el = (System.nanoTime() - reloadStartNs) / 1e9f;
        float intro = shellEmpty ? sh[1] : sh[0];
        int phase = el < intro ? 0 : Math.min(shellLoops + 1, 1 + (int) ((el - intro) / sh[3]));
        if (phase == shellPhase) return;
        shellPhase = phase;
        ViewModel vm = models.get(held.model());
        if (vm == null) return;
        if (phase == 0) vm.play(shellEmpty && vm.has("reload_intro_empty") ? "reload_intro_empty" : "reload_intro");
        else if (phase <= shellLoops) vm.play("reload_loop");
        else vm.play("reload_end");
    }

    private void startReloadLocal(int kind, float seconds) {
        boltAtNs = -1;                                             // перезарядка с пустым магазином передёргивает сама
        reloadKind = kind;
        reloadLen = Math.max(0.1f, seconds);
        reloadStartNs = System.nanoTime();
        shellPhase = -1;
        if (held == null) return;
        if (kind == 3) {
            shellAnim();
            return;
        }
        ViewModel vm = models.get(held.model());
        if (vm != null) vm.play(kind == 1 ? "reload_tactical" : "reload_empty");
        // звук на всю перезарядку (M16A1: в анимации TACZ своих звуков нет)
        Sfx.play(held.sound(kind == 1 ? "reload_tactical" : "reload_empty"), 0.8f * cfg.volume, 1f);
    }

    private void toggleMode() {
        if (held == null || !(held.auto() && held.semi())) return;
        mode = mode == 0 ? 1 : 0;
        modeSetNs = System.nanoTime();
        ctx.send(Proto.fireMode(mode));
        Sfx.play(held.sound("fire_select"), 0.8f * cfg.volume, 1f);
        ViewModel vm = models.get(held.model());
        if (vm != null && !reloading()) vm.play(mode == 0 ? "switch_auto" : "switch_semi");
        setHint(mode == 0 ? "РЕЖИМ: АВТО" : "РЕЖИМ: ОДИНОЧНЫЙ");
    }

    private void setHint(String s) {
        hint = s;
        hintNs = System.nanoTime();
    }

    private boolean reloading() {
        return reloadStartNs >= 0;
    }

    private boolean drawing() {
        return System.nanoTime() < drawUntilNs || (srvFlags & Proto.FLAG_DRAWING) != 0 && srvGun.equals(held == null ? "" : held.id());
    }

    private boolean vacuum() {
        return (srvFlags & Proto.FLAG_VACUUM) != 0;
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

    // ---------------------------------------------------------------- прицел: приближение

    /**
     * Приближение в прицеле — только у себя: игра умножает угол обзора на (скорость/walkingSpeed + 1)/2 (и ещё ×1,1
     * в полёте), с плавным переходом по тикам; подбираем walkingSpeed своих способностей так, чтобы вышло 1/zoom
     * (игра держит множитель в 0,1…1,5 — приближение до 10).
     * Сервер walkingSpeed на клиенте ни для чего, кроме обзора, не читает. Ползунок «эффекты обзора» учтён (на 0 —
     * без приближения).
     */
    private void zoom(LocalPlayer p) {
        // от третьего лица (F5) не приближаем: прицел там — только поза тела
        boolean fp = Minecraft.getInstance().options.getCameraType().isFirstPerson();
        float z = held == null || !fp ? 1 : 1 + (held.zoom() - 1) * AdsBlend.easeInOutCubic(ads);
        double fx = Minecraft.getInstance().options.fovEffectScale().get();
        if (z <= 1.001f || fx < 0.05) {
            restoreZoom();
            return;
        }
        var ab = p.getAbilities();
        float cur = ab.getWalkingSpeed();
        if (!zoomOn) {
            savedWalk = cur;
            zoomOn = true;
        } else if (Math.abs(cur - lastWalk) > 1e-6) {
            savedWalk = cur;                                       // сервер прислал свои способности
        }
        double want = 1 + (1 / z - 1) / fx;
        double fly = ab.flying ? 1.1 : 1;
        double attr = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
        // (скорость/walkingSpeed + 1)/2 = want: для приближения больше ×2 (оптика 8x) walkingSpeed отрицательная —
        // игра на клиенте берёт её только для угла обзора (на сервер способности с ней не уходят)
        double k = 2 * want / fly - 1;
        if (attr <= 0) return;
        if (Math.abs(k) < 1e-3) k = 1e-3;
        lastWalk = (float) (attr / k);
        ab.setWalkingSpeed(lastWalk);
    }

    private void restoreZoom() {
        if (!zoomOn) return;
        zoomOn = false;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && Math.abs(p.getAbilities().getWalkingSpeed() - lastWalk) < 1e-6) {
            p.getAbilities().setWalkingSpeed(savedWalk);
        }
    }

    /** Раз в тик — как GameRenderer.tickFov: множитель угла обзора тянется к цели игрока за полшага. */
    private void tickFov(Minecraft mc) {
        float target = 1;
        if (mc.getCameraEntity() instanceof net.minecraft.client.player.AbstractClientPlayer a) {
            target = a.getFieldOfViewModifier(mc.options.getCameraType().isFirstPerson(),
                    mc.options.fovEffectScale().get().floatValue());
        }
        fovModO = fovMod;
        fovMod = net.minecraft.util.Mth.clamp(fovMod + (target - fovMod) * 0.5f, 0.1f, 1.5f);
    }

    /**
     * Сжатие x, y камеры для оружия в руке: угол обзора мира (настройка × множитель игры, как GameRenderer.getFov)
     * → угол обзора модели. По умолчанию — как у TACZ: свой у каждого оружия (model_fov от бедра, к zoom_model_fov в
     * прицеле — {@link ViewModel#viewFov}); viewmodel_fov числом — один для всего оружия. Вода и лава сужают оба.
     */
    private float viewFovScale(Minecraft mc, float pt, ViewModel vm) {
        double view = cfg.viewmodelFov == GunsConfig.FOV_MODEL ? vm.viewFov(ads) : cfg.viewmodelFov;
        if (view <= 0 && cfg.viewmodelFov == GunsConfig.FOV_MODEL) view = 70;   // в модели угла нет — как рука игры
        if (view <= 0) return 1;
        double world = mc.options.fov().get() * net.minecraft.util.Mth.lerp(pt, fovModO, fovMod);
        var fluid = mc.gameRenderer.getMainCamera().getFluidInCamera();
        if (fluid == net.minecraft.world.level.material.FogType.WATER
                || fluid == net.minecraft.world.level.material.FogType.LAVA) {
            double k = net.minecraft.util.Mth.lerp(mc.options.fovEffectScale().get(), 1.0, 0.85714287);
            world *= k;
            view *= k;
        }
        return ViewModel.fovScale(world, view);
    }

    // ---------------------------------------------------------------- кадр: стрельба, отдача

    private void frameUpdate(Minecraft mc, LocalPlayer p, long now) {
        float dt = lastFrameNs == 0 ? 0 : Math.min(0.1f, (now - lastFrameNs) / 1e9f);
        lastFrameNs = now;
        bloom *= (float) Math.exp(-6 * dt);
        if (held == null) return;
        // прицел — пока зажата «использовать» (ПКМ): клик забран модулем, серверу — ADS (разброс, замедление). Сервер
        // в ответ ставит «использование» предмета (поза лука от третьего лица на ядре без onModelParts) — щелчки ЛКМ
        // тогда игра выбрасывает, их забирают preTick и кадр
        boolean aimKey = mc.screen == null && mc.options.keyUse.isDown();
        if (aimKey != adsSent) {
            adsSent = aimKey;
            ctx.send(Proto.ads(aimKey));
        }
        if (aimKey && p.isSprinting()) p.setSprinting(false);
        float target = aimKey && !reloading() && !drawing() ? 1 : 0;
        float rate = dt / Math.max(0.05f, held.aimTime());
        ads = target > ads ? Math.min(target, ads + rate) : Math.max(target, ads - rate * 1.2f);
        keys(mc);
        ThirdPerson.Signals own = third.signals(p.getId());       // своя поза от третьего лица (F5)
        own.aim = ads > 0.05f;
        own.reloadStartNs = reloading() ? reloadStartNs : -1;
        own.reloadLen = reloadLen;
        own.shotNs = lastShotNs;

        // нажатия ЛКМ забираем сами, каждый кадр: в прицеле («использование» предмета) игра их молча выбрасывает,
        // и короткий щелчок между кадрами иначе потерялся бы
        if (mc.screen == null) while (mc.options.keyAttack.consumeClick()) clickEdge = true;
        boolean down = mc.screen == null && (mc.options.keyAttack.isDown() || clickEdge);
        boolean edge = clickEdge || (down && !triggerWas);
        clickEdge = false;
        triggerWas = mc.options.keyAttack.isDown();
        if (!down) semiFired = false;
        long interval = (long) (held.intervalMs() * 1e6);
        if (down && edge && reloading() && reloadKind == 3 && ammo > 0) {
            reloadStartNs = -1;                                    // по одному патрону: выстрел прерывает перезарядку
        }
        shellAnim();
        if (down && !reloading() && !drawing()) {
            if (ammo <= 0) {
                if (edge) {
                    Sfx.play(held.sound("dry_fire"), 0.8f * cfg.volume, 1f);
                    setHint(keyName(reloadKey) + " — ПЕРЕЗАРЯДКА");
                }
            } else if (mode == 1 || !held.auto() || kind().boltTime() > 0) {
                // одиночный — по нажатию; очередь с помпой/затвором (M1897) — пока зажата, после каждого передёргивания
                boolean single = mode == 1 || !held.auto();
                if ((single ? edge && !semiFired : true) && now >= nextShotNs) {
                    shoot(mc, p, now);
                    semiFired = true;
                    nextShotNs = now + interval;
                    float bolt = kind().boltTime();
                    if (bolt > 0 && ammo > 0) {                    // ручной затвор: передёрнуть после промежутка
                        boltAtNs = now + interval;
                        nextShotNs = now + interval + (long) (bolt * 1e9);
                        boltDue.put(heldKey, nextShotNs);
                    }
                }
            } else {
                if (nextShotNs < now - interval) nextShotNs = now;
                int guard = 0;
                while (now >= nextShotNs && ammo > 0 && guard++ < 4) {
                    shoot(mc, p, now);
                    nextShotNs += interval;
                }
            }
        }
        if (boltAtNs >= 0 && now >= boltAtNs) {                    // затвор — анимация TACZ «bolt» со звуками
            boltAtNs = -1;
            ViewModel bvm = models.get(held.model());
            if (bvm != null && !reloading()) bvm.play("bolt");
        }
        // отдача камеры: изменение сплайнов с прошлого кадра
        double dp = recoilPitch.delta(now / 1_000_000) * cfg.recoil;
        double dy = recoilYaw.delta(now / 1_000_000) * cfg.recoil;
        if (dp != 0 || dy != 0) kick(p, dy, dp);
    }

    /** Вид оружия в руке (нет данных — винтовка без затвора и оптики). */
    private Proto.Kind kind() {
        Proto.Kind k = held == null ? null : kinds.get(held.id());
        return k == null ? Proto.Kind.RIFLE : k;
    }

    /**
     * Оптика: насколько закрыт экран (0 — не в прицеле, 1 — чёрный экран и круг прицела). Только от первого лица и у
     * оружия с прицелом; нарастает в последней трети сведения оружия к глазу.
     */
    private float scopeCover() {
        if (held == null || !kind().scope() || !Minecraft.getInstance().options.getCameraType().isFirstPerson()) return 0;
        return Math.max(0, Math.min(1, (ads - 0.7f) / 0.28f));
    }

    /** В оптике мышь медленнее: делённая на текущее приближение (как подзорная труба игры) × scope_sensitivity. */
    private boolean mouse(double yawDeg, double pitchDeg) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.screen != null || scopeCover() <= 0 || vacuum()) return false;
        float z = 1 + (held.zoom() - 1) * AdsBlend.easeInOutCubic(ads);
        double k = cfg.scopeSensitivity / Math.max(1, z);
        p.turn(yawDeg * k / 0.15, pitchDeg * k / 0.15);           // как MouseHandler.turnPlayer: градусы = ×0,15
        return true;
    }

    private static String keyName(InputConstants.Key k) {
        String s = k.getDisplayName().getString();
        return s.length() > 6 ? s.substring(0, 6) : s.toUpperCase();
    }

    /**
     * Отдача в вакууме: отнести себя назад вдоль выстрела на distance блоков — сразу, через spaceflight (скорость
     * в невесомости ведёт он). Только в свободном полёте — это решает spaceflight; сервер игрока со spaceflight сам
     * не толкает (нет spaceflight — толкает сервер, и тогда здесь ничего не делаем).
     */
    @SuppressWarnings("unchecked")
    private static void pushBack(Vec3 dir, float distance) {
        Object r = System.getProperties().get(RECOIL);
        if (!(r instanceof java.util.function.Predicate<?> f)) return;
        try {
            ((java.util.function.Predicate<double[]>) f).test(
                    new double[] {-dir.x * distance, -dir.y * distance, -dir.z * distance});
        } catch (RuntimeException ignored) {
            // spaceflight выгружается — отдачи не будет
        }
    }

    /** Повернуть взгляд на отдачу: pitch + — ствол вверх, yaw + — влево (как у сплайнов отдачи). */
    @SuppressWarnings("unchecked")
    private void kick(LocalPlayer p, double yaw, double pitch) {
        Object k = System.getProperties().get(LOOK_KICK);
        if (k instanceof BiFunction<?, ?, ?> f) {
            try {
                Object r = ((BiFunction<Double, Double, Boolean>) f).apply(-yaw, -pitch);
                if (Boolean.TRUE.equals(r)) return;               // в невесомости взгляд у spaceflight — повернул он
            } catch (RuntimeException ignored) {
                // spaceflight выгружается — повернём сами
            }
        }
        float x = (float) Math.max(-90, Math.min(90, p.getXRot() - pitch));
        float dx = x - p.getXRot();
        p.setXRot(x);
        p.xRotO += dx;
        p.setYRot(p.getYRot() - (float) yaw);
        p.yRotO -= (float) yaw;
    }

    private void shoot(Minecraft mc, LocalPlayer p, long now) {
        Proto.Spec g = held;
        Vec3 dir = p.calculateViewVector(p.getXRot(), p.getYRot());
        int ms = lastShotNs == 0 ? 0 : (int) Math.min(65535, (now - lastShotNs) / 1_000_000);
        lastShotNs = now;
        pending.add(new Proto.Shot(ms, (float) dir.x, (float) dir.y, (float) dir.z));
        if (pending.size() >= 16) flush();
        ammo--;
        if (p.isSprinting()) p.setSprinting(false);
        ViewModel vm = models.get(g.model());
        if (vm != null) vm.play("shoot");
        boolean vac = vacuum();
        Float vr = vacRecoil.get(g.id());
        if (vr != null && vr > 0) pushBack(dir, vr);
        Sfx.play(g.sound("shoot"), cfg.volume * (vac ? vacVolume : 1f), vac ? 0.7f : 0.97f + rnd.nextFloat() * 0.06f);
        flashNs = now;
        flashSpin = rnd.nextFloat() * (float) Math.PI * 2;
        float a = AdsBlend.easeInOutCubic(ads);
        double mod = 1 - a + a / Math.min(Math.sqrt(Math.max(1, g.zoom())), 1.5);
        long t = now / 1_000_000;
        recoilPitch.shot(g.pitch(), mod, t, rnd);
        recoilYaw.shot(g.yaw(), mod, t, rnd);
        bloom = Math.min(3, bloom + 1);
        if (cfg.tracers) {
            float inacc = ads > 0.95f ? g.spreadAim() : g.spreadHip();
            double s = INACCURACY_K * inacc;
            Vec3 o = mc.options.getCameraType().isFirstPerson() && muzzleWorld != null ? muzzleWorld
                    : p.getEyePosition().add(dir.scale(0.8));
            int pellets = extra().pellets();                       // дробь — трассер на каждую дробину
            for (int k = 0; k < pellets; k++) {
                Vec3 d = dir.add(tri(s), tri(s), tri(s)).normalize();
                fx.tracer(o, d.scale(g.speed()), vac ? 3 : g.life(), g.tracerColor(), true);
            }
            if (!mc.options.getCameraType().isFirstPerson()) fx.flash(o, 0.55f);
        }
    }

    private double tri(double s) {
        return s * (rnd.nextDouble() - rnd.nextDouble());
    }

    // ---------------------------------------------------------------- рисование

    private void world(ClientContext.WorldFrame frame) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        long now = System.nanoTime();
        frameUpdate(mc, p, now);
        Quaternionf q = frame.cameraState().orientation;
        Matrix4f pose = frame.pose().last().pose();
        // эффекты — в системе камеры (повёрнутые обратно её поворотом) и той же матрицей, что оружие в руке
        fx.render(frame.buffers(), new Matrix4f(pose).rotate(q), frame.camera(), q, flashTex);

        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        ViewModel vm = held == null ? null : models.get(held.model());
        boolean show = vm != null && firstPerson;
        boolean scoped = scopeCover() >= 1;                       // экран закрыт оптикой — оружие не видно
        if (show != handHidden) {
            ctx.hideHand(show);
            handHidden = show;
        }
        if (held != null && firstPerson) System.getProperties().put(HIDE_CROSSHAIR, Boolean.TRUE);
        else System.getProperties().remove(HIDE_CROSSHAIR);
        if (!show || scoped) {
            muzzleWorld = null;
            if (scoped) vm.update();
            return;
        }
        // покачивание за поворотом взгляда (инерция оружия), в прицеле — слабее
        float yaw = p.getViewYRot(frame.partialTick()), pitch = p.getViewXRot(frame.partialTick());
        if (!Float.isNaN(lastYaw)) {
            float dyaw = net.minecraft.util.Mth.wrapDegrees(yaw - lastYaw), dpitch = pitch - lastPitch;
            float k = 1 - 0.75f * ads;
            swayX += (Math.max(-6, Math.min(6, dyaw)) * 0.35f * k - swayX) * 0.25f;
            swayY += (Math.max(-6, Math.min(6, dpitch)) * 0.35f * k - swayY) * 0.25f;
        }
        lastYaw = yaw;
        lastPitch = pitch;

        vm.safety(mode == 0 && held.auto() ? "static_auto" : "static_semi");
        vm.update();
        Matrix4f m2c = vm.modelToCamera(cfg.viewmodelScale, ads);
        Matrix4f sway = new Matrix4f().rotateY((float) Math.toRadians(swayX)).rotateX((float) Math.toRadians(swayY));
        m2c = sway.mul(m2c);
        Matrix4f camToBuf = new Matrix4f(pose).rotate(q);
        // свой угол обзора оружия (как у TACZ — model_fov оружия при любой настройке): x, y камеры ×k
        Matrix4f fov = ViewModel.fovMatrix(viewFovScale(mc, frame.partialTick(), vm));
        vm.render(frame.buffers(), camToBuf, fov, m2c, light(mc, frame.camera()), ammo, cfg.arms);
        Vector3f mz = vm.muzzle(m2c);
        if (mz != null) {
            mz = fov.transformPosition(mz);                        // где ствол виден на экране
            Vector3f w = new Vector3f(mz).rotate(q);
            Vec3 c = frame.camera();
            muzzleWorld = new Vec3(c.x + w.x, c.y + w.y, c.z + w.z);
            if (flashNs > 0 && now - flashNs < 55_000_000L && flashTex != null && cfg.tracers) {
                float size = cfg.viewmodelScale * 13f / 16f * (vacuum() ? 0.6f : 1f) * kind().flashScale();
                vm.renderFlash(frame.buffers(), camToBuf, mz, flashTex, size * fov.m00(), flashSpin);  // в плоскости экрана
            }
        }
    }

    private int light(Minecraft mc, Vec3 cam) {
        int l = mc.level == null ? LightTexture.FULL_BRIGHT : LevelRenderer.getLightColor(mc.level, BlockPos.containing(cam));
        int block = Math.max(LightTexture.block(l), cfg.minLight);
        int sky = LightTexture.sky(l);
        if (flashNs > 0 && System.nanoTime() - flashNs < 60_000_000L) block = 15;   // вспышка освещает оружие
        return LightTexture.pack(block, sky);
    }

    private void camera(CameraState cam, float pt) {
        if (held == null || cam.detached || cfg.cameraShake <= 0) return;
        ViewModel vm = models.get(held.model());
        if (vm == null) return;
        Vector3f s = vm.cameraShake().mul(cfg.cameraShake * (1 - 0.6f * ads));
        if (s.lengthSquared() < 1e-6f) return;
        if (cam.rotation != null) {
            cam.rotation.rotateX((float) Math.toRadians(s.x)).rotateY((float) Math.toRadians(s.y))
                    .rotateZ((float) Math.toRadians(s.z));
        } else {
            cam.pitch -= s.x;
            cam.yaw -= s.y;
            cam.roll += s.z;
        }
    }

    private final GunHud.View view = new GunHud.View();

    private void hud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (held == null || mc.player == null) return;
        float cover = scopeCover();
        if (cover > 0) {                                           // оптика: и при F1 — это вид в прицел, не HUD
            ViewModel vm = models.get(held.model());
            ScopeOverlay.render(g, vm == null ? null : vm.reticle, cover, cfg.scopeSize);
        }
        if (mc.options.hideGui) return;
        long now = System.nanoTime();
        GunHud.View v = view;
        v.name = held.name();
        v.ammo = ammo;
        v.magazine = held.magazine();
        v.reserve = gotState ? srvReserve : -1;
        v.auto = mode == 0 && held.auto();
        v.bothModes = held.auto() && held.semi();
        v.reload = reloading() ? Math.min(1, (now - reloadStartNs) / 1e9f / reloadLen) : -1;
        v.drawing = drawing();
        v.vacuum = vacuum();
        v.ads = ads;
        boolean moving = mc.player.getDeltaMovement().horizontalDistanceSqr() > 0.003;
        v.spreadDeg = held.spreadHip() * (moving ? 1.15f : 1f);
        v.bloom = bloom;
        v.hitKind = hitKind;
        v.hitLeft = hitKind == 0 ? 0 : Math.max(0, 1 - (now - hitNs) / 0.35e9f);
        v.silhouette = hudTex.get(held.hud());
        v.crosshair = mc.options.getCameraType().isFirstPerson() && cover <= 0 && extra().crosshair();
        v.hint = !hint.isEmpty() && now - hintNs < 2_000_000_000L ? hint : ammo <= 0 ? "ПУСТО" : "";
        hud.render(g, v, cfg.hud);
    }
}
