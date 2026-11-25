package ink.hoka.odyssey.gravity;

import ink.hoka.odyssey.api.ModuleConfig;
import ink.hoka.odyssey.api.OdysseyModule;
import ink.hoka.odyssey.spacephys.Contact;
import ink.hoka.odyssey.spacephys.Field;
import ink.hoka.odyssey.spacephys.Integrator;
import ink.hoka.odyssey.spacephys.Quat;
import ink.hoka.odyssey.spacephys.State;
import ink.hoka.odyssey.spacephys.Thrust;
import ink.hoka.odyssey.spacephys.Units;
import ink.hoka.odyssey.spacephys.Vec3d;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.collision.Aerodynamics;
import net.minestom.server.collision.CollisionUtils;
import net.minestom.server.collision.PhysicsResult;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.attribute.AttributeModifier;
import net.minestom.server.entity.attribute.AttributeOperation;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.entity.vehicle.PlayerInputs;
import net.minestom.server.event.entity.EntitySpawnEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.network.packet.server.play.EntityVelocityPacket;
import net.minestom.server.network.packet.server.play.ExplosionPacket;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.utils.WeightedList;

/**
 * gravity — вакуум/невесомость на выбранных картах. Игроку ставятся модификаторы атрибутов: гравитация = 0 (клиент
 * перестаёт падать), прыжок с земли = 0, урон от падения = 0. Тягу (пробел/Shift/WASD) сервер каждый тик добавляет
 * к скорости клиента пакетом взрыва с отдачей игроку: в отличие от setVelocity он не затирает скорость, которую
 * клиент набрал сам, а прибавляется к ней — управление остаётся плавным при любом пинге. Взрыв «пустой»: радиус 0,
 * звук intentionally_empty, частица далеко под игроком (клиент её не рисует).
 * <p>
 * Чёрные дыры (black-holes в config.yml): игрока без нажатых клавиш вакуум сносит к ближайшей дыре (или по
 * drift.direction, если дыр рядом нет) теми же толчками. Предметы (ItemEntity) на картах с вакуумом — без
 * гравитации и почти без затухания, плывут по инерции, падают в дыры и исчезают за горизонтом. Кроме предметов в
 * зонах с обычной гравитацией (источник зон — {@link VacuumService.ZoneProvider}, его ставит gravzones): их gravity
 * отпускает, дыры их не тянут и не поглощают, а гравитацию зоны ставит владелец зон.
 * <p>
 * Настоящая физика вакуума (по умолчанию на всех картах из maps, кроме physics.legacy-maps): инерция без
 * затухания и пределов скорости, притяжение тел карты (bodies) и дыр по закону 1/r² (spacephys), слабый толчок
 * от блоков, урон от сильного удара, ранец WASD/пробел/Shift. Сервер ведёт настоящую скорость игрока и каждый тик
 * шлёт её клиенту пакетом EntityVelocity (с поправкой на затухание и ввод клиента, см. {@link Inertia}); на земле
 * (планета) двигается клиент сам — ему ставится гравитация, равная полю под ногами. Игроку с клиентским модулем
 * spaceflight сервер импульсов не шлёт, только тела карты ({@link SpaceflightLink}), и лишь проверяет скорость
 * (у блоков — с запасом physics.surface-walk: с модом можно ходить по стенам и потолку). Поворот тела такого
 * игрока сервер раздаёт другим игрокам ({@link BodyRelay}). Скорость игрока никогда не ведут сервер и клиент
 * одновременно: пропал модуль у клиента — сначала «физика выкл.», потом скорость от сервера.
 * <p>
 * /hogm ({@link HoGmCommand}) — выживание ↔ креатив для операторов; вакуум остаётся, урона в креативе нет.
 */
public final class GravityModule extends OdysseyModule {
    /** id модификаторов атрибутов модуля — по нему же снимаем. */
    static final Key MODIFIER = Key.key("hoka", "gravity_vacuum");
    private static final AttributeModifier ZERO = new AttributeModifier(MODIFIER, -1, AttributeOperation.ADD_MULTIPLIED_TOTAL);
    /** Насколько ниже игрока «взрыв» (частица и звук за пределами слышимости и видимости). */
    private static final double FAR_BELOW = 4096;
    /** Скорость предмета меньше этой (блоков/тик) — стоит, скорость не шлём. */
    private static final double ITEM_REST = 1.0e-4;
    /** Изменение скорости предмета меньше этого (блоков/тик) копим, а не шлём каждый раз. */
    private static final double ITEM_MIN_CHANGE = 0.002;
    /** Но не реже, чем раз в столько тиков. */
    private static final int ITEM_MAX_SILENCE = 20;
    /** Тиков после переноса на спавн, пока горизонт не проверяем (позиция клиента ещё старая). */
    private static final int SPAWN_COOLDOWN = 40;
    /** Высота «центра» игрока над ногами и предмета над его позицией — от них считаем расстояние до дыры. */
    static final double PLAYER_CENTER = 0.9;
    private static final double ITEM_CENTER = 0.125;
    /** На сколько тиков вперёд (сверх items.update-ticks) проверять, что чанк на пути предмета загружен. */
    private static final int ITEM_LOOKAHEAD = 2;
    /** Шаг пробы «есть ли рядом блок» для толчка, блоков. */
    private static final double PROBE = 0.15;
    /** Удары чаще, чем раз в столько тиков, — одно столкновение (урон один раз). */
    private static final int IMPACT_COOLDOWN = 10;
    /** Касание блоков у игроков с модом (запас проверки скорости) — раз в столько тиков. */
    private static final int SURFACE_CHECK = 2;
    /** Проба касания для запаса проверки скорости, блоков: шире, чем у клиента (позиция сервера отстаёт). */
    private static final double SURFACE_PROBE = 0.35;
    /** Нарушения проверки скорости — в лог не чаще, чем раз в столько тиков на игрока. */
    private static final int VIOLATION_LOG_TICKS = 100;
    /** Ограничение скорости при нарушении — не чаще, чем раз в столько тиков (не «драться» с клиентом). */
    private static final int CLAMP_TICKS = 10;
    /** Удар медленнее этого (по нормали, блоки/с) — без отскока: иначе упор в стену дрожит отскоками. */
    private static final double REST_SPEED = 1.0;
    /** Сколько тиков после касания ещё действует запас (оттолкнулся и отлетел). */
    private static final int SURFACE_HOLD = 20;
    /** Скорость в action bar — раз в столько тиков. */
    private static final int READOUT_TICKS = 10;
    /** Гравитация игрока по умолчанию в Minecraft, блоков/тик². */
    private static final double VANILLA_GRAVITY = 0.08;
    /** Мировой «верх» — для пробела/Shift у ранца и толчка с земли. */
    private static final Vec3d UP = new Vec3d(0, 1, 0);

    private volatile Settings settings;
    private final Map<UUID, Floater> floaters = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> spawnCooldown = new ConcurrentHashMap<>();
    /** Предметы, которым модуль выключил гравитацию: прежняя аэродинамика (вернуть при выходе из вакуума). */
    private final Map<ItemEntity, ItemState> items = new ConcurrentHashMap<>();   // Entity — без equals, по ссылке
    /** Точки появления карт (из maps/&lt;карта&gt;/map.yml) для player-on-horizon: spawn. */
    private final Map<String, Pos> mapSpawns = new ConcurrentHashMap<>();
    private volatile long ticks;
    /** Очередь правок config.yml: пишем строго по одной и по порядку команд. */
    private final Queue<ConfigWrite> writes = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean writing = new AtomicBoolean();
    /** Вакуум отдельным игрокам поверх карт — сервис {@link VacuumService} для других модулей (gravzones). */
    private volatile RealPhysics physics;
    /** Игроки на картах с настоящей физикой. */
    private final Map<UUID, Spacer> spacers = new ConcurrentHashMap<>();
    /** У кого только что загрузился клиентский модуль spaceflight (проверить в ближайшем тике). */
    private final Set<UUID> spaceflightReady = ConcurrentHashMap.newKeySet();
    private SpaceflightLink link;
    /** Повороты тел игроков с модом — другим игрокам ({@link BodyRelay}). */
    private BodyRelay bodyRelay;
    private final VacuumOverrides overrides = new VacuumOverrides(floaters, spacers);
    /** Чёрные дыры для других модулей (spacesky рисует их в небе) — сервис {@link BlackHoles}. */
    private final BlackHolesService blackHoles = new BlackHolesService(() -> settings, w -> logger().warn(w));

    @Override
    protected void onEnable() {
        settings = Settings.from(config());
        physics = loadPhysics();
        link = new SpaceflightLink(this, logger());
        bodyRelay = new BodyRelay(link, p -> {
            Spacer sp = spacers.get(p.getUuid());
            if (sp != null) {
                sp.boxShift = true;                        // смена рамки: следующий пакет движения — со сдвигом
            }
        });
        link.resolve(p -> spaceflightReady.add(p.getUuid()), this::clientMessage);
        services().register(VacuumService.class, overrides);
        services().register(BlackHoles.class, blackHoles);
        registerCommand(new HoGravCommand(this));
        registerCommand(new HoGmCommand(this));
        // PlayerSpawnEvent срабатывает и при входе, и при каждой смене карты (setInstance)
        events().addListener(PlayerSpawnEvent.class, e -> refresh(e.getPlayer(), e.getInstance(), true));
        events().addListener(PlayerDisconnectEvent.class, e -> {
            floaters.remove(e.getPlayer().getUuid());
            spacers.remove(e.getPlayer().getUuid());
            spaceflightReady.remove(e.getPlayer().getUuid());
            spawnCooldown.remove(e.getPlayer().getUuid());
            overrides.forget(e.getPlayer().getUuid());
            bodyRelay.forget(e.getPlayer().getUuid());
        });
        // предмет появился на карте с вакуумом — сразу без гравитации (до первого тика физики, иначе он успеет
        // набрать скорость падения)
        events().addListener(EntitySpawnEvent.class, e -> {
            if (e.getEntity() instanceof ItemEntity item && settings.space().items() && inVacuum(e.getInstance())) {
                Pos pos = item.getPosition();
                if (VacuumService.itemInVacuum(true,
                    VacuumOverrides.modeAt(overrides.zones(e.getInstance()), pos.x(), pos.y(), pos.z()))) {
                    capture(item, physics.real(mapOf(e.getInstance()).orElse("")));
                }
            }
        });
        // игрок с модом spaceflight считает физику сам — проверяем только, что быстрее физики он не летает
        events().addListener(PlayerMoveEvent.class, this::checkMove);
        scheduler().repeat(this::tick, 1, 1);
        refreshAll();
        loadMapSpawns();
        logger().info("gravity: вакуум на картах {}, чёрных дыр: {}, тел: {}, старая физика на {}, команда /hograv",
            settings.maps(), holeCount(), bodyCount(), physics.legacyMaps());
    }

    @Override
    protected void onDisable() {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (floaters.containsKey(p.getUuid())) {
                clearAttributes(p);
            }
        }
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            Spacer sp = spacers.get(p.getUuid());
            if (sp != null && sp.clientOn) {
                link.sendPhysics(p, false);                // клиентский модуль перестаёт считать вакуум
            }
        }
        if (bodyRelay != null) {
            bodyRelay.close();                             // зрителям — обычный вид игроков
        }
        if (link != null) {
            link.close();
        }
        spacers.clear();
        spaceflightReady.clear();
        floaters.clear();
        spawnCooldown.clear();
        for (var e : items.entrySet()) {
            restore(e.getKey(), e.getValue());
        }
        items.clear();
        overrides.clear();
        blackHoles.clear();
    }

    Settings settings() {
        return settings;
    }

    /** Перечитать config.yml и применить ко всем игрокам. */
    void reloadSettings() {
        config().reload();
        settings = Settings.from(config());
        physics = loadPhysics();
        for (Spacer sp : spacers.values()) {
            sp.clientOn = false;                           // тела могли поменяться — разослать заново в тике
            sp.pack = new Thrust(thrustSpec(physics));
        }
        refreshAll();
        loadMapSpawns();
        blackHoles.changed();
    }

    private RealPhysics loadPhysics() {
        RealPhysics rp = RealPhysics.from(config(), settings.space().holes());
        for (String w : rp.warnings()) {
            logger().warn("gravity: {}", w);
        }
        return rp;
    }

    RealPhysics physics() {
        return physics;
    }

    int bodyCount() {
        return physics.bodies().values().stream().mapToInt(List::size).sum();
    }

    /** Сервис другого модуля по классу, найденному рефлексией (null — нет). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Object service(Class<?> type) {
        return services().get((Class) type).orElse(null);
    }

    /**
     * Сообщение клиентского модуля spaceflight (поток clientmods: только разобрать и запомнить). op 6 — модуль у
     * клиента (пере)загрузился: в ближайшем тике заново тела и «физика вкл.»; остальное — повороты тел.
     */
    private void clientMessage(Player p, byte[] data) {
        if (p != null && data != null && data.length > 0 && (data[0] & 0xFF) == SpaceflightLink.OP_HELLO) {
            spaceflightReady.add(p.getUuid());
            return;
        }
        bodyRelay.receive(p, data);
    }

    /**
     * Сменили режим игры (/hogm): скорость с нуля — иначе скорость креативного полёта (до ~11 б/с) осталась бы
     * в вакууме и унесла бы игрока; клиенту с модом заново «физика вкл.» (проверка скорости — с чистого листа).
     */
    void resetMotion(Player p) {
        Spacer sp = spacers.get(p.getUuid());
        if (sp != null) {
            sp.reset();
            sp.clientOn = false;
        }
        Floater f = floaters.get(p.getUuid());
        if (f != null) {
            f.reset();
        }
        if (floaters.containsKey(p.getUuid())) {
            p.sendPacket(new EntityVelocityPacket(p.getEntityId(), Vec.ZERO));
        }
    }

    /** Выполнить в тике сервера. */
    void runInTick(Runnable r) {
        scheduler().run(r);
    }

    /** Сколько игроков сейчас в вакууме. */
    int floating() {
        return floaters.size();
    }

    /** Сколько предметов сейчас в вакууме. */
    int floatingItems() {
        return items.size();
    }

    int holeCount() {
        return settings.space().holes().values().stream().mapToInt(List::size).sum();
    }

    /**
     * Включить/выключить вакуум на карте: записать список карт в config.yml (с сохранением комментариев) в
     * отдельном потоке, затем в тике перечитать и применить к игрокам. done получает null или текст ошибки.
     * Список берётся из файла в момент записи — правки руками и предыдущие команды не теряются.
     */
    void setMap(String map, boolean on, Consumer<String> done) {
        writeConfig((cur, text) -> {
            Set<String> maps = new LinkedHashSet<>(cur.getStringList("maps"));
            if (on) {
                maps.add(map);
            } else {
                maps.remove(map);
            }
            return YamlList.replace(text, "maps", maps);
        }, done);
    }

    /** Добавить/заменить (hole != null) или убрать (hole == null) дыру name на карте map. */
    void setHole(String map, String name, BlackHole hole, Consumer<String> done) {
        writeConfig((cur, text) -> {
            Map<String, List<BlackHole>> all = new LinkedHashMap<>();
            Settings.holes(cur, Settings.holeDefaults(cur)).forEach((k, v) -> all.put(k, new ArrayList<>(v)));
            List<BlackHole> list = all.computeIfAbsent(map, k -> new ArrayList<>());
            int i = 0;
            while (i < list.size() && !list.get(i).name().equals(name)) {
                i++;
            }
            if (hole == null) {
                if (i < list.size()) {
                    list.remove(i);
                }
            } else if (i < list.size()) {
                list.set(i, hole);
            } else {
                list.add(hole);
            }
            return HoleYaml.replace(text, all);
        }, done);
    }

    /**
     * Переписать config.yml в отдельном потоке, затем в тике перечитать. Правка получает текущее содержимое файла
     * (разобранное и текстом). Правки идут по очереди: одна async-задача разбирает очередь, пока та не опустеет.
     * done получает null или текст ошибки.
     */
    private void writeConfig(BiFunction<ModuleConfig, String, String> edit, Consumer<String> done) {
        writes.add(new ConfigWrite(edit, done));
        if (writing.compareAndSet(false, true)) {
            scheduler().async(this::drainWrites);
        }
    }

    private void drainWrites() {
        Path file = config().file();
        do {
            ConfigWrite w;
            while ((w = writes.poll()) != null) {
                String error = null;
                try {
                    String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
                    String out = w.edit.apply(new ModuleConfig(file), text);
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, out, StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException e) {
                    error = e.toString();
                    logger().warn("не записать {}: {}", file, e.toString());
                }
                String err = error;
                Consumer<String> done = w.done;
                scheduler().run(() -> {
                    String result = err;
                    if (result == null) {
                        try {
                            reloadSettings();
                        } catch (RuntimeException e) {
                            result = "записано, но не перечитать: " + e;
                            logger().warn("не перечитать {}: {}", file, e.toString());
                        }
                    }
                    done.accept(result);
                });
            }
            writing.set(false);
            // задача могла встать в очередь между poll() и set(false) — тогда разбираем дальше сами
        } while (!writes.isEmpty() && writing.compareAndSet(false, true));
    }

    private record ConfigWrite(BiFunction<ModuleConfig, String, String> edit, Consumer<String> done) {}

    /** Прочитать точки появления карт с дырами (файлы — в отдельном потоке). */
    private void loadMapSpawns() {
        if (!settings.space().horizonSpawn()) {
            return;
        }
        Set<String> maps = new LinkedHashSet<>(settings.space().holes().keySet());
        scheduler().async(() -> {
            for (String map : maps) {
                Path f = Path.of("maps", map, "map.yml");
                try {
                    if (Files.isRegularFile(f)) {
                        ModuleConfig m = new ModuleConfig(f);
                        mapSpawns.put(map, new Pos(m.getDouble("spawn.x", 0.5), m.getDouble("spawn.y", 65),
                            m.getDouble("spawn.z", 0.5), (float) m.getDouble("spawn.yaw", 0), (float) m.getDouble("spawn.pitch", 0)));
                    }
                } catch (RuntimeException e) {
                    logger().warn("не прочитать {}: {}", f, e.toString());
                }
            }
        });
    }

    Optional<String> mapOf(Instance instance) {
        return instance == null ? Optional.empty() : server().mapName(instance);
    }

    private void refreshAll() {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            refresh(p, p.getInstance(), false);
        }
    }

    /** В вакууме ли игрок: сначала решение через VacuumService, иначе — по карте. */
    private boolean inVacuum(Player p, Instance instance) {
        Boolean forced = overrides.decide(p);
        return forced != null ? forced && instance != null : inVacuum(instance);
    }

    private boolean inVacuum(Instance instance) {
        return mapOf(instance).map(settings.maps()::contains).orElse(false);
    }

    /** Привести игрока в соответствие с картой: включить/выключить вакуум. reset — игрок только что появился. */
    private void refresh(Player p, Instance instance, boolean reset) {
        boolean should = inVacuum(p, instance);
        Floater f = floaters.get(p.getUuid());
        if (should) {
            if (f == null) {
                floaters.put(p.getUuid(), new Floater());
                if (!settings.enterHint().isEmpty()) {
                    p.sendActionBar(Component.text(settings.enterHint(), NamedTextColor.AQUA));
                }
            } else if (reset) {
                f.reset();
            }
            Spacer sp = spacers.get(p.getUuid());
            if (sp != null && reset) {
                sp.reset();
                sp.clientOn = false;                       // новая карта — новые тела (и «физика вкл.»)
            }
            applyAttributes(p);
        } else if (f != null) {
            floaters.remove(p.getUuid());
            dropSpacer(p);
            clearAttributes(p);
        }
    }

    private void applyAttributes(Player p) {
        Settings s = settings;
        set(p, Attribute.GRAVITY, true);
        set(p, Attribute.JUMP_STRENGTH, !s.groundJump());
        set(p, Attribute.FALL_DAMAGE_MULTIPLIER, !s.fallDamage());
    }

    private static void clearAttributes(Player p) {
        set(p, Attribute.GRAVITY, false);
        set(p, Attribute.JUMP_STRENGTH, false);
        set(p, Attribute.FALL_DAMAGE_MULTIPLIER, false);
    }

    /** Поставить (on) или снять модификатор «×0» модуля. Повторный вызов ничего не дублирует. */
    private static void set(Player p, Attribute a, boolean on) {
        var inst = p.getAttribute(a);
        boolean has = inst.modifiers().stream().anyMatch(m -> m.id().equals(MODIFIER));
        if (on && !has) {
            inst.addModifier(ZERO);
        } else if (!on && has) {
            inst.removeModifier(MODIFIER);
        }
    }

    private void tick() {
        Settings s = settings;
        RealPhysics rp = physics;
        ticks++;
        if (ticks % 100 == 0) {
            link.resolve(p -> spaceflightReady.add(p.getUuid()), this::clientMessage);   // clientmods могли перезагрузить
        }
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            Instance instance = p.getInstance();
            boolean should = inVacuum(p, instance);
            if (should != floaters.containsKey(p.getUuid())) {
                refresh(p, instance, true);                     // карту включили/выключили, а игрок уже там
            }
            Floater f = floaters.get(p.getUuid());
            if (f == null) {
                continue;
            }
            Pos pos = p.getPosition();
            boolean flying = p.isFlying() || p.getGameMode() == GameMode.SPECTATOR;
            String map = mapOf(instance).orElse("");
            List<BlackHole> holes = s.space().holes(map);
            if (!flying && horizon(p, f, holes, pos)) {
                Spacer sp = spacers.get(p.getUuid());
                if (sp != null) {
                    sp.reset();
                }
                continue;
            }
            if (rp.real(map)) {
                tickReal(p, instance, map, s, rp);
                continue;
            }
            dropSpacer(p);                                  // карта со старой физикой
            PlayerInputs in = p.inputs();
            Floater.Keys keys = new Floater.Keys(in.forward(), in.backward(), in.left(), in.right(), in.jump(), in.shift(),
                in.sprint());
            Gravitation.Force drift = keys.any() ? null : playerDrift(s.space(), holes, pos);
            Floater.Impulse imp = f.tick(s, pos.x(), pos.y(), pos.z(), pos.yaw(), keys, p.isOnGround(), flying, drift);
            if (imp != null) {
                push(p, imp);
            }
        }
        if (ticks % s.space().itemTicks() == 0) {
            tickItems(s);
        }
        bodyRelay.tick(p -> {
            Spacer sp = spacers.get(p.getUuid());
            return sp != null && sp.modded && sp.clientOn;
        });
    }

    private static Gravitation.Force playerDrift(Settings.Space sp, List<BlackHole> holes, Pos pos) {
        return Gravitation.playerForce(holes, pos.x(), pos.y() + PLAYER_CENTER, pos.z(), sp.falloff(), sp.minPull(),
            sp.playerScale(), sp.playerMaxDrift(), !sp.horizonSpawn(), sp.driftDir(), sp.driftSpeed(), sp.driftAccel());
    }

    /** Игрок за горизонтом при player-on-horizon: spawn — на спавн карты. true — перенесён (тик пропустить). */
    private boolean horizon(Player p, Floater f, List<BlackHole> holes, Pos pos) {
        Integer cd = spawnCooldown.get(p.getUuid());
        if (cd != null) {
            if (cd <= 1) {
                spawnCooldown.remove(p.getUuid());
            } else {
                spawnCooldown.put(p.getUuid(), cd - 1);
            }
            return false;
        }
        if (!settings.space().horizonSpawn() || holes.isEmpty()) {
            return false;
        }
        double cy = pos.y() + PLAYER_CENTER;
        BlackHole h = Gravitation.nearest(holes, pos.x(), cy, pos.z());
        if (h == null || h.distance(pos.x(), cy, pos.z()) > h.horizon()) {
            return false;
        }
        String map = mapOf(p.getInstance()).orElse("");
        Pos to = mapSpawns.getOrDefault(map, p.getRespawnPoint());
        p.teleport(to, Vec.ZERO);
        f.reset();
        spawnCooldown.put(p.getUuid(), SPAWN_COOLDOWN);
        p.sendActionBar(Component.text("Вас затянуло в чёрную дыру «" + h.name() + "» — выброшены на точку появления",
            NamedTextColor.LIGHT_PURPLE));
        return true;
    }

    /**
     * Предметы: на картах с вакуумом — без гравитации, к дырам; вне вакуума и в зонах с обычной гравитацией — вернуть
     * как было (дыры их не тянут и не поглощают).
     */
    private void tickItems(Settings s) {
        Settings.Space sp = s.space();
        for (Iterator<Map.Entry<ItemEntity, ItemState>> it = items.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            ItemEntity item = e.getKey();
            if (item.isRemoved()) {
                it.remove();
            } else if (!sp.items() || !inVacuum(item.getInstance())) {
                restore(item, e.getValue());
                it.remove();
            }
        }
        if (!sp.items()) {
            return;
        }
        for (Instance instance : MinecraftServer.getInstanceManager().getInstances()) {
            if (!inVacuum(instance)) {
                continue;
            }
            String map = mapOf(instance).orElse("");
            List<BlackHole> holes = sp.holes(map);
            boolean real = physics.real(map);
            Field field = physics.field(map);
            VacuumService.ZoneLookup zones = overrides.zones(instance);   // раз на карту, не на предмет
            List<ItemEntity> absorbed = new ArrayList<>();
            List<ItemEntity> lost = new ArrayList<>();
            for (Entity en : instance.getEntities()) {
                if (!(en instanceof ItemEntity item) || item.isRemoved()) {
                    continue;
                }
                Pos pos = item.getPosition();
                if (zones != null && !VacuumService.itemInVacuum(true,
                    VacuumOverrides.modeAt(zones, pos.x(), pos.y(), pos.z()))) {
                    ItemState was = items.remove(item);       // залетел в зону с обычной гравитацией — отпустить
                    if (was != null) {
                        restore(item, was);
                        overrides.released(item);             // гравитация зоны — в этом же тике
                    }
                    continue;
                }
                ItemState st = capture(item, real);
                double cy = pos.y() + ITEM_CENTER;
                BlackHole h = Gravitation.nearest(holes, pos.x(), cy, pos.z());
                if (h != null && h.distance(pos.x(), cy, pos.z()) <= h.horizon()) {
                    absorbed.add(item);
                    continue;
                }
                Vec v = item.getVelocity().div(Settings.TPS);
                int dt = (int) Math.min(ITEM_MAX_SILENCE, Math.max(1, ticks - st.updated));
                Vec nv;
                if (real) {
                    // настоящая физика: без затухания, притяжение всех тел и дыр карты (spacephys)
                    Aerodynamics ad = item.getAerodynamics();
                    if (ad.horizontalAirResistance() != 1.0 || ad.verticalAirResistance() != 1.0) {
                        item.setAerodynamics(new Aerodynamics(ad.gravity(), 1.0, 1.0));
                    }
                    State is = new State(pos.x(), cy, pos.z(), v.x() * Settings.TPS, v.y() * Settings.TPS,
                        v.z() * Settings.TPS);
                    Integrator.DEFAULT.kick(field, is, dt * Units.TICK);
                    nv = finite(is) ? new Vec(is.vx, is.vy, is.vz).div(Settings.TPS) : Vec.ZERO;
                } else {
                    double[] next = Gravitation.itemVelocity(new double[] {v.x(), v.y(), v.z()}, h, pos.x(), cy,
                        pos.z(), sp.falloff(), sp.minPull(), sp.itemKeep(), dt);
                    nv = new Vec(next[0], next[1], next[2]);
                }
                if (nv.length() < ITEM_REST) {
                    nv = Vec.ZERO;
                }
                // Minestom не двигает сущность в незагруженный чанк — предмет встал бы стеной на краю мира у игроков.
                // Летит к дыре — считаем, что упал в неё (тихо: рядом никого); дыры нет — гасим скорость, висит.
                if (!nv.isZero() && !instance.isChunkLoaded(pos.add(nv.mul(ITEM_LOOKAHEAD + sp.itemTicks())))) {
                    if (h != null) {
                        lost.add(item);
                        continue;
                    }
                    nv = Vec.ZERO;
                }
                // предмет у клиента сам гасит скорость (×0.98 за тик), а позицию сервер шлёт раз в секунду —
                // пока летит, синхронизируем часто, иначе он у игрока отстаёт и скачет рывками
                long sync = nv.isZero() ? st.originalSync : sp.itemTicks();
                if (item.getSynchronizationTicks() != sync) {
                    item.setSynchronizationTicks(sync);
                    if (sync < st.originalSync) {
                        item.synchronizeNextTick();
                    }
                }
                if (nv.samePoint(v)) {
                    st.updated = ticks;
                } else if (nv.distance(v) >= ITEM_MIN_CHANGE || dt >= ITEM_MAX_SILENCE || nv.isZero()) {
                    item.setVelocity(nv.mul(Settings.TPS));      // мелкие изменения копим — меньше пакетов
                    st.updated = ticks;
                }
            }
            for (ItemEntity item : absorbed) {
                absorb(item, sp.absorbEffect());
            }
            for (ItemEntity item : lost) {
                absorb(item, false);
            }
        }
    }

    /** Взять предмет в вакуум (повторно — ничего не меняет). real — настоящая физика: без затухания совсем. */
    private ItemState capture(ItemEntity item, boolean real) {
        return items.computeIfAbsent(item, it -> {
            Aerodynamics a = it.getAerodynamics();
            it.setNoGravity(true);
            // затухание и притяжение считаем сами, сервер скорость между нашими пересчётами не меняет
            it.setAerodynamics(new Aerodynamics(a.gravity(), 1.0, real ? 1.0 : a.verticalAirResistance()));
            return new ItemState(a, it.getSynchronizationTicks(), ticks - settings.space().itemTicks());
        });
    }

    private void absorb(ItemEntity item, boolean effect) {
        Pos pos = item.getPosition();
        if (effect && item.getInstance() != null) {
            item.sendPacketToViewers(new ParticlePacket(Particle.REVERSE_PORTAL, pos.add(0, ITEM_CENTER, 0),
                new Vec(0.15, 0.15, 0.15), 0.05f, 12));
            item.getInstance().playSound(Sound.sound(SoundEvent.ENTITY_ENDERMAN_TELEPORT, Sound.Source.NEUTRAL, 0.4f, 0.5f),
                pos.x(), pos.y(), pos.z());
        }
        items.remove(item);
        item.remove();
    }

    private static void restore(ItemEntity item, ItemState st) {
        if (item.isRemoved()) {
            return;
        }
        item.setAerodynamics(released(item.getAerodynamics(), st.original));
        item.setSynchronizationTicks(st.originalSync);
        item.setNoGravity(false);
    }

    /**
     * Аэродинамика предмета, которого вакуум отпускает: прежнее затухание, но гравитация — текущая. Сам gravity её
     * не меняет (выключает флагом noGravity), а gravzones мог поменять, пока предмет был в вакууме (вернуть исходную
     * при вылете из зоны), — старое значение из момента захвата затёрло бы его правку.
     */
    static Aerodynamics released(Aerodynamics current, Aerodynamics original) {
        return new Aerodynamics(current.gravity(), original.horizontalAirResistance(), original.verticalAirResistance());
    }

    /** Предмет в вакууме: прежняя аэродинамика и тик последнего пересчёта скорости. */
    private static final class ItemState {
        final Aerodynamics original;
        /** Прежний интервал синхронизации позиции с клиентами. */
        final long originalSync;
        volatile long updated;

        ItemState(Aerodynamics original, long originalSync, long updated) {
            this.original = original;
            this.originalSync = originalSync;
            this.updated = updated;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Настоящая физика вакуума (игроки)
    // ---------------------------------------------------------------------------------------------------------------

    private static Thrust.Spec thrustSpec(RealPhysics rp) {
        return rp.deltaV() == Double.POSITIVE_INFINITY ? Thrust.Spec.unlimited(rp.thrust())
            : new Thrust.Spec(rp.thrust(), rp.deltaV());
    }

    /** Убрать игрока из настоящей физики (ушёл из вакуума или на карту со старой): мод выключить, гравитация 0. */
    private void dropSpacer(Player p) {
        Spacer sp = spacers.remove(p.getUuid());
        if (sp == null) {
            return;
        }
        if (sp.clientOn) {
            link.sendPhysics(p, false);
        }
        if (!Double.isNaN(sp.gravityAttr)) {
            var inst = p.getAttribute(Attribute.GRAVITY);
            inst.removeModifier(MODIFIER);
            if (floaters.containsKey(p.getUuid())) {
                inst.addModifier(ZERO);                    // остаётся в вакууме (старая физика) — гравитация 0
            }
        }
    }

    /**
     * Один тик игрока на карте с настоящей физикой. Без мода: сервер ведёт настоящую скорость и шлёт её клиенту;
     * на земле двигается клиент (гравитация = поле под ногами). С модом spaceflight — только проверка.
     */
    private void tickReal(Player p, Instance instance, String map, Settings s, RealPhysics rp) {
        Spacer sp = spacers.computeIfAbsent(p.getUuid(), k -> {
            Spacer n = new Spacer(thrustSpec(rp));
            n.reset();
            return n;
        });
        Field field = rp.field(map);
        Pos pos = p.getPosition();
        State st = sp.state;
        if (!sp.observe(pos.x(), pos.y() + PLAYER_CENTER, pos.z())) {
            st.setVelocity(0, 0, 0);                        // телепорт — с нуля
        }
        if (spaceflightReady.remove(p.getUuid())) {
            sp.modded = link.ready(p);
            sp.clientOn = false;                           // модуль (пере)загрузился у клиента — тела и «вкл.» заново
        } else if ((ticks + p.getEntityId()) % 20 == 0) {
            sp.modded = link.ready(p);
        }
        boolean flying = p.isFlying() || p.getGameMode() == GameMode.SPECTATOR;
        double[] m = sp.measured();
        if (sp.modded) {
            tickModded(p, sp, field, rp, m);
            return;
        }
        if (sp.clientOn) {
            // модуль у клиента пропал (clientmods перезагрузили, /hoclient reload), а «физика вкл.» у него могла
            // остаться: дальше скорость ведёт сервер — клиенту сначала «выкл.», иначе вели бы оба и игрока швыряло
            link.sendPhysics(p, false);
            sp.clientOn = false;
            st.setVelocity(m[0], m[1], m[2]);
        }
        Vec3d a = field.acceleration(st.x, st.y, st.z);
        setGravity(p, sp, flying ? 0 : Math.max(0, -a.y()));
        PlayerInputs in = p.inputs();
        boolean jumpEdge = in.jump() && !sp.wasJump;
        sp.wasJump = in.jump();
        if (flying) {                                       // креативный полёт — у клиента своя физика
            st.setVelocity(m[0], m[1], m[2]);
            sp.grounded = false;
            return;
        }
        boolean onGround = p.isOnGround() && sp.pushGrace == 0;
        if (sp.pushGrace > 0) {
            sp.pushGrace--;
        }
        if (onGround) {
            // на земле (планета): ходит клиент сам, у него гравитация = поле под ногами; скорость — по замерам
            if (!sp.grounded) {
                landing(p, sp, rp);
            }
            sp.grounded = true;
            st.setVelocity(m[0], m[1], m[2]);
            sp.pack.refill();
            sp.sentZero = false;
            if (jumpEdge && rp.pushOff() > 0) {
                Vec3d dir = Inertia.pushDirection(UP, look(pos));
                st.setVelocity(st.vx + dir.x() * rp.pushOff(), Math.max(0, st.vy) + dir.y() * rp.pushOff(),
                    st.vz + dir.z() * rp.pushOff());
                sp.grounded = false;
                sp.pushGrace = Spacer.PUSH_GRACE;
                sendVelocity(p, sp, null);
            }
            readout(p, sp, rp);
            return;
        }
        if (sp.grounded) {                                  // сошёл с края или подпрыгнул — с той же скоростью
            st.setVelocity(m[0], m[1], m[2]);
            sp.grounded = false;
        }
        int fwd = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0);
        int strafe = (in.left() ? 1 : 0) - (in.right() ? 1 : 0);
        int up = (in.jump() ? 1 : 0) - (in.shift() ? 1 : 0);
        Quat q = Quat.fromYawPitchRoll(pos.yaw(), pos.pitch(), 0);
        if (jumpEdge && rp.pushOff() > 0) {                 // пробел у стены/потолка — толчок от блока
            Vec3d n = contactNormal(instance, p, pos);
            if (n.lengthSquared() > 0) {
                Vec3d dir = Inertia.pushDirection(n, q.forward());
                st.setVelocity(st.vx + dir.x() * rp.pushOff(), st.vy + dir.y() * rp.pushOff(),
                    st.vz + dir.z() * rp.pushOff());
                sp.pushGrace = Spacer.PUSH_GRACE;
                up = 0;
            }
        }
        Vec3d thrust = Vec3d.ZERO;
        if (fwd != 0 || strafe != 0 || up != 0) {
            if (rp.thrust() > 0) {
                thrust = sp.pack.accelerate(fwd, -strafe, up, q.forward(), q.right(), UP, Units.TICK);
            }
        } else {
            // без клавиш: бег (Ctrl) — тормоз, иначе стабилизатор (тела карты неподвижны: относительно опорного
            // тела — это просто скорость); топливо не тратят
            thrust = Thrust.damp(st.velocity(), in.sprint() ? Math.max(rp.brake(), rp.stabilizer()) : rp.stabilizer(),
                Units.TICK);
        }
        Integrator.DEFAULT.kick(field, st, Units.TICK, thrust.x(), thrust.y(), thrust.z());
        if (!s.space().horizonSpawn()) {
            holdAtHorizon(st, s.space().holes(map));
        }
        Vec3d capped = Thrust.capSpeed(st.velocity(), rp.maxSpeed());
        st.setVelocity(capped.x(), capped.y(), capped.z());
        collide(p, sp, instance, pos, rp);
        if (!finite(st)) {
            st.setVelocity(0, 0, 0);
        }
        sendVelocity(p, sp, Inertia.airInput(pos.yaw(), fwd, strafe, in.sprint()));
        readout(p, sp, rp);
    }

    /** Игрок с модом spaceflight: тела карты и «физика вкл.» — один раз, дальше только проверка скорости. */
    private void tickModded(Player p, Spacer sp, Field field, RealPhysics rp, double[] m) {
        if (!sp.clientOn) {
            link.sendBodies(p, field.bodies());
            link.sendPhysicsOn(p, rp.thrust(), rp.maxSpeed());
            sp.clientOn = true;
            double v = Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
            sp.guard.reset(v);
            sp.guard.credit = (v + Inertia.MoveGuard.SLACK) * Inertia.MoveGuard.BURST / Settings.TPS;
        }
        setGravity(p, sp, 0);                              // vanilla-гравитацию клиента не включаем — считает мод
        sp.state.setVelocity(m[0], m[1], m[2]);
        sp.guard.measured(sp.lastStep() * Settings.TPS);
        // у блока клиент может ходить по стене/потолку и отталкиваться — шаг и прыжок не ускорение ранца
        if (bodyRelay.gravity(p.getUuid())) {
            sp.surfaceTicks = SURFACE_HOLD;                // тяготение грани: прыжок и падение обратно — ходьба
        } else if ((ticks + p.getEntityId()) % SURFACE_CHECK == 0) {
            Instance instance = p.getInstance();
            boolean touching = instance != null && touching(instance, p, p.getPosition());
            sp.surfaceTicks = touching ? SURFACE_HOLD : Math.max(0, sp.surfaceTicks - SURFACE_CHECK);
        }
        sp.guard.tick(field.acceleration(sp.state.x, sp.state.y, sp.state.z).length() + rp.thrust(), allowance(sp, rp));
        readout(p, sp, rp);
    }

    /**
     * Пакет движения игрока с модом: путь за пакет не больше, чем позволяет физика (иначе — назад), и урон от
     * удара, если скорость резко упала у блока.
     */
    private void checkMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Spacer sp = spacers.get(p.getUuid());
        RealPhysics rp = physics;
        if (sp == null || !sp.modded || !sp.clientOn || p.isFlying() || p.getGameMode() == GameMode.SPECTATOR
            || p.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Pos from = p.getPosition();
        Pos to = e.getNewPosition();
        double d = from.distance(to);
        // клиент сменил рамку (встал на стену/потолок или сошёл): позиция сдвигается до PlayerBox.MAX_SHIFT за раз —
        // разовый запас; это не скорость (ни удара, ни замера)
        boolean shifted = sp.boxShift;
        if (shifted) {
            sp.boxShift = false;
            sp.guard.credit += PlayerBox.SHIFT_CREDIT;
        }
        if (rp.antiCheat() && d > sp.guard.credit && sp.surfaceTicks == 0 && p.getInstance() != null
            && (touching(p.getInstance(), p, from) || touching(p.getInstance(), p, to))) {
            // у блока (шаг по стене, прыжок от неё) — запас surface-walk сразу, а не с проверки раз в пару тиков
            sp.surfaceTicks = SURFACE_HOLD;
            sp.guard.credit += (rp.surfaceWalk() + rp.pushOff()) * Inertia.MoveGuard.BURST / Settings.TPS;
            sp.guard.speed = Math.max(sp.guard.speed, d * Settings.TPS);
        }
        if (rp.antiCheat()) {
            Inertia.MoveGuard.Verdict verdict = sp.guard.check(d, rp.teleportFactor());
            if (verdict != Inertia.MoveGuard.Verdict.OK) {
                violation(p, sp, rp, from, to, d, verdict);
                if (verdict == Inertia.MoveGuard.Verdict.GROSS) {
                    e.setCancelled(true);
                    p.teleport(from, Vec.ZERO);
                    sp.guard.reset(sp.guard.speed);
                    sp.lastMoveSpeed = 0;
                    return;
                }
            }
        }
        if (shifted) {
            sp.lastMoveSpeed = 0;
            sp.rebase();
            return;
        }
        double v = d * Settings.TPS;                       // пакет — один тик клиента
        double drop = sp.lastMoveSpeed - v;
        sp.lastMoveSpeed = v;
        if (drop > rp.impactThreshold() && rp.impactDamage() > 0 && p.getInstance() != null
            && contactNormal(p.getInstance(), p, to).lengthSquared() > 0) {
            impact(p, sp, drop, rp);
        }
    }

    /** Запас проверки скорости сверх физики сейчас: толчок от блока и (у блоков) ходьба по стенам, блоки/с. */
    private static double allowance(Spacer sp, RealPhysics rp) {
        return rp.pushOff() + (sp.surfaceTicks > 0 ? rp.surfaceWalk() : 0);
    }

    /** Касается ли игрок блоков (для запаса проверки скорости; проба шире, чем у толчка). */
    private static boolean touching(Instance instance, Player p, Pos pos) {
        return contactNormal(instance, p, pos, SURFACE_PROBE).lengthSquared() > 0;
    }

    /**
     * Игрок с модом сдвинулся быстрее, чем позволяет физика. Мягко: запись в лог (не чаще раза в 5 с) и, при OVER,
     * ограничение скорости до допустимой (не чаще раза в {@link #CLAMP_TICKS} тиков). Назад — только GROSS.
     */
    private void violation(Player p, Spacer sp, RealPhysics rp, Pos from, Pos to, double d,
                           Inertia.MoveGuard.Verdict verdict) {
        sp.violations++;
        double allowed = sp.guard.allowed;
        if (ticks - sp.lastViolationLog >= VIOLATION_LOG_TICKS) {
            logger().info("gravity: {} движется быстрее физики ({}): {} блоков за пакет, допустимо ~{} б/с; "
                    + "нарушений: {}", p.getUsername(), verdict == Inertia.MoveGuard.Verdict.GROSS ? "назад" : "лог",
                String.format(java.util.Locale.ROOT, "%.2f", d), String.format(java.util.Locale.ROOT, "%.1f", allowed),
                sp.violations);
            sp.lastViolationLog = ticks;
            sp.violations = 0;
        }
        if (verdict == Inertia.MoveGuard.Verdict.OVER && ticks - sp.lastClamp >= CLAMP_TICKS && d > 0) {
            sp.lastClamp = ticks;
            double speed = Math.min(d * Settings.TPS, allowed);       // блоки/с, по направлению сдвига
            if (rp.maxSpeed() > 0) {
                speed = Math.min(speed, rp.maxSpeed());
            }
            double k = speed / Settings.TPS / d;                     // → блоки/тик
            p.sendPacket(new EntityVelocityPacket(p.getEntityId(),
                new Vec((to.x() - from.x()) * k, (to.y() - from.y()) * k, (to.z() - from.z()) * k)));
        }
    }

    /** Приземлился (клиент сказал «на земле»): удар по вертикальной скорости, если его ещё не посчитали. */
    private void landing(Player p, Spacer sp, RealPhysics rp) {
        double vn = -sp.state.vy;
        if (vn > 0 && ticks - sp.lastImpact > IMPACT_COOLDOWN) {
            impact(p, sp, (1 + rp.restitution()) * vn, rp);
        }
    }

    /** Урон от удара с изменением нормальной скорости dvn (блоки/с). */
    private void impact(Player p, Spacer sp, double dvn, RealPhysics rp) {
        if (dvn <= rp.impactThreshold() || ticks - sp.lastImpact <= IMPACT_COOLDOWN) {
            return;
        }
        sp.lastImpact = ticks;
        double dmg = Contact.impactDamage(dvn, rp.impactThreshold(), rp.impactDamage());
        if (dmg > 0 && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)) {
            p.damage(DamageType.FLY_INTO_WALL, (float) dmg);
        }
    }

    /**
     * Удар о блок за этот тик: сдвинуть рамку игрока на скорость за тик; по каждой оси, где упёрлась, —
     * {@link Contact#collide} (отскок, трение) и урон по |Δv по нормали|.
     */
    private void collide(Player p, Spacer sp, Instance instance, Pos pos, RealPhysics rp) {
        State st = sp.state;
        Vec delta = new Vec(st.vx, st.vy, st.vz).div(Settings.TPS);
        if (delta.isZero()) {
            return;
        }
        PhysicsResult r = sweep(instance, p, pos, delta);
        if (r == null || !r.hasCollision()) {
            return;
        }
        double dvn = 0;
        Vec3d v = st.velocity();
        if (r.collisionX()) {
            Contact.Result c = hit(v, new Vec3d(-Math.signum(delta.x()), 0, 0), rp);
            v = c.velocity();
            dvn = Math.max(dvn, c.deltaVNormal());
        }
        if (r.collisionY()) {
            Contact.Result c = hit(v, new Vec3d(0, -Math.signum(delta.y()), 0), rp);
            v = c.velocity();
            dvn = Math.max(dvn, c.deltaVNormal());
        }
        if (r.collisionZ()) {
            Contact.Result c = hit(v, new Vec3d(0, 0, -Math.signum(delta.z())), rp);
            v = c.velocity();
            dvn = Math.max(dvn, c.deltaVNormal());
        }
        st.setVelocity(v.x(), v.y(), v.z());
        impact(p, sp, dvn, rp);
    }

    /**
     * Удар по одной оси. Медленный удар (упор: ранец или притяжение давят в стену) — без отскока: иначе каждый тик
     * маленький отскок, тяга обратно, снова отскок — игрока трясёт у стены и уводит вбок трением.
     */
    static Contact.Result hit(Vec3d v, Vec3d normal, RealPhysics rp) {
        double vn = -v.dot(normal);
        return Contact.collide(v, normal, vn < REST_SPEED ? 0 : rp.restitution(), rp.friction());
    }

    /** Нормаль от блоков, которых игрок касается (сумма по шести сторонам); ZERO — рядом блоков нет. */
    private static Vec3d contactNormal(Instance instance, Player p, Pos pos) {
        return contactNormal(instance, p, pos, PROBE);
    }

    private static Vec3d contactNormal(Instance instance, Player p, Pos pos, double probe) {
        double nx = 0;
        double ny = 0;
        double nz = 0;
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                Vec d = new Vec(axis == 0 ? sign * probe : 0, axis == 1 ? sign * probe : 0, axis == 2 ? sign * probe : 0);
                PhysicsResult r = sweep(instance, p, pos, d);
                if (r == null) {
                    continue;
                }
                boolean hit = axis == 0 ? r.collisionX() : axis == 1 ? r.collisionY() : r.collisionZ();
                if (hit) {
                    if (axis == 0) {
                        nx -= sign;
                    } else if (axis == 1) {
                        ny -= sign;
                    } else {
                        nz -= sign;
                    }
                }
            }
        }
        return new Vec3d(nx, ny, nz).normalize();
    }

    /** Сдвиг рамки игрока по блокам (null — чанк не загружен или ошибка). */
    private static PhysicsResult sweep(Instance instance, Player p, Pos pos, Vec delta) {
        try {
            return CollisionUtils.handlePhysics(instance, p.getBoundingBox(), pos, delta, null, false);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** player-on-horizon: none — у горизонта дыры внутрь не затягивает: радиальная скорость внутрь гасится. */
    private static void holdAtHorizon(State st, List<BlackHole> holes) {
        BlackHole h = Gravitation.nearest(holes, st.x, st.y, st.z);
        if (h == null) {
            return;
        }
        double r = h.distance(st.x, st.y, st.z);
        if (r > h.horizon() * 1.5 || r == 0) {
            return;
        }
        double ux = (st.x - h.x()) / r;
        double uy = (st.y - h.y()) / r;
        double uz = (st.z - h.z()) / r;
        double vr = st.vx * ux + st.vy * uy + st.vz * uz;
        if (vr < 0) {
            st.setVelocity(st.vx - ux * vr, st.vy - uy * vr, st.vz - uz * vr);
        }
    }

    /** Послать клиенту настоящую скорость (абсолютную, блоков/тик) с поправкой на его затухание и ввод WASD. */
    private static void sendVelocity(Player p, Spacer sp, double[] input) {
        State st = sp.state;
        Vec3d v = Inertia.toClient(st.vx, st.vy, st.vz, input, 1);
        boolean zero = v.lengthSquared() < 1.0e-12;
        if (zero && sp.sentZero) {
            return;                                         // клиент и так стоит
        }
        sp.sentZero = zero;
        p.sendPacket(new EntityVelocityPacket(p.getEntityId(), new Vec(v.x(), v.y(), v.z())));
    }

    /**
     * Гравитация клиента (атрибут) = g блоков/с² вниз: на земле планеты клиент ходит и падает сам. В воздухе её
     * перекрывает наша скорость каждый тик. Меняем атрибут, только когда g заметно сдвинулось.
     */
    private static void setGravity(Player p, Spacer sp, double g) {
        double perTick2 = Units.accelToPerTick(Math.min(g, 400));
        double cur = sp.gravityAttr;
        if (!Double.isNaN(cur) && Math.abs(perTick2 - cur) <= Math.max(2.5e-4, cur * 0.03)
            && (perTick2 == 0) == (cur == 0)) {
            return;
        }
        var inst = p.getAttribute(Attribute.GRAVITY);
        double base = inst.getBaseValue() > 0 ? inst.getBaseValue() : VANILLA_GRAVITY;
        inst.removeModifier(MODIFIER);
        inst.addModifier(perTick2 <= 0 ? ZERO
            : new AttributeModifier(MODIFIER, perTick2 / base - 1, AttributeOperation.ADD_MULTIPLIED_TOTAL));
        sp.gravityAttr = perTick2;
    }

    /** physics.speed-readout: скорость в action bar. */
    private void readout(Player p, Spacer sp, RealPhysics rp) {
        if (rp.speedReadout() && (ticks + p.getEntityId()) % READOUT_TICKS == 0) {
            p.sendActionBar(Component.text(String.format(java.util.Locale.ROOT, "%.1f б/с", sp.state.speed()),
                NamedTextColor.GRAY));
        }
    }

    private static Vec3d look(Pos pos) {
        return Quat.fromYawPitchRoll(pos.yaw(), pos.pitch(), 0).forward();
    }

    private static boolean finite(State s) {
        return Double.isFinite(s.vx) && Double.isFinite(s.vy) && Double.isFinite(s.vz);
    }


    /** Прибавить импульс к скорости клиента (отдача «пустого» взрыва). */
    private static void push(Player p, Floater.Impulse imp) {
        Pos pos = p.getPosition();
        p.sendPacket(new ExplosionPacket(new Vec(pos.x(), pos.y() - FAR_BELOW, pos.z()), 0f, 0,
            new Vec(imp.x(), imp.y(), imp.z()), Particle.BUBBLE, SoundEvent.INTENTIONALLY_EMPTY, WeightedList.of()));
    }
}
