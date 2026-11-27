package ink.hoka.odyssey.guns;

import ink.hoka.odyssey.api.OdysseyModule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.collision.BoundingBox;
import net.minestom.server.collision.Shape;
import net.minestom.server.collision.ShapeImpl;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.attribute.AttributeModifier;
import net.minestom.server.entity.attribute.AttributeOperation;
import net.minestom.server.entity.damage.Damage;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.event.item.PlayerBeginItemUseEvent;
import net.minestom.server.event.item.PlayerCancelItemUseEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerHandAnimationEvent;
import net.minestom.server.event.player.PlayerSwapItemEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.timer.Task;

/**
 * Огнестрел (АКМ из TACZ). Стреляет сервер: клиентский модуль guns присылает выстрелы (FIRE, раз в тик пачкой) с
 * направлением взгляда; сервер проверяет ({@link FireValidator}), разбрасывает ({@link Spread}), ведёт пулю по тикам
 * ({@link Ballistics}), наносит урон и рассылает эффекты ({@link Effects}). Протокол — {@link Proto}.
 * <p>
 * Прицел — ванильное «использование предмета» (ПКМ, см. {@link GunItems}): сервер узнаёт о нём по событиям начала и
 * конца, приближает обзор через «скорость ходьбы» способностей игрока (единственный рычаг FOV с сервера). Отдача в
 * вакууме — толчок модуля gravity. Патроны лежат в памяти и пишутся в предмет при смене слота, раз в полсекунды после
 * стрельбы, в конце перезарядки, при выходе и выключении модуля. Всё своё убирается в onDisable.
 */
public final class GunsModule extends OdysseyModule {
    private static final int INVENTORY = 36;
    /** Модификатор скорости в прицеле (у игроков с клиентским модулем). */
    private static final net.kyori.adventure.key.Key AIM_SLOW = net.kyori.adventure.key.Key.key("hoka", "gun_aim");

    private final Map<String, GunDef> guns = new LinkedHashMap<>();
    private final Map<UUID, ShooterState> states = new ConcurrentHashMap<>();
    /** Игроки, у которых загружен клиентский модуль guns (прислали HELLO). */
    private final Set<UUID> modded = ConcurrentHashMap.newKeySet();
    private final List<Live> bullets = new ArrayList<>();
    /** Что последним разослали «за спиной» у каждого игрока (id оружия, «» — ничего). */
    private final Map<UUID, String> backSent = new ConcurrentHashMap<>();

    private Links links;
    private GunItems items;
    private Vacuum vacuum;
    private Effects effects;
    private Assets assets;
    private Task task;
    private long ticks;

    private boolean pvp;
    private boolean backGun;
    private boolean damageMobs;
    private double maxAim;
    private double burst;
    private double vacImpulse;
    private double vacTargetImpulse;
    private String vacSound;
    private double vacLife;
    private boolean particles;
    private double soundRange;
    private boolean logHits;

    /** Пуля в полёте и чья она. */
    private record Live(Ballistics.Bullet b, Player shooter, GunDef gun, Instance inst) {}

    @Override
    protected void onEnable() {
        readConfig();
        links = new Links(this, logger());
        items = new GunItems(links);
        try {
            vacuum = new Vacuum(this);
        } catch (LinkageError e) {
            vacuum = null;
        }
        effects = new Effects(this);
        assets = new Assets(dataFolder());
        if (links.resolve(this::onClientMessage, this::onClientReady)) {
            publishFiles();
            adoptReady();
        }
        events().addListener(PlayerBeginItemUseEvent.class, e -> {
            if (e.getHand() == PlayerHand.MAIN && GunItems.gunId(e.getItemStack()) != null) {
                beginAim(e.getPlayer());
            }
        });
        events().addListener(PlayerCancelItemUseEvent.class, e -> endAim(e.getPlayer()));
        events().addListener(PlayerDisconnectEvent.class, e -> {
            Player p = e.getPlayer();
            ShooterState st = states.remove(p.getUuid());
            if (st != null && st.gun != null) {
                flushAmmo(p, st);
            }
            modded.remove(p.getUuid());
            backSent.remove(p.getUuid());
        });
        // без клиентского модуля: ЛКМ (взмах руки) — одиночный выстрел, F (смена рук) — перезарядка
        events().addListener(PlayerHandAnimationEvent.class, e -> {
            Player p = e.getPlayer();
            if (e.getHand() == PlayerHand.MAIN && !modded(p) && GunItems.gunId(p.getItemInMainHand()) != null) {
                syncHeld(p);
                ShooterState st = state(p);
                Vec d = p.getPosition().direction();
                fireOne(p, st, d.x(), d.y(), d.z(), 0, System.currentTimeMillis());
            }
        });
        // с оружием в руке блоки не ломаются (клиентский модуль забирает ЛКМ сам; это — для игроков без него и
        // для чужих клиентов, которые прислали «сломал» в обход)
        events().addListener(PlayerBlockBreakEvent.class, e -> {
            if (GunItems.gunId(e.getPlayer().getItemInMainHand()) != null) {
                e.setCancelled(true);
            }
        });
        events().addListener(PlayerSwapItemEvent.class, e -> {
            Player p = e.getPlayer();
            if (GunItems.gunId(p.getItemInMainHand()) != null) {
                e.setCancelled(true);
                syncHeld(p);
                startReload(p, state(p));
            }
        });
        registerCommand(new HoGunCommand(this));
        task = scheduler().repeat(this::tick, 1, 1);
        logger().info("guns: включён (оружие: {}; clientmods {}, hoitems {}, hosounds {}, gravity {})",
                String.join(", ", guns.keySet()), yes(links.hasClient()), yes(links.hasItems()), yes(links.hasSounds()),
                yes(vacuum != null));
    }

    private static String yes(boolean b) {
        return b ? "есть" : "нет";
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            ShooterState st = states.get(p.getUuid());
            if (st == null) {
                continue;
            }
            if (st.gun != null) {
                flushAmmo(p, st);
            }
            endAim(p);
        }
        bullets.clear();
        states.clear();
        modded.clear();
        if (links != null) {
            links.clearFiles();
            links.publish();
            links.close();
        }
    }

    private void readConfig() {
        var c = config();
        pvp = c.getBoolean("pvp", true);
        damageMobs = c.getBoolean("damage-mobs", true);
        maxAim = GunDef.clamp(c.getDouble("max-aim-deviation", 25), 1, 180);
        burst = GunDef.clamp(c.getDouble("rate-burst", 3), 1, 20);
        vacImpulse = Math.max(0, c.getDouble("vacuum.impulse", 0.25));
        vacTargetImpulse = Math.max(0, c.getDouble("vacuum.target-impulse", 0.15));
        vacSound = c.getString("vacuum.sound", "self");
        vacLife = GunDef.clamp(c.getDouble("vacuum.bullet-life", 3.0), 0.05, 30);
        particles = c.getBoolean("particles", true);
        soundRange = GunDef.clamp(c.getDouble("sound-range", 96), 4, 512);
        logHits = c.getBoolean("log-hits", false);
        backGun = c.getBoolean("back-gun", true);
        guns.clear();
        GunDef.Cfg cfg = c::get;
        for (String id : c.keys("guns")) {
            try {
                guns.put(id, GunDef.read(id, cfg));
            } catch (RuntimeException e) {
                logger().warn("guns: оружие {} не прочитано: {}", id, e.toString());
            }
        }
    }

    /**
     * Перечитать config.yml (после /hogun install дописал новое оружие): оружие, файлы для мода, настройки игрокам с
     * клиентским модулем. Оружие в руках подхватывается заново при следующей проверке (патроны пишутся в предмет).
     */
    void reloadGuns() {
        config().reload();
        readConfig();
        if (links.hasClient()) {
            publishFiles();
            for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
                if (modded(p)) {
                    sendConfig(p);
                    state(p).sentKey = "";
                }
            }
        }
        logger().info("guns: config.yml перечитан, оружие: {}", String.join(", ", guns.keySet()));
    }

    // ---------------------------------------------------------------- для других классов модуля

    @SuppressWarnings({"unchecked", "rawtypes"})
    Object service(Class<?> type) {
        return services().get((Class) type).orElse(null);
    }

    Links links() {
        return links;
    }

    GunItems items() {
        return items;
    }

    Assets assets() {
        return assets;
    }

    Map<String, GunDef> guns() {
        return guns;
    }

    boolean modded(Player p) {
        return modded.contains(p.getUuid());
    }

    boolean particles() {
        return particles;
    }

    String vacuumSound() {
        return vacSound;
    }

    double soundRange() {
        return soundRange;
    }

    double tracerRange() {
        return 160;
    }

    ShooterState state(Player p) {
        return states.computeIfAbsent(p.getUuid(), k -> new ShooterState());
    }

    // ---------------------------------------------------------------- клиентский модуль

    /** Сообщение клиентского модуля (поток clientmods) — разобрать и перенести в тик. */
    private void onClientMessage(Player p, byte[] data) {
        Proto.Msg m = Proto.decode(data);
        if (p == null || m == null) {
            return;
        }
        scheduler().run(() -> {
            if (!p.isOnline()) {
                return;
            }
            switch (m.op()) {
                case Proto.C_HELLO -> {
                    modded.add(p.getUuid());
                    sendConfig(p);
                    state(p).sentKey = "";
                }
                case Proto.C_FIRE -> fire(p, m.fire());
                case Proto.C_RELOAD -> {
                    syncHeld(p);
                    startReload(p, state(p));
                }
                case Proto.C_ADS -> {
                    syncHeld(p);
                    ShooterState st = state(p);
                    if (m.mode() != 0 && st.gun != null && st.aimSince == 0) {
                        beginAim(p);
                    } else if (m.mode() == 0) {
                        endAim(p);
                    }
                }
                case Proto.C_FIREMODE -> {
                    ShooterState st = state(p);
                    if (st.gun != null && (m.mode() == 0 ? st.gun.auto() : st.gun.semi())) {
                        st.mode = m.mode() == 0 ? 0 : 1;
                    }
                }
                default -> {
                }
            }
        });
    }

    /**
     * Игроки, у которых клиентский модуль guns уже загружен (наш модуль перезагрузили — HELLO они второй раз не
     * пришлют): считать их «с модулем» и прислать настройки.
     */
    private void adoptReady() {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (links.ready(p) && modded.add(p.getUuid())) {
                sendConfig(p);
                state(p).sentKey = "";
            }
        }
    }

    private void onClientReady(Player p) {
        scheduler().run(() -> {
            if (p.isOnline() && modded(p)) {
                sendConfig(p);
            }
        });
    }

    private void sendConfig(Player p) {
        links.send(p, Proto.config(guns.values(), "self".equals(vacSound) ? 0.35f : "none".equals(vacSound) ? 0f : 1f,
                backGun));
    }

    /** Модели, силуэты и вспышки — моду через clientmods (у игроков с модом обновятся сразу). */
    private void publishFiles() {
        links.clearFiles();
        int n = 0;
        for (GunDef g : guns.values()) {
            for (String path : new String[] {g.model(), g.hud(), g.flash(), g.playerAnimation()}) {
                if (path == null || path.isBlank()) {
                    continue;
                }
                byte[] b = assets.read(path);
                if (b != null) {
                    links.putFile(path, b);
                    n++;
                } else {
                    logger().warn("guns: файл {} для {} не найден (ни в module/guns/files, ни в jar)", path, g.id());
                }
            }
        }
        links.publish();
        logger().info("guns: моду отдано файлов: {}", n);
    }

    // ---------------------------------------------------------------- тик

    private void tick() {
        ticks++;
        if (ticks % 100 == 0 && links.resolve(this::onClientMessage, this::onClientReady)) {
            publishFiles();                                   // clientmods перезагрузили
            adoptReady();
            for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
                if (modded(p)) {
                    sendConfig(p);
                }
            }
        }
        long now = System.currentTimeMillis();
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            ShooterState st = states.get(p.getUuid());
            boolean holding = GunItems.gunId(p.getItemInMainHand()) != null || items.bareGun(p.getItemInMainHand(), guns.values()) != null;
            if (st == null && !holding) {
                continue;
            }
            if (st != null && !holding) {
                relayPose(p, st, now);                        // убрал оружие — остальным «без позы»
            }
            if (st == null) {
                st = state(p);
            }
            Pos pos = p.getPosition();
            if (!Double.isNaN(st.lastX)) {
                double dx = pos.x() - st.lastX;
                double dz = pos.z() - st.lastZ;
                st.speed = Math.sqrt(dx * dx + dz * dz);
            }
            double mx = Double.isNaN(st.lastX) ? 0 : pos.x() - st.lastX;
            double my = Double.isNaN(st.lastX) ? 0 : pos.y() - st.lastY;
            double mz = Double.isNaN(st.lastX) ? 0 : pos.z() - st.lastZ;
            st.lastX = pos.x();
            st.lastY = pos.y();
            st.lastZ = pos.z();
            st.motion = new double[] {mx, my, mz};
            syncHeld(p);
            if (st.gun == null) {
                restoreFov(p, st);
                aimPose(p, st);
                relayPose(p, st, now);
                continue;
            }
            st.vacuum = vacuum != null && vacuum.inVacuum(p);
            if (st.aimSince > 0 && !modded(p) && !p.isUsingItem()) {
                endAim(p);
            }
            reloadTick(p, st, now);
            boltTick(p, st, now);
            aimPose(p, st);
            if (st.pushX != 0 || st.pushY != 0 || st.pushZ != 0) {
                if (st.vacuum && vacuum != null) {
                    vacuum.push(p, st.pushX, st.pushY, st.pushZ);
                }
                st.pushX = st.pushY = st.pushZ = 0;
            }
            if (st.ammoDirty && ticks % 10 == 0) {
                flushAmmo(p, st);
            }
            sendState(p, st, now);
            relayPose(p, st, now);
        }
        if (ticks % 40 == 0) {
            resendPoses(now);                                 // кто только что увидел стрелка (подошёл, зашёл)
        }
        relayBacks(ticks % 40 == 20);
        stepBullets();
    }

    /** Флаги позы стрелка: оружие в руке, прицел, перезарядка. */
    private static int poseFlags(ShooterState st) {
        if (st.gun == null) {
            return 0;
        }
        return Proto.POSE_HOLDING | (st.aimSince > 0 ? Proto.POSE_AIM : 0) | (st.reloadKind != 0 ? Proto.POSE_RELOAD : 0);
    }

    private byte[] poseMessage(Player p, ShooterState st, long now) {
        int flags = poseFlags(st);
        float len = 0;
        float since = 0;
        if ((flags & Proto.POSE_RELOAD) != 0) {
            GunDef.Reload r = st.reloadKind == 1 ? st.gun.tactical() : st.gun.empty();
            len = (float) r.total();
            since = Math.max(0, (now - st.reloadStart) / 1000f);
        }
        return Proto.pose(p.getEntityId(), flags, since, len);
    }

    /**
     * Разослать позу стрелка тем, у кого клиентский модуль (кроме него самого: свою позу клиент знает сам) — при
     * смене: взял/убрал оружие, прицел, перезарядка.
     */
    private void relayPose(Player p, ShooterState st, long now) {
        String key = poseFlags(st) + ":" + (st.reloadKind != 0 ? st.reloadStart : 0);
        if (key.equals(st.poseKey)) {
            return;
        }
        st.poseKey = key;
        byte[] msg = poseMessage(p, st, now);
        for (Player v : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (v != p && modded(v) && v.getInstance() == p.getInstance()) {
                links.send(v, msg);
            }
        }
    }

    /** Раз в 2 с — всем с модулем позы всех стрелков с оружием в руке (новые зрители, смена мира). */
    private void resendPoses(long now) {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            ShooterState st = states.get(p.getUuid());
            if (st == null || st.gun == null) {
                continue;
            }
            byte[] msg = poseMessage(p, st, now);
            for (Player v : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
                if (v != p && modded(v) && v.getInstance() == p.getInstance()) {
                    links.send(v, msg);
                }
            }
        }
    }

    /**
     * Оружие «за спиной»: первое длинное оружие (rifle / sniper) в быстрой панели (слоты 0–8) не в выбранном слоте, и
     * первый такой же пистолет — «в кобуре». Вторая рука не в счёт — оружие в ней и так видно в руке. null — ничего;
     * иначе id через запятую (длинное первым).
     */
    String backGunOf(Player p) {
        int held = p.getHeldSlot();
        PlayerInventory inv = p.getInventory();
        String longGun = null;
        String pistol = null;
        for (int i = 0; i < 9 && (longGun == null || pistol == null); i++) {
            if (i == held) {
                continue;
            }
            ItemStack s = inv.getItemStack(i);
            if (s == null || s.isAir()) {
                continue;
            }
            String id = GunItems.gunId(s);
            GunDef g = id != null ? guns.get(id) : items.bareGun(s, guns.values());
            if (g == null) {
                continue;
            }
            if (g.pistol()) {
                pistol = pistol == null ? g.id() : pistol;
            } else {
                longGun = longGun == null ? g.id() : longGun;
            }
        }
        if (longGun == null) {
            return pistol;
        }
        return pistol == null ? longGun : longGun + "," + pistol;
    }

    /**
     * Разослать «оружие за спиной» тем, кто игрока видит, с клиентским модулем (и самому игроку — свой клиент
     * рисует его в F5): при смене, а с refresh — заново (кто подошёл, зашёл, сменил мир).
     */
    private void relayBacks(boolean refresh) {
        var online = MinecraftServer.getConnectionManager().getOnlinePlayers();
        if (refresh && backSent.size() > online.size()) {
            Set<UUID> ids = new HashSet<>();
            for (Player p : online) {
                ids.add(p.getUuid());
            }
            backSent.keySet().retainAll(ids);              // вышедшие (гонка с отключением)
        }
        for (Player p : online) {
            String gun = backGun ? backGunOf(p) : null;
            String key = gun == null ? "" : gun;
            String was = backSent.put(p.getUuid(), key);
            boolean changed = !key.equals(was == null ? "" : was);
            if (!changed && !(refresh && !key.isEmpty())) {
                continue;
            }
            byte[] msg = Proto.back(p.getEntityId(), key);
            if (modded(p)) {
                links.send(p, msg);
            }
            for (Player v : p.getViewers()) {
                if (modded(v)) {
                    links.send(v, msg);
                }
            }
        }
    }

    /** Что в руке: взяли оружие — «достаёт», убрали — патроны в предмет. */
    void syncHeld(Player p) {
        ShooterState st = state(p);
        int slot = p.getHeldSlot();
        ItemStack s = p.getItemInMainHand();
        GunDef bare = items.bareGun(s, guns.values());
        if (bare != null) {                                   // выдали /hoitem give — сделать настоящим оружием
            s = GunItems.decorate(s, bare, UUID.randomUUID().toString().substring(0, 8), bare.magazine());
            p.getInventory().setItemStack(slot, s);
        }
        String id = GunItems.gunId(s);
        GunDef g = id == null ? null : guns.get(id);
        String serial = g == null ? null : s.getTag(GunItems.SERIAL);
        if (g != null && serial == null) {
            serial = UUID.randomUUID().toString().substring(0, 8);
            s = s.withTag(GunItems.SERIAL, serial);
            p.getInventory().setItemStack(slot, s);
        }
        if (st.gun != null && (g == null || slot != st.slot || !Objects.equals(serial, st.serial) || st.gun != g)) {
            flushAmmo(p, st);
            endAim(p);
            st.unequip();
        }
        if (g != null && st.gun == null) {
            Integer ammo = s.getTag(GunItems.AMMO);
            ItemStack fresh = GunItems.decorate(s, g, serial, ammo == null ? g.magazine() : ammo);
            if (!fresh.equals(s)) {                           // предмет от старой версии модуля — поведение заново
                p.getInventory().setItemStack(slot, fresh);
            }
            st.equip(g, slot, serial, ammo == null ? g.magazine() : ammo, System.currentTimeMillis());
            st.ammoDirty = false;
            if (!modded(p)) {
                effects.others(p, g.sound("draw"), 0.6f);
            }
        }
    }

    /** Записать патроны в предмет (ищем по серийному номеру: сначала слот, потом весь инвентарь). */
    void flushAmmo(Player p, ShooterState st) {
        st.ammoDirty = false;
        if (st.serial == null) {
            return;
        }
        PlayerInventory inv = p.getInventory();
        int found = -1;
        if (st.slot >= 0 && st.slot < INVENTORY && st.serial.equals(inv.getItemStack(st.slot).getTag(GunItems.SERIAL))) {
            found = st.slot;
        } else {
            for (int i = 0; i < INVENTORY; i++) {
                if (st.serial.equals(inv.getItemStack(i).getTag(GunItems.SERIAL))) {
                    found = i;
                    break;
                }
            }
        }
        if (found >= 0) {
            ItemStack s = inv.getItemStack(found);
            Integer old = s.getTag(GunItems.AMMO);
            if (old == null || old != st.ammo) {
                inv.setItemStack(found, s.withTag(GunItems.AMMO, st.ammo));
            }
        }
    }

    // ---------------------------------------------------------------- прицел

    private void beginAim(Player p) {
        syncHeld(p);
        ShooterState st = state(p);
        if (st.gun == null) {
            return;
        }
        st.aimSince = System.currentTimeMillis();
        aimPose(p, st);
        double z = st.gun.zoom();
        if (modded(p) || z <= 1.001) {                       // с клиентским модулем приближает он сам, плавно
            return;
        }
        double speed = p.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
        if (speed <= 0) {
            return;
        }
        if (Float.isNaN(st.savedFov)) {
            st.savedFov = p.getFieldViewModifier();
        }
        // FOV клиента ×(скорость/walkingSpeed + 1)/2 — подбираем walkingSpeed так, чтобы вышло 1/zoom
        p.setFieldViewModifier((float) (speed / (2 / z - 1)));
    }

    private void endAim(Player p) {
        ShooterState st = states.get(p.getUuid());
        if (st == null) {
            return;
        }
        st.aimSince = 0;
        restoreFov(p, st);
        aimPose(p, st);
    }

    /**
     * Поза прицела, видная ванильному клиенту (ядро без крючка onModelParts — и свой F5, и чужие): игроку с модулем
     * в прицеле (не в перезарядке) ставим «использование» предмета основной рукой. Анимация использования у оружия —
     * BOW ({@link GunItems}): обе руки вперёд, оружие вдоль руки — стволом вперёд. С крючком поза TACZ модуля ставится
     * поверх (руки он задаёт сам). Без модуля «использование» настоящее (ПКМ) — его ведёт Minestom, не трогаем.
     * <p>
     * Замедление прицела у игрока с модулем: при позе — USE_EFFECTS предмета (клиент «использует» его и замедляет
     * сам, как без модуля), без позы (перезарядка с зажатой ПКМ) — атрибутом; вместе не бывают.
     */
    private void aimPose(Player p, ShooterState st) {
        boolean mod = modded(p);
        boolean pose = mod && st.gun != null && st.aimSince > 0 && st.reloadKind == 0;
        if (pose != st.aimPose) {
            st.aimPose = pose;
            p.refreshActiveHand(pose, false, false);
        }
        if (pose) {
            java.util.Set<java.util.UUID> now = new java.util.HashSet<>();
            for (Player v : p.getViewers()) {
                now.add(v.getUuid());
                if (st.aimPoseSeen.add(v.getUuid())) {
                    v.sendPacket(p.getMetadataPacket());
                }
            }
            st.aimPoseSeen.retainAll(now);
        } else {
            st.aimPoseSeen.clear();
        }
        boolean slow = mod && st.gun != null && st.aimSince > 0 && !pose;
        if (slow != st.aimSlow) {
            st.aimSlow = slow;
            var speed = p.getAttribute(Attribute.MOVEMENT_SPEED);
            speed.removeModifier(AIM_SLOW);
            if (slow) {
                speed.addModifier(new AttributeModifier(AIM_SLOW, st.gun.aimSpeed() - 1,
                        AttributeOperation.ADD_MULTIPLIED_TOTAL));
            }
        }
    }

    private static void restoreFov(Player p, ShooterState st) {
        if (!Float.isNaN(st.savedFov)) {
            p.setFieldViewModifier(st.savedFov);
            st.savedFov = Float.NaN;
        }
    }

    boolean aiming(Player p, ShooterState st, long now) {
        return st.gun != null && (modded(p) || p.isUsingItem()) && st.aimSince > 0
                && now - st.aimSince >= st.gun.aimTime() * 1000;
    }

    // ---------------------------------------------------------------- стрельба

    private void fire(Player p, Proto.Fire f) {
        syncHeld(p);
        ShooterState st = state(p);
        long now = System.currentTimeMillis();
        for (Proto.Shot s : f.shots()) {
            if (!fireOne(p, st, s.dx(), s.dy(), s.dz(), f.seq(), now)) {
                break;
            }
        }
        st.ackSeq = f.seq();
    }

    /** Один выстрел: проверка, патрон, пуля, эффекты. false — отклонён (остальные в пачке тоже). */
    private boolean fireOne(Player p, ShooterState st, double dx, double dy, double dz, int seq, long now) {
        Instance inst = p.getInstance();
        if (inst == null || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        Vec look = p.getPosition().direction();
        double ang = FireValidator.angle(dx, dy, dz, look.x(), look.y(), look.z());
        FireValidator.Result r = FireValidator.check(st, now, ang, maxAim, burst);
        if (r != FireValidator.Result.OK) {
            if (modded(p)) {
                links.send(p, Proto.reject(seq, r.code));
            } else if (r == FireValidator.Result.EMPTY) {
                links.play(p, st.gun.sound("dry_fire"), p.getPosition(), 1f, 1f);
            }
            return false;
        }
        GunDef g = st.gun;
        st.ammo--;
        st.ammoDirty = true;
        boolean vac = st.vacuum;
        Spread.Stance stance = new Spread.Stance(aiming(p, st, now), vac, p.isSneaking(),
                st.speed > 0.08 || (!vac && !p.isOnGround()));
        double[] d = Spread.apply(dx, dy, dz, Spread.inaccuracy(g, stance), ThreadLocalRandom.current());
        Pos pos = p.getPosition();
        double ex = pos.x();
        double ey = pos.y() + p.getEyeHeight();
        double ez = pos.z();
        double v = g.speed() / 20.0;
        double[] m = st.motion == null ? new double[3] : st.motion;
        double vx = d[0] * v + m[0];
        double vy = d[1] * v + (vac || !p.isOnGround() ? m[1] : 0);
        double vz = d[2] * v + m[2];
        double life = vac ? vacLife : g.life();
        Ballistics.Bullet b = new Ballistics.Bullet(ex, ey, ez, vx, vy, vz, (int) Math.max(1, Math.round(life * 20)),
                g.pierce(), vac ? 0 : g.gravity(), vac ? 0 : g.friction(), p);
        Live live = new Live(b, p, g, inst);
        effects.shot(p, g, ex, ey, ez, vx * 20, vy * 20, vz * 20, life, vac);
        st.boltAfterShot(now);
        if (g.boltTime() > 0 && st.ammo > 0 && !g.boltSounds3p().isEmpty()) {   // затвор — после промежутка по rpm
                                                                                // (последний патрон — без затвора)
            st.boltStart = now + Math.round(g.intervalMs());
            st.boltSoundIndex = 0;
        }
        if (step(live)) {                                     // первый отрезок — в том же тике
            bullets.add(live);
        }
        if (vac && vacImpulse > 0) {                          // отдача в вакууме: назад вдоль выстрела
            double k = vacImpulse / 20.0;
            st.pushX -= d[0] * k;
            st.pushY -= d[1] * k;
            st.pushZ -= d[2] * k;
        }
        if (st.ammo <= 0 && !modded(p)) {
            p.sendActionBar(Component.text("Магазин пуст — F: перезарядка", NamedTextColor.RED));
        }
        return true;
    }

    private void stepBullets() {
        for (Iterator<Live> it = bullets.iterator(); it.hasNext(); ) {
            Live l = it.next();
            if (!step(l)) {
                it.remove();
            }
        }
        if (bullets.size() > 4096) {                          // на всякий случай
            bullets.subList(0, bullets.size() - 4096).clear();
        }
    }

    private boolean step(Live l) {
        if (l.inst() == null || !l.inst().isRegistered()) {
            return false;
        }
        return l.b().step(world(l.inst()), new Ballistics.Listener() {
            @Override
            public void target(Ballistics.Target t, double x, double y, double z, double nx, double ny, double nz,
                               double fromStart, boolean head) {
                hitEntity(l, (LivingEntity) t.handle(), x, y, z, nx, ny, nz, fromStart, head);
            }

            @Override
            public void block(int bx, int by, int bz, double x, double y, double z, double nx, double ny, double nz) {
                Block block;
                try {
                    block = l.inst().getBlock(bx, by, bz, Block.Getter.Condition.TYPE);
                } catch (RuntimeException e) {
                    block = null;
                }
                effects.blockHit(l.inst(), block, x, y, z, nx, ny, nz);
            }
        });
    }

    private void hitEntity(Live l, LivingEntity target, double x, double y, double z, double nx, double ny, double nz,
                           double fromStart, boolean head) {
        GunDef g = l.gun();
        Player shooter = l.shooter();
        effects.entityHit(l.inst(), x, y, z, nx, ny, nz, head);
        boolean allowed = target instanceof Player ? pvp : damageMobs;
        if (!allowed || target.isDead()) {
            return;
        }
        double dmg = g.damageAt(fromStart) * (head ? g.headshot() : 1);
        float hpBefore = target.getHealth();
        boolean ok = target.damage(new Damage(DamageType.PLAYER_ATTACK, shooter, shooter, new Vec(x, y, z), (float) dmg));
        if (logHits) {
            logger().info("guns: попадание {} → {} {}: {} урона на {} блоках{}, здоровье {} → {}{}", shooter.getUsername(),
                    target instanceof Player tp ? tp.getUsername() : target.getEntityType().name(), target.getEntityId(),
                    String.format(java.util.Locale.ROOT, "%.1f", dmg), String.format(java.util.Locale.ROOT, "%.1f", fromStart),
                    head ? " (в голову)" : "", String.format(java.util.Locale.ROOT, "%.1f", hpBefore),
                    String.format(java.util.Locale.ROOT, "%.1f", target.getHealth()), ok ? "" : " — урон не прошёл");
        }
        if (!ok) {
            return;
        }
        Vec dir = new Vec(l.b().vx, l.b().vy, l.b().vz).normalize();
        if (g.knockback() > 0) {
            target.takeKnockback((float) g.knockback(), -dir.x(), -dir.z());
        }
        if (target instanceof Player tp && vacuum != null && vacTargetImpulse > 0 && vacuum.inVacuum(tp)) {
            double k = vacTargetImpulse / 20.0;
            vacuum.push(tp, dir.x() * k, dir.y() * k, dir.z() * k);
        }
        if (shooter.isOnline()) {
            int kind = target.isDead() || target.getHealth() <= 0 ? 3 : head ? 2 : 1;
            if (modded(shooter)) {
                links.send(shooter, Proto.hit(kind));
            } else {
                links.play(shooter, g.sound(kind == 3 ? "kill" : head ? "head_hit" : "flesh_hit"), shooter.getPosition(), 0.8f, 1f);
            }
        }
    }

    /** Мир для пуль: формы блоков (рамки) и живые сущности рядом. */
    private Ballistics.World world(Instance inst) {
        return new Ballistics.World() {
            @Override
            public List<double[]> blockBoxes(int x, int y, int z) {
                if (!inst.isChunkLoaded(x >> 4, z >> 4)) {
                    return null;
                }
                try {
                    var dim = inst.getCachedDimensionType();
                    if (y < dim.minY() || y >= dim.minY() + dim.height()) {
                        return List.of();
                    }
                    Block b = inst.getBlock(x, y, z, Block.Getter.Condition.TYPE);
                    if (b.isAir() || b.isLiquid()) {
                        return List.of();
                    }
                    Shape sh = b.registry().collisionShape();
                    if (sh instanceof ShapeImpl si) {
                        List<double[]> out = new ArrayList<>();
                        for (BoundingBox bb : si.boundingBoxes()) {
                            out.add(new double[] {bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ()});
                        }
                        return out;
                    }
                    var s = sh.relativeStart();
                    var e = sh.relativeEnd();
                    if (e.x() <= s.x() || e.y() <= s.y() || e.z() <= s.z()) {
                        return List.of();
                    }
                    return List.of(new double[] {s.x(), s.y(), s.z(), e.x(), e.y(), e.z()});
                } catch (RuntimeException ex) {
                    return List.of();
                }
            }

            @Override
            public List<Ballistics.Target> targets(double cx, double cy, double cz, double radius) {
                List<Ballistics.Target> out = new ArrayList<>();
                for (Entity e : inst.getNearbyEntities(new Vec(cx, cy, cz), radius)) {
                    if (!(e instanceof LivingEntity le) || le.isDead() || le.isRemoved()) {
                        continue;
                    }
                    if (le instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR) {
                        continue;
                    }
                    Pos p = le.getPosition();
                    BoundingBox bb = le.getBoundingBox();
                    double eye = p.y() + le.getEyeHeight();
                    out.add(new Ballistics.Target() {
                        @Override public double minX() { return p.x() + bb.minX(); }
                        @Override public double minY() { return p.y() + bb.minY(); }
                        @Override public double minZ() { return p.z() + bb.minZ(); }
                        @Override public double maxX() { return p.x() + bb.maxX(); }
                        @Override public double maxY() { return p.y() + bb.maxY(); }
                        @Override public double maxZ() { return p.z() + bb.maxZ(); }
                        @Override public double eyeY() { return eye; }
                        @Override public Object handle() { return le; }
                    });
                }
                return out;
            }
        };
    }

    // ---------------------------------------------------------------- патроны и перезарядка

    boolean infinite(Player p, GunDef g) {
        return g.infiniteAmmo() || p.getGameMode() == GameMode.CREATIVE;
    }

    /** Патроны в инвентаре (−1 — бесконечно). */
    int reserve(Player p, GunDef g) {
        if (infinite(p, g)) {
            return -1;
        }
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < INVENTORY; i++) {
            ItemStack s = inv.getItemStack(i);
            if (items.isAmmo(s, g)) {
                n += s.amount();
            }
        }
        return n;
    }

    private int takeAmmo(Player p, GunDef g, int want) {
        if (infinite(p, g)) {
            return want;
        }
        int got = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < INVENTORY && got < want; i++) {
            ItemStack s = inv.getItemStack(i);
            if (!items.isAmmo(s, g)) {
                continue;
            }
            int take = Math.min(s.amount(), want - got);
            got += take;
            inv.setItemStack(i, s.amount() - take <= 0 ? ItemStack.AIR : s.withAmount(s.amount() - take));
        }
        return got;
    }

    void startReload(Player p, ShooterState st) {
        GunDef g = st.gun;
        long now = System.currentTimeMillis();
        if (g == null || st.reloading() || st.drawing(now)) {
            return;
        }
        if (st.ammo >= g.capacity(st.ammo)) {
            return;
        }
        if (reserve(p, g) == 0) {
            p.sendActionBar(Component.text("Нет патронов: " + g.ammoName(), NamedTextColor.RED));
            if (!modded(p)) {
                links.play(p, g.sound("dry_fire"), p.getPosition(), 1f, 1f);
            }
            return;
        }
        st.reloadKind = st.ammo > 0 ? 1 : 2;
        st.reloadStart = now;
        st.reloadFed = false;
        st.reloadSoundIndex = 0;
        GunDef.Reload r = st.reloadKind == 1 ? g.tactical() : g.empty();
        links.send(p, Proto.reloadStart(st.reloadKind, r.total()));
        if (!modded(p)) {
            p.sendActionBar(Component.text("Перезарядка…", NamedTextColor.YELLOW));
        }
    }

    private void reloadTick(Player p, ShooterState st, long now) {
        if (!st.reloading()) {
            return;
        }
        GunDef g = st.gun;
        GunDef.Reload r = st.reloadKind == 1 ? g.tactical() : g.empty();
        double el = (now - st.reloadStart) / 1000.0;
        // звуки для остальных (на планете; в вакууме не слышно)
        Map<Double, String> snd = st.reloadKind == 1 ? g.reloadSoundsTactical() : g.reloadSoundsEmpty();
        int i = 0;
        for (Map.Entry<Double, String> e : snd.entrySet()) {
            if (i++ < st.reloadSoundIndex) {
                continue;
            }
            if (e.getKey() > el) {
                break;
            }
            st.reloadSoundIndex++;
            if (!st.vacuum || "all".equals(vacSound)) {
                effects.others(p, e.getValue(), 0.8f);
            }
        }
        if (!st.reloadFed && el >= r.feed()) {
            int cap = st.reloadKind == 1 ? g.capacity(1) : g.magazine();
            int want = Math.max(0, cap - st.ammo);
            st.ammo += takeAmmo(p, g, want);
            st.reloadFed = true;
            flushAmmo(p, st);
        }
        if (el >= r.total()) {
            st.cancelReload();
            if (!modded(p)) {
                p.sendActionBar(Component.text(g.name() + "  " + st.ammo + " / " + fmtReserve(reserve(p, g)), NamedTextColor.GREEN));
            }
        }
    }

    /** Звуки затвора для остальных (на планете; в вакууме не слышно) — по времени от начала передёргивания. */
    private void boltTick(Player p, ShooterState st, long now) {
        if (st.boltStart <= 0 || now < st.boltStart) {
            return;
        }
        double el = (now - st.boltStart) / 1000.0;
        int i = 0;
        for (Map.Entry<Double, String> e : st.gun.boltSounds3p().entrySet()) {
            if (i++ < st.boltSoundIndex) {
                continue;
            }
            if (e.getKey() > el) {
                return;
            }
            st.boltSoundIndex++;
            if (!st.vacuum || "all".equals(vacSound)) {
                effects.others(p, e.getValue(), 0.8f);
            }
        }
        st.boltStart = 0;                                     // все сыграны
    }

    static String fmtReserve(int r) {
        return r < 0 ? "∞" : String.valueOf(r);
    }

    private void sendState(Player p, ShooterState st, long now) {
        GunDef g = st.gun;
        int reserve = reserve(p, g);
        int flags = (st.reloading() ? Proto.FLAG_RELOADING : 0) | (st.drawing(now) ? Proto.FLAG_DRAWING : 0)
                | (st.vacuum ? Proto.FLAG_VACUUM : 0);
        String key = g.id() + '|' + st.ammo + '|' + reserve + '|' + st.mode + '|' + flags + '|' + st.ackSeq;
        if (modded(p)) {
            if (!key.equals(st.sentKey)) {
                st.sentKey = key;
                links.send(p, Proto.state(g.id(), st.ammo, reserve, st.mode, flags, st.ackSeq));
            }
        } else if (ticks % 20 == 0 && !st.reloading()) {               // без мода — строка над хотбаром
            p.sendActionBar(Component.text(g.name() + "  " + st.ammo + " / " + fmtReserve(reserve)
                    + "   ЛКМ — выстрел, ПКМ — прицел, F — перезарядка", NamedTextColor.GRAY));
        }
    }

    /** Сказать клиенту «оружия нет» (после /hogun take и т.п.) — при смене предмета клиент видит это сам. */
    void resetClient(Player p) {
        if (modded(p)) {
            links.send(p, Proto.state("", 0, 0, 0, 0, state(p).ackSeq));
            state(p).sentKey = "";
        }
    }
}
