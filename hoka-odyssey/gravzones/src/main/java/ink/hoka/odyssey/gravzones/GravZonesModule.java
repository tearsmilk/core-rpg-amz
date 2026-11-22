package ink.hoka.odyssey.gravzones;

import ink.hoka.odyssey.api.OdysseyModule;
import ink.hoka.odyssey.gravity.VacuumService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.collision.Aerodynamics;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.attribute.AttributeModifier;
import net.minestom.server.entity.attribute.AttributeOperation;
import net.minestom.server.event.entity.EntitySpawnEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.tag.Tag;

/**
 * gravzones — зоны гравитации, выделяемые топориком. В зоне 0.1…10 игроку ставятся свои модификаторы атрибутов
 * (гравитация × g, прыжок, безопасная высота падения), а модулю gravity говорится «здесь не вакуум» (зона главнее
 * вакуумной карты). В зоне больше 10 gravity включает игроку вакуум через {@link VacuumService}, а этот модуль
 * подбрасывает его вверх; невесомость держится, пока игрок в зоне, и ещё linger-seconds после выхода.
 * Пересекающиеся зоны: действует меньшая по объёму, при равном объёме — созданная позже.
 * <p>
 * Предметы: в зоне 0.1…10 модуль ставит им гравитацию зоны, а модулю gravity через {@link VacuumService.ZoneProvider}
 * говорит «здесь NORMAL» — на вакуумной карте gravity такой предмет не держит в невесомости, дыры его не тянут и не
 * поглощают. Вылетел из зоны в вакуум — снова решает gravity.
 */
public final class GravZonesModule extends OdysseyModule {
    /** id модификаторов атрибутов модуля (свои, не пересекаются с hoka:gravity_vacuum модуля gravity). */
    static final Key MODIFIER = Key.key("hoka", "gravzone");
    /** Исходная гравитация предмета до входа в зону (чтобы вернуть). */
    static final Tag<Double> ITEM_BASE = Tag.Double("gravzones_base_gravity");
    /**
     * Через сколько тиков после того, как gravity включил вакуум, толкать вверх: первые тики gravity берёт скорость
     * игрока из замеров позиции и затёр бы толчок.
     */
    static final int LAUNCH_AFTER_VACUUM_TICKS = 5;
    private static final Attribute[] ATTRS = {Attribute.GRAVITY, Attribute.JUMP_STRENGTH, Attribute.SAFE_FALL_DISTANCE,
        Attribute.FALL_DAMAGE_MULTIPLIER};

    private volatile Settings settings;
    private final Zones zones = new Zones();
    private ZoneStore store;
    private boolean loaded;
    private long saveVersion;
    private long now;
    private final Map<UUID, PState> states = new HashMap<>();
    private final Map<UUID, Selection> selections = new HashMap<>();
    private AxeListener axe;
    /** Источник зон для предметов, который отдаём gravity (один объект на включение модуля). */
    private final VacuumService.ZoneProvider zoneProvider = this::itemZones;
    /** Сервис gravity, в котором сейчас стоит zoneProvider (после перезагрузки gravity — другой объект). */
    private VacuumService zoneService;

    /** Что модуль сейчас сделал с игроком. */
    static final class PState {
        boolean known;
        String key;
        String map;
        boolean mods;
        boolean normal;
        boolean vacuum;
        long lingerUntil;
        double pendingLaunch;
        int vacuumTicks;
        long lastJumpLaunch = Long.MIN_VALUE / 2;
        boolean lastJumpKey;
    }

    @Override
    protected void onEnable() {
        settings = Settings.from(config());
        store = new ZoneStore(dataFolder().resolve("zones.json"));
        axe = new AxeListener(this);
        axe.register();
        registerCommand(new HoZoneCommand(this));
        events().addListener(PlayerDisconnectEvent.class, e -> {
            states.remove(e.getPlayer().getUuid());
            selections.remove(e.getPlayer().getUuid());
        });
        // предмет появился в зоне — сразу гравитация зоны (до первого тика физики)
        events().addListener(EntitySpawnEvent.class, e -> {
            if (e.getEntity() instanceof ItemEntity item && loaded && settings.items()) {
                String map = server().mapName(e.getInstance()).orElse(null);
                Pos pos = item.getPosition();
                applyItem(item, map == null ? null : zones.at(map, pos.x(), pos.y(), pos.z()));
            }
        });
        registerZoneProvider(vacuum());
        scheduler().repeat(this::tick, 1, 1);
        scheduler().async(() -> {
            List<Zone> list;
            try {
                list = store.load(w -> logger().warn("{}: {}", store.file(), w));
            } catch (Exception e) {
                logger().error("не прочитать {}: {}", store.file(), e.toString());
                list = List.of();
            }
            List<Zone> got = list;
            scheduler().run(() -> {
                zones.replaceAll(got);
                loaded = true;
                logger().info("gravzones: зон {}, команда /hozone", zones.size());
            });
        });
    }

    @Override
    protected void onDisable() {
        VacuumService vs = vacuum();
        if (zoneService != null) {
            zoneService.removeZoneProvider(zoneProvider);
            zoneService = null;
        }
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            PState st = states.get(p.getUuid());
            if (st == null) {
                continue;
            }
            clearMods(p);
            if ((st.normal || st.vacuum) && vs != null) {
                vs.override(p, VacuumService.Mode.MAP);
            }
        }
        states.clear();
        selections.clear();
        restoreAllItems();
    }

    private static void restoreAllItems() {
        for (Instance inst : MinecraftServer.getInstanceManager().getInstances()) {
            for (Entity e : inst.getEntities()) {
                if (e instanceof ItemEntity && e.hasTag(ITEM_BASE)) {
                    restoreItem(e);
                }
            }
        }
    }

    /** Поставить zoneProvider в сервис gravity, если он там ещё не стоит. */
    private void registerZoneProvider(VacuumService vs) {
        if (vs != null && vs != zoneService) {
            vs.zoneProvider(zoneProvider);
            zoneService = vs;
        }
    }

    /**
     * Зоны карты для gravity: null — зон нет (или предметы зонами не управляются, items: false). Лямбда — на карту
     * за проход gravity, сам опрос точки без аллокаций.
     */
    private VacuumService.ZoneLookup itemZones(Instance inst) {
        if (!loaded || !settings.items()) {
            return null;
        }
        String map = server().mapName(inst).orElse(null);
        List<Zone> list = map == null ? List.of() : zones.on(map);
        return list.isEmpty() ? null : (x, y, z) -> itemMode(Zones.at(list, x, y, z));
    }

    /** Режим для предмета в зоне z (null — вне зон). */
    static VacuumService.Mode itemMode(Zone z) {
        if (z == null) {
            return VacuumService.Mode.MAP;
        }
        return z.vacuum() ? VacuumService.Mode.VACUUM : VacuumService.Mode.NORMAL;
    }

    // ───────────────────────────── доступ для команд/топорика ─────────────────────────────

    Settings settings() {
        return settings;
    }

    Zones zones() {
        return zones;
    }

    boolean loaded() {
        return loaded;
    }

    long now() {
        return now;
    }

    AxeListener axe() {
        return axe;
    }

    Selection selection(Player p) {
        return selections.computeIfAbsent(p.getUuid(), k -> new Selection());
    }

    /** Имя карты игрока (или null, если мир не из maps/). */
    String mapOf(Player p) {
        Instance inst = p.getInstance();
        return inst == null ? null : server().mapName(inst).orElse(null);
    }

    /** Зона, в которой игрок сейчас. */
    Zone zoneOf(Player p) {
        String map = mapOf(p);
        Pos pos = p.getPosition();
        return map == null ? null : zones.at(map, pos.x(), pos.y(), pos.z());
    }

    void reloadSettings() {
        config().reload();
        settings = Settings.from(config());
        for (PState st : states.values()) {
            st.known = false;                           // пересчитать атрибуты с новыми настройками (без выброса)
        }
        if (!settings.items()) {
            restoreAllItems();                          // items: false — вернуть предметам их гравитацию
        }
    }

    /** Сохранить все зоны в zones.json в отдельном потоке. done (в тике) получает null или текст ошибки. */
    void save(Consumer<String> done) {
        long v = ++saveVersion;
        String json = ZoneStore.toJson(zones.all());
        scheduler().async(() -> {
            String err = null;
            try {
                store.save(v, json);
            } catch (Exception e) {
                err = e.toString();
                logger().warn("не записать {}: {}", store.file(), err);
            }
            String e2 = err;
            if (done != null) {
                scheduler().run(() -> done.accept(e2));
            }
        });
    }

    /** Сервис вакуума модуля gravity — каждый раз заново (после перезагрузки gravity это другой объект). */
    VacuumService vacuum() {
        return services().get(VacuumService.class).orElse(null);
    }

    // ───────────────────────────── тик ─────────────────────────────

    private void tick() {
        now++;
        if (!loaded) {
            return;
        }
        Settings s = settings;
        VacuumService vs = vacuum();
        registerZoneProvider(vs);                       // gravity перезагрузили отдельно — встать в новый сервис
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            PState st = states.computeIfAbsent(p.getUuid(), k -> new PState());
            String map = mapOf(p);
            Pos pos = p.getPosition();
            Zone z = map == null ? null : zones.at(map, pos.x(), pos.y(), pos.z());
            String key = z == null ? null : z.map() + '\0' + z.name() + '\0' + z.g();
            if (!st.known || !Objects.equals(key, st.key) || !Objects.equals(map, st.map)) {
                boolean mapChanged = st.known && !Objects.equals(map, st.map);
                transition(p, st, z, st.known && !mapChanged, mapChanged, s, vs);
                st.known = true;
                st.key = key;
                st.map = map;
            }
            if (st.vacuum && vs != null) {
                vacuumTick(p, st, z, s, vs);
            }
        }
        if (s.items() && now % 5 == 0) {
            itemsTick();
        }
        if (now % s.particlePeriod() == 0) {
            axe.drawAll();
        }
    }

    /** Игрок перешёл в зону z (null — вне зон). launch — можно подбросить (настоящий вход, а не включение модуля). */
    private void transition(Player p, PState st, Zone z, boolean launch, boolean mapChanged, Settings s, VacuumService vs) {
        if (z != null && !z.vacuum()) {
            applyMods(p, z.g(), s);
            st.mods = true;
            st.vacuum = false;
            st.pendingLaunch = 0;
            if (vs != null) {
                vs.override(p, VacuumService.Mode.NORMAL);  // зона главнее вакуумной карты
            }
            st.normal = true;
            return;
        }
        if (st.mods) {
            clearMods(p);
            st.mods = false;
        }
        if (z != null) {                                // вакуумная зона
            if (!st.vacuum) {
                st.vacuumTicks = 0;
            }
            if (vs != null) {
                vs.override(p, VacuumService.Mode.VACUUM);
            }
            st.vacuum = true;
            st.normal = false;
            st.lingerUntil = Long.MAX_VALUE;
            st.pendingLaunch = launch ? s.launch(z.g()) / Physics.TPS : 0;
            return;
        }
        if (st.normal) {                                // вышел из обычной зоны — дальше решает карта
            if (vs != null) {
                vs.override(p, VacuumService.Mode.MAP);
            }
            st.normal = false;
        }
        if (st.vacuum) {
            st.lingerUntil = mapChanged ? now : now + s.lingerTicks();
            st.pendingLaunch = 0;
        }
    }

    private void vacuumTick(Player p, PState st, Zone z, Settings s, VacuumService vs) {
        boolean inZone = z != null && z.vacuum();
        if (!inZone && now >= st.lingerUntil) {
            vs.override(p, VacuumService.Mode.MAP);
            st.vacuum = false;
            st.pendingLaunch = 0;
            return;
        }
        if (!vs.inVacuum(p)) {
            st.vacuumTicks = 0;
            return;
        }
        st.vacuumTicks++;
        if (st.pendingLaunch > 0 && st.vacuumTicks >= LAUNCH_AFTER_VACUUM_TICKS) {
            vs.push(p, 0, st.pendingLaunch, 0);
            st.pendingLaunch = 0;
            st.lastJumpLaunch = now;
        }
        boolean jump = p.inputs().jump();
        if (inZone && s.jumpLaunch() && jump && !st.lastJumpKey && p.isOnGround()
            && now - st.lastJumpLaunch >= s.jumpCooldownTicks()) {
            vs.push(p, 0, s.launch(z.g()) / Physics.TPS, 0);
            st.lastJumpLaunch = now;
        }
        st.lastJumpKey = jump;
    }

    // ───────────────────────────── атрибуты ─────────────────────────────

    private static void applyMods(Player p, double g, Settings s) {
        double jf = Physics.jumpFactor(g, s.weakExponent(), s.strongExponent());
        mod(p, Attribute.GRAVITY, Physics.gravityAmount(g));
        mod(p, Attribute.JUMP_STRENGTH, jf - 1);
        mod(p, Attribute.SAFE_FALL_DISTANCE,
            s.scaleSafeFall() ? Physics.safeFallFactor(g, s.weakExponent(), s.strongExponent()) - 1 : 0);
        mod(p, Attribute.FALL_DAMAGE_MULTIPLIER, s.fallDamage() - 1);
    }

    /** Модификатор модуля «× (1 + amount)»; amount = 0 — снять. Повтор с тем же значением ничего не шлёт. */
    private static void mod(Player p, Attribute a, double amount) {
        var inst = p.getAttribute(a);
        if (Math.abs(amount) < 1e-9) {
            inst.removeModifier(MODIFIER);
            return;
        }
        inst.addModifier(new AttributeModifier(MODIFIER, amount, AttributeOperation.ADD_MULTIPLIED_TOTAL));
    }

    private static void clearMods(Player p) {
        for (Attribute a : ATTRS) {
            p.getAttribute(a).removeModifier(MODIFIER);
        }
    }

    // ───────────────────────────── предметы ─────────────────────────────

    private void itemsTick() {
        for (Instance inst : MinecraftServer.getInstanceManager().getInstances()) {
            String map = server().mapName(inst).orElse(null);
            boolean has = map != null && zones.hasMap(map);
            for (Entity e : inst.getEntities()) {
                if (!(e instanceof ItemEntity)) {
                    continue;
                }
                Zone z = null;
                if (has) {
                    Pos pos = e.getPosition();
                    z = zones.at(map, pos.x(), pos.y(), pos.z());
                }
                applyItem(e, z);
            }
        }
    }

    /**
     * Предмет в зоне z (null — вне зон): в зоне 0.1…10 — гравитация зоны (исходная запоминается тегом), иначе вернуть
     * исходную. Флаг noGravity не трогаем: им управляет gravity (в зоне NORMAL он его снимает сам).
     */
    private static void applyItem(Entity e, Zone z) {
        if (z != null && !z.vacuum()) {
            Double base = e.getTag(ITEM_BASE);
            if (base == null) {
                base = e.getAerodynamics().gravity();
                e.setTag(ITEM_BASE, base);
            }
            double want = base * z.g();
            Aerodynamics a = e.getAerodynamics();
            if (a.gravity() != want) {
                e.setAerodynamics(withGravity(a, want));
            }
        } else if (e.hasTag(ITEM_BASE)) {
            restoreItem(e);
        }
    }

    /** Не withGravity(double): у record перегрузка с fastutil, которого нет на classpath компиляции. */
    private static Aerodynamics withGravity(Aerodynamics a, double gravity) {
        return new Aerodynamics(gravity, a.horizontalAirResistance(), a.verticalAirResistance());
    }

    private static void restoreItem(Entity e) {
        Double base = e.getTag(ITEM_BASE);
        if (base != null) {
            e.setAerodynamics(withGravity(e.getAerodynamics(), base));
        }
        e.removeTag(ITEM_BASE);
    }
}
