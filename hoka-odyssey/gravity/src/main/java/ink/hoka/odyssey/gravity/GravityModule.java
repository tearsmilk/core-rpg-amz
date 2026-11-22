package ink.hoka.odyssey.gravity;

import ink.hoka.odyssey.api.ModuleConfig;
import ink.hoka.odyssey.api.OdysseyModule;
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
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.attribute.AttributeModifier;
import net.minestom.server.entity.attribute.AttributeOperation;
import net.minestom.server.entity.vehicle.PlayerInputs;
import net.minestom.server.event.entity.EntitySpawnEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.Instance;
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
    private static final double PLAYER_CENTER = 0.9;
    private static final double ITEM_CENTER = 0.125;
    /** На сколько тиков вперёд (сверх items.update-ticks) проверять, что чанк на пути предмета загружен. */
    private static final int ITEM_LOOKAHEAD = 2;

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
    private final VacuumOverrides overrides = new VacuumOverrides(floaters);
    /** Чёрные дыры для других модулей (spacesky рисует их в небе) — сервис {@link BlackHoles}. */
    private final BlackHolesService blackHoles = new BlackHolesService(() -> settings, w -> logger().warn(w));

    @Override
    protected void onEnable() {
        settings = Settings.from(config());
        services().register(VacuumService.class, overrides);
        services().register(BlackHoles.class, blackHoles);
        registerCommand(new HoGravCommand(this));
        // PlayerSpawnEvent срабатывает и при входе, и при каждой смене карты (setInstance)
        events().addListener(PlayerSpawnEvent.class, e -> refresh(e.getPlayer(), e.getInstance(), true));
        events().addListener(PlayerDisconnectEvent.class, e -> {
            floaters.remove(e.getPlayer().getUuid());
            spawnCooldown.remove(e.getPlayer().getUuid());
            overrides.forget(e.getPlayer().getUuid());
        });
        // предмет появился на карте с вакуумом — сразу без гравитации (до первого тика физики, иначе он успеет
        // набрать скорость падения)
        events().addListener(EntitySpawnEvent.class, e -> {
            if (e.getEntity() instanceof ItemEntity item && settings.space().items() && inVacuum(e.getInstance())) {
                Pos pos = item.getPosition();
                if (VacuumService.itemInVacuum(true,
                    VacuumOverrides.modeAt(overrides.zones(e.getInstance()), pos.x(), pos.y(), pos.z()))) {
                    capture(item);
                }
            }
        });
        scheduler().repeat(this::tick, 1, 1);
        refreshAll();
        loadMapSpawns();
        logger().info("gravity: вакуум на картах {}, чёрных дыр: {}, команда /hograv", settings.maps(), holeCount());
    }

    @Override
    protected void onDisable() {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (floaters.containsKey(p.getUuid())) {
                clearAttributes(p);
            }
        }
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
        refreshAll();
        loadMapSpawns();
        blackHoles.changed();
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
            applyAttributes(p);
        } else if (f != null) {
            floaters.remove(p.getUuid());
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
        ticks++;
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
            List<BlackHole> holes = s.space().holes(mapOf(instance).orElse(""));
            if (!flying && horizon(p, f, holes, pos)) {
                continue;
            }
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
            List<BlackHole> holes = sp.holes(mapOf(instance).orElse(""));
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
                ItemState st = capture(item);
                double cy = pos.y() + ITEM_CENTER;
                BlackHole h = Gravitation.nearest(holes, pos.x(), cy, pos.z());
                if (h != null && h.distance(pos.x(), cy, pos.z()) <= h.horizon()) {
                    absorbed.add(item);
                    continue;
                }
                Vec v = item.getVelocity().div(Settings.TPS);
                int dt = (int) Math.min(ITEM_MAX_SILENCE, Math.max(1, ticks - st.updated));
                double[] next = Gravitation.itemVelocity(new double[] {v.x(), v.y(), v.z()}, h, pos.x(), cy, pos.z(),
                    sp.falloff(), sp.minPull(), sp.itemKeep(), dt);
                Vec nv = new Vec(next[0], next[1], next[2]);
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

    /** Взять предмет в вакуум (повторно — ничего не меняет). */
    private ItemState capture(ItemEntity item) {
        return items.computeIfAbsent(item, it -> {
            Aerodynamics a = it.getAerodynamics();
            it.setNoGravity(true);
            // затухание и притяжение считаем сами, сервер скорость между нашими пересчётами не меняет
            it.setAerodynamics(new Aerodynamics(a.gravity(), 1.0, a.verticalAirResistance()));
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

    /** Прибавить импульс к скорости клиента (отдача «пустого» взрыва). */
    private static void push(Player p, Floater.Impulse imp) {
        Pos pos = p.getPosition();
        p.sendPacket(new ExplosionPacket(new Vec(pos.x(), pos.y() - FAR_BELOW, pos.z()), 0f, 0,
            new Vec(imp.x(), imp.y(), imp.z()), Particle.BUBBLE, SoundEvent.INTENTIONALLY_EMPTY, WeightedList.of()));
    }
}
