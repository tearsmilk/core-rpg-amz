package ink.hoka.odyssey.client.guns;

import com.mojang.blaze3d.platform.InputConstants;
import ink.hoka.odyssey.client.api.CameraState;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import ink.hoka.odyssey.client.model.BbModel;
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
    /** Вид трассера по id (шестой хвост CONFIG): плазма или пуля. */
    private Map<String, Proto.Tracer> tracerKinds = new HashMap<>();
    /** Картинки вспышки у ствола по пути файла (у каждого оружия своя; flashTex — первая, для чужих выстрелов). */
    private final Map<String, Identifier> flashTexs = new HashMap<>();
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
    /** Где ствол модели в руке (первое лицо) относительно камеры — последний кадр; null — не виден. */
    private Vec3 muzzleRel;
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

    // ---- обвес (как в TACZ): каталог с сервера, свой обвес (LOADOUT), чужой (GEAR), модели обвесов, окно обвеса (Y)
    static final String ATT_JSON = "guns/attachments.json";
    Att.Catalog cat = Att.Catalog.EMPTY;
    private boolean catWatched;
    private final Map<String, AttModel> attModels = new HashMap<>();
    private final Set<String> attWatched = new HashSet<>();
    private final Map<String, Identifier> iconTex = new HashMap<>();
    /** Обвес своего оружия в руке и его характеристики (LOADOUT); null — нет. */
    private Proto.Loadout ownLoadout;
    private Proto.Spec eff;
    private Proto.Loadout effFrom;
    private Proto.Spec effBase;
    /** Обвес оружия в руке других игроков (и своего — для вида от третьего лица): id игрока → обвес. */
    private final Map<Integer, Att.Gear> gears = new HashMap<>();
    private InputConstants.Key refitKey;
    private InputConstants.Key zoomKey;
    private boolean refitWas;
    private boolean zoomWas;
    /** Окно обвеса: насколько открыто (0…1, «камера» к refit_view), какой слот смотрим (−1 — общий вид) и прошлый. */
    private float refitW;
    private int refitView = -1;
    private int refitPrevView = -1;
    private long refitViewNs;
    /** Обвес для показа в окне обвеса (наведён на обвес в списке) — только у себя; null — как на оружии. */
    Att.Gear preview;
    /** Звук, который сыграть, когда сервер ответит «готово» на действие с обвесом. */
    private String pendingSound = "";
    /** Прицел-обвес в этом кадре (для HUD, приближения, мыши): вид через окуляр и сколько пикселей экрана на тангенс. */
    private AttModel.Optic curOptic;
    private float pxPerTanK;

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        cfg = GunsConfig.load(ctx.dataDir(), ctx.logger());
        reloadKey = key(cfg.reloadKey, "key.keyboard.r");
        modeKey = key(cfg.fireModeKey, "key.keyboard.v");
        refitKey = key(cfg.refitKey, "key.keyboard.y");
        zoomKey = key(cfg.zoomKey, "key.keyboard.g");
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
        ctx.logger().info("guns: загружен (перезарядка — {}, режим огня — {}, обвес — {}, кратность — {}, размер оружия {};"
                + " поза тела от третьего лица — {})", cfg.reloadKey, cfg.fireModeKey, cfg.refitKey, cfg.zoomKey,
                cfg.viewmodelScale,
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
        for (AttModel am : attModels.values()) am.close();
        attModels.clear();
        if (Minecraft.getInstance().screen instanceof RefitScreen) Minecraft.getInstance().setScreen(null);
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
                    Map<String, Proto.Tracer> tk = new HashMap<>();
                    specs = Proto.readConfig(in, vv, playerAnimPaths, gunItems, k, vr, ex, tk);
                    kinds = k;
                    extras = ex;
                    tracerKinds = tk;
                    vacRecoil = vr;
                    if (back != null) back.setItems(gunItems, k);
                    vacVolume = vv[0];
                    for (Proto.Spec s : specs.values()) watch(s);
                    watchCatalog();
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
                    int tf = in.available() > 0 ? in.readUnsignedByte() : 0;
                    boolean plasma = (tf & Proto.TRACER_PLASMA) != 0;
                    float plen = plasma && in.available() >= 4 ? in.readFloat() : 0;
                    otherShot(shooter, o, v, life, color, (tf & Proto.TRACER_SILENCED) != 0, plasma,
                            Float.isFinite(plen) ? Math.max(0, Math.min(8, plen)) : 0);
                }
                case Proto.S_IMPACT -> {
                    // искры и крошку блока рассылает сервер частицами игры; здесь — след пули на блоке
                    Vec3 at = new Vec3(in.readDouble(), in.readDouble(), in.readDouble());
                    Vec3 nrm = new Vec3(in.readFloat(), in.readFloat(), in.readFloat());
                    int kind = in.readUnsignedByte();
                    int flags = in.available() > 0 ? in.readUnsignedByte() : 0;     // хвост (сервер 0.81+)
                    int rgb = in.available() >= 4 ? in.readInt() : 0x66CCFF;
                    if (kind == Proto.IMPACT_BLOCK && cfg.bulletHoles) {
                        fx.decal(at, nrm, (flags & Proto.IMPACT_PLASMA) != 0, rgb);
                    }
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
                case Proto.S_LOADOUT -> {
                    Proto.Loadout lo = Proto.readLoadout(in);
                    ownLoadout = lo.gun().isEmpty() ? null : lo;
                    preview = null;
                }
                case Proto.S_GEAR -> {
                    int id = in.readInt();
                    String gun = in.readUTF();
                    String csv = in.readUTF();
                    if (gun.isEmpty()) gears.remove(id);
                    else gears.put(id, Att.Gear.parseCsv(csv));
                }
                case Proto.S_REFIT_RESULT -> {
                    int code = in.readUnsignedByte();
                    String msg = in.readUTF();
                    if (code == 0 && !pendingSound.isEmpty()) Sfx.play(pendingSound, 0.8f * cfg.volume, 1f);
                    pendingSound = "";
                    if (Minecraft.getInstance().screen instanceof RefitScreen rs) rs.result(code, msg);
                    else if (!msg.isEmpty()) setHint(msg.toUpperCase(java.util.Locale.ROOT));
                }
                case Proto.S_HIT -> {
                    hitKind = in.readUnsignedByte();
                    hitNs = System.nanoTime();
                    if (held != null) {
                        String s = g().sound(hitKind == 3 ? "kill" : hitKind == 2 ? "head_hit" : "flesh_hit");
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
        if (!s.flash().isEmpty()) flashTexs.computeIfAbsent(s.flash(), ctx::texture);
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

    private void otherShot(int shooterId, Vec3 eye, Vec3 v, float life, int color, boolean silenced, boolean plasma,
                           float plasmaLength) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && shooterId == mc.player.getId()) return;
        third.signals(shooterId).shotNs = System.nanoTime();
        Entity e = mc.level == null ? null : mc.level.getEntity(shooterId);
        Vec3 o = handMuzzle(e, eye, v);
        if (cfg.tracers) {
            fx.tracer(eye, o, v, life, color, false, plasma, plasmaLength, e);
            if (!silenced) fx.flash(o, plasma ? 0.45f : 0.55f, plasma, color);
        }
    }

    /**
     * Где ствол у игрока от третьего лица (чужие и свой в F5): поза с оружием ({@link ThirdPerson}) держит его в
     * правой руке по взгляду — от правого плеча (ниже глаз, правее) вперёд по look на длину руки и оружия (у
     * пистолета короче). eye — глаза, look — куда летит пуля; e — стрелок (по оружию в руке — длина), null — винтовка.
     */
    private Vec3 handMuzzle(Entity e, Vec3 eye, Vec3 look) {
        Vec3 l = look.lengthSqr() < 1e-9 ? new Vec3(0, 0, 1) : look.normalize();
        Vec3 right = l.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
        boolean pistol = false;
        if (e instanceof net.minecraft.world.entity.LivingEntity le) {
            String id = gunId(le.getMainHandItem());
            Proto.Kind k = id == null ? null : kinds.get(id);
            pistol = k != null && k.pistol();
        }
        Vec3 shoulder = eye.add(0, -0.42, 0).add(right.scale(0.17));
        return shoulder.add(l.scale(pistol ? 0.85 : 1.2));
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
        if ((now != held || slot != heldSlot) && mc.screen instanceof RefitScreen) mc.setScreen(null);
        if (now != held || slot != heldSlot) {
            if (held != null && now == null) Sfx.play(g().sound("put_away"), 0.6f * cfg.volume, 1f);
            held = now;
            heldSlot = slot;
            resetGun();
            if (held != null) {
                ammo = itemAmmo(stack, g().magazine());
                long t = System.nanoTime();
                drawUntilNs = t + (long) (g().draw() * 1e9);
                heldKey = gunKey(stack, slot);
                boltDue.values().removeIf(d -> d <= t);
                float bolt = kind().boltTime();
                if (bolt > 0 && boltDue.containsKey(heldKey)) {    // затвор не передёрнут — передёрнуть после draw
                    boltAtNs = drawUntilNs;
                    drawUntilNs += (long) (bolt * 1e9);
                    boltDue.put(heldKey, drawUntilNs);
                }
                ViewModel vm = models.get(g().model());
                if (vm != null) vm.play("draw");
                Sfx.play(g().sound("draw"), 0.7f * cfg.volume, 1f);
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
        ViewModel vm = models.get(g().model());
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
        // Y — окно обвеса (закрывает его само окно; пока клавиша держится после закрытия — не открываем снова)
        boolean y = physical(mc, refitKey);
        if (y && !refitWas && input) openRefit(mc);
        refitWas = y;
        boolean z = input && physical(mc, zoomKey);
        if (z && !zoomWas && ads > 0.5f) nextZoom();
        zoomWas = z;
    }

    // ---------------------------------------------------------------- обвес

    /** Каталог обвесов с сервера (обновится сам при смене файла). */
    private void watchCatalog() {
        if (catWatched) return;
        catWatched = true;
        ctx.onFile(ATT_JSON, bytes -> {
            if (bytes == null) return;
            try {
                cat = Att.Catalog.parse(bytes);
                ctx.logger().info("guns: каталог обвесов — {} обвесов, {} планок, оружия {}", cat.defs.size(),
                        cat.adapters.size(), cat.guns.keySet());
            } catch (RuntimeException e) {
                ctx.logger().warn("guns: каталог обвесов не разобран: {}", e.toString());
            }
        });
    }

    /** Модель обвеса по пути (грузится с сервера при первом запросе; пока нет — null). */
    AttModel attModel(String path) {
        if (path == null || path.isEmpty()) return null;
        if (attWatched.add(path)) {
            ctx.onFile(path, bytes -> {
                AttModel old = attModels.remove(path);
                if (old != null) old.close();
                if (bytes == null) return;
                Att.Def def = null;
                for (Att.Def d : cat.defs.values()) {
                    if (path.equals(d.model())) {
                        def = d;
                        break;
                    }
                }
                AttModel am = AttModel.load(path, bytes, def, ctx.logger());
                if (am != null) attModels.put(path, am);
            });
        }
        return attModels.get(path);
    }

    /** Иконка обвеса (текстура ядра из файлов сервера). */
    Identifier icon(Att.Def d) {
        String f = d == null ? "" : d.iconFile();
        if (f.isEmpty()) return null;
        return iconTex.computeIfAbsent(f, ctx::texture);
    }

    /** Обвес своего оружия в руке (null — сервер ещё не прислал). */
    Att.Gear ownGear() {
        Proto.Loadout lo = ownLoadout;
        return lo == null || held == null || !lo.gun().equals(held.id()) ? null : lo.gear();
    }

    Proto.Stats ownStats() {
        Proto.Loadout lo = ownLoadout;
        return lo == null || held == null || !lo.gun().equals(held.id()) ? null : lo.stats();
    }

    /** Оружие в руке с характеристиками обвеса (до LOADOUT — как в CONFIG). */
    Proto.Spec g() {
        if (held == null) return null;
        Proto.Loadout lo = ownLoadout;
        if (lo == null || lo.stats() == null || !lo.gun().equals(held.id())) return held;
        if (eff == null || effFrom != lo || effBase != held) {
            eff = Proto.tuned(held, lo.stats());
            effFrom = lo;
            effBase = held;
        }
        return eff;
    }

    Proto.Spec heldBase() {
        return held;
    }

    /** Прицел-обвес на своём оружии (null — нет или прицел часть модели). */
    private Att.Def scopeDef() {
        Att.Gear gr = preview != null ? preview : ownGear();
        return gr == null ? null : cat.def(gr.ids[Att.SCOPE]);
    }

    /** Приближение в прицеле: кратность прицела-обвеса (не больше 10 — столько даёт угол обзора игры), иначе оружия. */
    private float effZoom() {
        Att.Def d = scopeDef();
        Att.Gear gr = ownGear();
        if (d != null && d.zoom().length > 0) return Math.max(1, Math.min(10, d.zoomAt(gr == null ? 0 : gr.zoom)));
        return g() == null ? 1 : g().zoom();
    }

    /** G в прицеле — следующая кратность (у прицелов с несколькими). */
    private void nextZoom() {
        Att.Gear gr = ownGear();
        Att.Def d = scopeDef();
        if (gr == null || d == null || d.zoom().length < 2) return;
        gr.zoom = Math.floorMod(gr.zoom + 1, d.zoom().length);
        ctx.send(Proto.zoom(gr.zoom));
        Sfx.play(d.soundInstall(), 0.4f * cfg.volume, 1.3f);
        setHint("КРАТНОСТЬ " + fmt(Math.min(10, d.zoomAt(gr.zoom))) + "x");
    }

    static String fmt(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-3 ? String.valueOf((long) Math.rint(v))
                : String.format(java.util.Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void openRefit(Minecraft mc) {
        if (held == null || mc.screen != null || mc.player == null) return;
        if (reloading() || drawing()) {
            setHint(reloading() ? "ИДЁТ ПЕРЕЗАРЯДКА" : "ДОСТАЮ");
            return;
        }
        if (cat.guns.isEmpty()) {
            setHint("НЕТ КАТАЛОГА ОБВЕСА");
            return;
        }
        if (adsSent) {
            adsSent = false;
            ctx.send(Proto.ads(false));
        }
        refitView = -1;
        refitPrevView = -1;
        mc.setScreen(new RefitScreen(this));
    }

    // ---- для окна обвеса

    MonoFont font() {
        return font;
    }

    InputConstants.Key refitKey() {
        return refitKey;
    }

    void send(byte[] msg) {
        ctx.send(msg);
    }

    void expectSound(String id) {
        pendingSound = id == null ? "" : id;
    }

    /** «Камера» окна обвеса — к слоту slot (−1 — общий вид), плавно за 0,25 с. */
    void refitLook(int slot) {
        if (slot == refitView) return;
        refitPrevView = refitView;
        refitView = slot;
        refitViewNs = System.nanoTime();
    }

    boolean refitMoving() {
        return System.nanoTime() - refitViewNs < 250_000_000L;
    }

    int fireMode() {
        return mode;
    }

    void switchMode() {
        toggleMode();
    }

    int ammoNow() {
        return ammo;
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
            var pl = Minecraft.getInstance().player;
            Vec3 eo = pl == null ? new Vec3(0, 1.62, 0) : pl.getEyePosition().subtract(pl.position());
            ctx.send(Proto.fire(seq, part, (float) eo.x, (float) eo.y, (float) eo.z));
            n += part.size();
            if (!pending.isEmpty()) seq++;
        }
        inflight.addLast(new int[] {seq, n});
        while (inflight.size() > 64) inflight.pollFirst();
    }

    private void requestReload() {
        if (held == null || reloading() || drawing()) return;
        if (ammo >= g().magazine() + 1) return;
        if (gotState && srvReserve == 0) {
            setHint("НЕТ ПАТРОНОВ");
            Sfx.play(g().sound("dry_fire"), 0.8f * cfg.volume, 1f);
            return;
        }
        ctx.send(Proto.reload());
        Proto.Extra ex = extra();
        if (ex.shell() != null) {                                  // по одному патрону: вступление, циклы, конец
            shellEmpty = ammo <= 0;
            shellLoops = ex.shellLoops(ammo, g().magazine() + 1, gotState ? srvReserve : -1);
            startReloadLocal(3, ex.shellTotal(shellEmpty, shellLoops));
            return;
        }
        int kind = ammo > 0 ? 1 : 2;
        startReloadLocal(kind, kind == 1 ? g().reloadTactical() : g().reloadEmpty());
    }

    /** Дробь, свой прицел, перезарядка по одному патрону у оружия в руке. */
    private Proto.Extra extra() {
        Proto.Extra e = held == null ? null : extras.get(g().id());
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
        ViewModel vm = models.get(g().model());
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
        ViewModel vm = models.get(g().model());
        if (vm != null) {
            // с удлинённым магазином — своя анимация TACZ (reload_tactical_xmag / reload_empty_xmag), если есть
            String anim = kind == 1 ? "reload_tactical" : "reload_empty";
            Att.Gear gr = ownGear();
            if (gr != null && gr.xmagLevel(cat) > 0 && vm.has(anim + "_xmag")) anim += "_xmag";
            vm.play(anim);
        }
        // звук на всю перезарядку (M16A1: в анимации TACZ своих звуков нет)
        Sfx.play(g().sound(kind == 1 ? "reload_tactical" : "reload_empty"), 0.8f * cfg.volume, 1f);
    }

    private void toggleMode() {
        if (held == null || !(g().auto() && g().semi())) return;
        mode = mode == 0 ? 1 : 0;
        modeSetNs = System.nanoTime();
        ctx.send(Proto.fireMode(mode));
        Sfx.play(g().sound("fire_select"), 0.8f * cfg.volume, 1f);
        ViewModel vm = models.get(g().model());
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
        return System.nanoTime() < drawUntilNs || (srvFlags & Proto.FLAG_DRAWING) != 0 && srvGun.equals(held == null ? "" : g().id());
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
        float z = held == null || !fp ? 1 : 1 + (effZoom() - 1) * AdsBlend.easeInOutCubic(ads);
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
        Att.Def sd = scopeDef();
        Att.Gear sg = preview != null ? preview : ownGear();
        float sightFov = sd == null ? 0 : sd.fovAt(sg == null ? 0 : sg.zoom);
        double view = cfg.viewmodelFov == GunsConfig.FOV_MODEL ? vm.viewFov(ads, sightFov) : cfg.viewmodelFov;
        if (view <= 0 && cfg.viewmodelFov == GunsConfig.FOV_MODEL) view = 70;   // в модели угла нет — как рука игры
        double world = mc.options.fov().get() * net.minecraft.util.Mth.lerp(pt, fovModO, fovMod);
        pxPerTanK = (float) (1 / Math.tan(Math.toRadians(Math.min(179, world)) / 2));
        if (view <= 0) return 1;
        var fluid = mc.gameRenderer.getMainCamera().getFluidInCamera();
        if (fluid == net.minecraft.world.level.material.FogType.WATER
                || fluid == net.minecraft.world.level.material.FogType.LAVA) {
            double k = net.minecraft.util.Mth.lerp(mc.options.fovEffectScale().get(), 1.0, 0.85714287);
            world *= k;
            view *= k;
        }
        float k = ViewModel.fovScale(world, view);
        pxPerTanK = (float) (k / Math.tan(Math.toRadians(Math.min(179, world)) / 2));
        return k;
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
        float rate = dt / Math.max(0.05f, g().aimTime());
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
        long interval = (long) (g().intervalMs() * 1e6);
        if (down && edge && reloading() && reloadKind == 3 && ammo > 0) {
            reloadStartNs = -1;                                    // по одному патрону: выстрел прерывает перезарядку
        }
        shellAnim();
        if (down && !reloading() && !drawing()) {
            if (ammo <= 0) {
                if (edge) {
                    Sfx.play(g().sound("dry_fire"), 0.8f * cfg.volume, 1f);
                    setHint(keyName(reloadKey) + " — ПЕРЕЗАРЯДКА");
                }
            } else if (mode == 1 || !g().auto() || kind().boltTime() > 0) {
                // одиночный — по нажатию; очередь с помпой/затвором (M1897) — пока зажата, после каждого передёргивания
                boolean single = mode == 1 || !g().auto();
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
            ViewModel bvm = models.get(g().model());
            if (bvm != null && !reloading()) bvm.play("bolt");
        }
        // отдача камеры: изменение сплайнов с прошлого кадра
        double dp = recoilPitch.delta(now / 1_000_000) * cfg.recoil;
        double dy = recoilYaw.delta(now / 1_000_000) * cfg.recoil;
        if (dp != 0 || dy != 0) kick(p, dy, dp);
    }

    /** Вид оружия в руке (нет данных — винтовка без затвора и оптики). */
    private Proto.Kind kind() {
        Proto.Kind k = held == null ? null : kinds.get(g().id());
        return k == null ? Proto.Kind.RIFLE : k;
    }

    /**
     * Оптика: насколько закрыт экран (0 — не в прицеле, 1 — чёрный экран и круг прицела). Только от первого лица и у
     * оружия с прицелом; нарастает в последней трети сведения оружия к глазу.
     */
    private float scopeCover() {
        if (held == null || !Minecraft.getInstance().options.getCameraType().isFirstPerson()) return 0;
        Att.Def d = scopeDef();
        boolean scope = d != null ? (curOptic != null ? curOptic.scope() : d.scope() && !d.sight()) : kind().scope();
        if (!scope) return 0;
        return Math.max(0, Math.min(1, (ads - 0.7f) / 0.28f));
    }

    /** В оптике мышь медленнее: делённая на текущее приближение (как подзорная труба игры) × scope_sensitivity. */
    private boolean mouse(double yawDeg, double pitchDeg) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.screen != null || scopeCover() <= 0 || vacuum()) return false;
        float z = 1 + (effZoom() - 1) * AdsBlend.easeInOutCubic(ads);
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
        Proto.Spec g = g();
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
            // от ствола: от первого лица — где ствол модели в руке на экране (в оптике — от глаз), в F5 — у руки
            boolean fp = mc.options.getCameraType().isFirstPerson();
            Vec3 eye = p.getEyePosition();
            Vec3 o = fp ? (muzzleRel == null ? null : eye.add(muzzleRel)) : handMuzzle(p, eye, dir);
            int pellets = extra().pellets();                       // дробь — трассер на каждую дробину
            Proto.Tracer tk = tracerKinds.getOrDefault(g.id(), Proto.Tracer.BULLET);
            for (int k = 0; k < pellets; k++) {
                Vec3 d = dir.add(tri(s), tri(s), tri(s)).normalize();
                fx.tracer(eye, o, d.scale(g.speed()), vac ? 3 : g.life(), g.tracerColor(), true, tk.plasma(), tk.length(), p);
            }
            if (!fp) {
                fx.flash(o, tk.plasma() ? 0.45f : 0.55f, tk.plasma(), g.tracerColor());
            }
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
        float dtRefit = lastFrameNs == 0 ? 0 : Math.min(0.1f, (now - lastFrameNs) / 1e9f);
        frameUpdate(mc, p, now);
        Quaternionf q = frame.cameraState().orientation;
        Matrix4f pose = frame.pose().last().pose();
        // эффекты — в системе камеры (повёрнутые обратно её поворотом) и той же матрицей, что оружие в руке
        fx.render(frame.buffers(), new Matrix4f(pose).rotate(q), frame.camera(), q, flashTex);
        otherLasers(mc, frame.partialTick());

        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        ViewModel vm = held == null ? null : models.get(g().model());
        boolean show = vm != null && firstPerson;
        // окно обвеса: «камера» плавно к refit_view (0,25 с), как у TACZ
        boolean refitOpen = mc.screen instanceof RefitScreen;
        refitW = refitOpen ? Math.min(1, refitW + dtRefit / 0.25f) : Math.max(0, refitW - dtRefit / 0.25f);
        if (!refitOpen) preview = null;
        Att.Gear gear = preview != null ? preview : ownGear();
        curOptic = null;
        if (show != handHidden) {
            ctx.hideHand(show);
            handHidden = show;
        }
        if (held != null && firstPerson) System.getProperties().put(HIDE_CROSSHAIR, Boolean.TRUE);
        else System.getProperties().remove(HIDE_CROSSHAIR);
        // без каталога (ещё не пришёл) — как в файле модели: иначе видны все переходники сразу
        if (vm != null) vm.applyGear(cat, cat.defs.isEmpty() ? null : gear, kind().scope());
        // прицел-обвес: модель, глаз в прицеле (кратность выбирает свой scope_view), вид через окуляр
        Att.Def sd = gear == null ? null : cat.def(gear.ids[Att.SCOPE]);
        AttModel sm = sd == null ? null : attModel(sd.model());
        Att.Adapter riser = gear == null || gear.adapter.isEmpty() ? null : cat.adapters.get(gear.adapter);
        int view = sd == null ? 1 : sd.viewAt(gear.zoom);
        if (sm != null) curOptic = sm.optic(view);
        boolean scoped = scopeCover() >= 1;                       // экран закрыт оптикой — оружие не видно
        if (!show || scoped) {
            muzzleRel = null;
            if (scoped && vm != null) vm.update();
            fx.renderBeams(frame.buffers(), new Matrix4f(pose).rotate(q), frame.camera(), q);
            return;
        }
        // покачивание за поворотом взгляда (инерция оружия), в прицеле — слабее; в окне обвеса — нет
        float yaw = p.getViewYRot(frame.partialTick()), pitch = p.getViewXRot(frame.partialTick());
        if (!Float.isNaN(lastYaw)) {
            float dyaw = net.minecraft.util.Mth.wrapDegrees(yaw - lastYaw), dpitch = pitch - lastPitch;
            float k = (1 - 0.75f * ads) * (1 - refitW);
            swayX += (Math.max(-6, Math.min(6, dyaw)) * 0.35f * k - swayX) * 0.25f;
            swayY += (Math.max(-6, Math.min(6, dpitch)) * 0.35f * k - swayY) * 0.25f;
        }
        lastYaw = yaw;
        lastPitch = pitch;

        vm.safety(mode == 0 && g().auto() ? "static_auto" : "static_semi");
        vm.update();
        float[] riserOff = riser == null ? null : riser.mountOffset();
        Matrix4f sightEye = null;
        if (sm != null) {
            Matrix4f mt = vm.mount(Att.SCOPE, riserOff);
            Matrix4f e = sm.eye(view);
            if (mt != null && e != null) sightEye = mt.mul(e);
        }
        Matrix4f refitInv = null;
        if (refitW > 0) {
            float t = AdsBlend.easeOutCubic(AdsBlend.clamp01((now - refitViewNs) / 0.25e9f));
            refitInv = AdsBlend.blend(vm.refitEye(refitPrevView), vm.refitEye(refitView), t);
        }
        Matrix4f m2c = vm.modelToCamera(cfg.viewmodelScale, ads, sightEye, sd == null ? 0 : sd.heightAdjust(), refitInv, refitW);
        Matrix4f sway = new Matrix4f().rotateY((float) Math.toRadians(swayX)).rotateX((float) Math.toRadians(swayY));
        m2c = sway.mul(m2c);
        Matrix4f camToBuf = new Matrix4f(pose).rotate(q);
        // свой угол обзора оружия (как у TACZ — model_fov оружия при любой настройке): x, y камеры ×k
        Matrix4f fov = ViewModel.fovMatrix(viewFovScale(mc, frame.partialTick(), vm));
        int light = light(mc, frame.camera());
        ViewModel.Mounted mounted = null;
        if (gear != null) {
            AttModel[] ms = new AttModel[Att.SLOTS.length];
            for (int s = 0; s < ms.length; s++) {
                Att.Def d = cat.def(gear.ids[s]);
                if (d != null && !d.model().isEmpty() && vm.hasPos(s)) ms[s] = attModel(d.model());
            }
            mounted = new ViewModel.Mounted(ms, riser == null ? null : attModel(riser.model()), riserOff, ads > 0.7f);
        }
        vm.paint(gear == null ? 0 : gear.paint);                    // своя окраска модели (plasma_rifle — чёрный металл)
        // в окне обвеса рук не видно — только оружие и обвес
        vm.render(frame.buffers(), camToBuf, fov, m2c, light, ammo, cfg.arms && !refitOpen, mounted);
        if (mounted != null && cfg.laser) ownLasers(vm, gear, mounted, fov, m2c, q, frame.camera());
        Att.Def md = gear == null ? null : cat.def(gear.ids[Att.MUZZLE]);
        Vector3f mz = vm.muzzle(m2c, md == null ? 0 : md.flashOffset());
        if (mz != null) {
            mz = fov.transformPosition(mz);                        // где ствол виден на экране
            Vector3f w = new Vector3f(mz).rotate(q);
            muzzleRel = new Vec3(w.x, w.y, w.z);
            Identifier ft = flashTexs.getOrDefault(g().flash(), flashTex);    // своя вспышка оружия (у плазмы — голубая)
            if (flashNs > 0 && now - flashNs < 55_000_000L && ft != null && cfg.tracers) {
                Proto.Stats st = ownStats();
                float scale = st != null ? st.flashScale() : kind().flashScale();
                float size = cfg.viewmodelScale * 13f / 16f * (vacuum() ? 0.6f : 1f) * scale;
                vm.renderFlash(frame.buffers(), camToBuf, mz, ft, size * fov.m00(), flashSpin);  // в плоскости экрана
            }
        }
        fx.renderBeams(frame.buffers(), camToBuf, frame.camera(), q);
    }

    /** Свои лучи ЛЦУ: из костей laser_beam обвесов (ЛЦУ, рукоять с ЛЦУ) — туда, куда смотрит кость. */
    private void ownLasers(ViewModel vm, Att.Gear gear, ViewModel.Mounted mounted, Matrix4f fov, Matrix4f m2c,
                           Quaternionf q, Vec3 cam) {
        Vector3f at = new Vector3f(), dir = new Vector3f();
        for (int s = 0; s < Att.SLOTS.length; s++) {
            AttModel am = mounted.models()[s];
            int color = gear.laserColor(cat, s);
            if (am == null || color < 0 || am.beams.isEmpty()) continue;
            Matrix4f mt = vm.mount(s, null);
            if (mt == null) continue;
            Matrix4f full = new Matrix4f(fov).mul(m2c).mul(mt);
            Att.Def d = cat.def(gear.ids[s]);
            for (BbModel.Bone b : am.beams) {
                am.beam(b, at, dir);
                Vector3f p0 = full.transformPosition(new Vector3f(at));
                Vector3f d0 = full.transformDirection(new Vector3f(dir)).normalize();
                p0.rotate(q);
                d0.rotate(q);
                fx.laser(new Vec3(cam.x + p0.x, cam.y + p0.y, cam.z + p0.z), new Vec3(d0.x, d0.y, d0.z),
                        d.laser().length(), color, Minecraft.getInstance().player);
            }
        }
    }

    /** Лучи ЛЦУ других игроков (и свой в F5): от ствола (как у трассера) по взгляду. */
    private void otherLasers(Minecraft mc, float pt) {
        if (!cfg.laser || mc.level == null || gears.isEmpty()) return;
        boolean fp = mc.options.getCameraType().isFirstPerson();
        for (var e : gears.entrySet()) {
            Att.Gear gr = e.getValue();
            // ЛЦУ (слева), второй ЛЦУ (справа); рукоять с ЛЦУ — если других нет
            int[] colors = new int[3];
            float[] lens = new float[3];
            int[] slots = {Att.LASER, Att.LASER2, Att.GRIP};
            int n = 0;
            for (int i = 0; i < slots.length; i++) {
                int c = gr.laserColor(cat, slots[i]);
                colors[i] = c;
                if (c >= 0) {
                    lens[i] = cat.def(gr.ids[slots[i]]).laser().length();
                    if (i < 2) n++;
                }
            }
            if (n > 0) colors[2] = -1;
            if (colors[0] < 0 && colors[1] < 0 && colors[2] < 0) continue;
            Entity en = mc.level.getEntity(e.getKey());
            if (en == null || en.isRemoved()) continue;
            if (en == mc.player && fp) continue;                   // свой от первого лица — из модели в руке
            if (en.distanceToSqr(mc.player) > 96 * 96) continue;
            Vec3 eye = en.getEyePosition(pt);
            Vec3 look = en.getViewVector(pt).normalize();
            Vec3 from = handMuzzle(en, eye, look);
            Vec3 right = look.cross(new Vec3(0, 1, 0));
            right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
            for (int i = 0; i < slots.length; i++) {
                if (colors[i] < 0) continue;
                // два ЛЦУ — по бокам цевья (≈ 0,05 блока влево и вправо от ствола)
                Vec3 at = n == 2 && i < 2 ? from.add(right.scale(i == 0 ? -0.05 : 0.05)) : from;
                fx.laser(at, look, lens[i], colors[i], en);
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
        ViewModel vm = models.get(g().model());
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
        if (mc.screen instanceof RefitScreen) return;              // окно обвеса рисует своё
        float cover = scopeCover();
        boolean att = scopeDef() != null;
        if (cover > 0) {                                           // оптика: и при F1 — это вид в прицел, не HUD
            ViewModel vm = models.get(g().model());
            Reticle r = att ? (curOptic == null ? null : curOptic.reticle()) : vm == null ? null : vm.reticle;
            ScopeOverlay.render(g, r, cover, cfg.scopeSize);
        } else if (att && curOptic != null && !curOptic.scope() && cfg.sightReticle && ads > 0.75f
                && mc.options.getCameraType().isFirstPerson()) {
            // коллиматор: сетка (красная точка и т.п.) поверх мира — на оси прицела, т.е. в центре экрана
            ScopeOverlay.sight(g, curOptic.reticle(), pxPerTanK, (ads - 0.75f) / 0.25f);
        }
        if (mc.options.hideGui) return;
        long now = System.nanoTime();
        GunHud.View v = view;
        v.name = g().name();
        v.ammo = ammo;
        v.magazine = g().magazine();
        v.reserve = gotState ? srvReserve : -1;
        v.auto = mode == 0 && g().auto();
        v.bothModes = g().auto() && g().semi();
        v.reload = reloading() ? Math.min(1, (now - reloadStartNs) / 1e9f / reloadLen) : -1;
        v.drawing = drawing();
        v.vacuum = vacuum();
        v.ads = ads;
        boolean moving = mc.player.getDeltaMovement().horizontalDistanceSqr() > 0.003;
        v.spreadDeg = g().spreadHip() * (moving ? 1.15f : 1f);
        v.bloom = bloom;
        v.hitKind = hitKind;
        v.hitLeft = hitKind == 0 ? 0 : Math.max(0, 1 - (now - hitNs) / 0.35e9f);
        v.silhouette = hudTex.get(g().hud());
        v.crosshair = mc.options.getCameraType().isFirstPerson() && cover <= 0 && extra().crosshair();
        v.hint = !hint.isEmpty() && now - hintNs < 2_000_000_000L ? hint : ammo <= 0 ? "ПУСТО" : "";
        hud.render(g, v, cfg.hud);
    }
}
