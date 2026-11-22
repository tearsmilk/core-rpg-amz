package ink.hoka.odyssey.spacesky;

import ink.hoka.odyssey.api.OdysseyModule;
import ink.hoka.odyssey.respack.ResourcePackApi;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Weather;

/**
 * Космическое небо: в общий ресурспак (модуль respack) кладётся шейдер неба {@code minecraft:shaders/core/sky.*}.
 *
 * <p>Профили неба ({@link Profiles}, раздел profiles в config.yml): у каждого свой тип мира «hoka:&lt;id&gt;»
 * ({@link SpaceDimension}; регистрируется до старта сервера, карта выбирает его ключом {@code dimension} в map.yml —
 * /hosky world), шейдер узнаёт профиль по МЕТКЕ в цвете тумана и берёт параметры из spacesky:profiles.glsl.
 * Галактики — космос (области zones: Млечный путь, туманности, скопления — spacesky:zones.glsl), планеты — атмосфера,
 * солнце, облака, луна. Профиль по умолчанию — space (hoka:space, как до профилей).
 *
 * <p>Режим ({@code everywhere}, файл spacesky:mode.glsl, см. {@link SkyMode}): небо space во всех мирах без метки или
 * ванильное небо там. Сервер в игре ничего не считает — модуль только отдаёт файлы в пак (и держит ясную погоду в
 * мирах профилей).
 *
 * <p>Чёрные дыры ({@link Holes}) — из модуля gravity (softdepends, сервис BlackHoles): файл spacesky:holes.glsl, каждая
 * — в небе профиля своей карты. После /hograv hole add|remove|reload и смены типа мира карт пак пересобирается сам
 * (с задержкой, и только если дыры изменились).
 */
public final class SpaceSkyModule extends OdysseyModule {
    private FileSystem jarFs;
    private Path root;
    private volatile List<Zones.Zone> zones = List.of();
    /** Имена карт для подсказок /hosky world — читаются с диска в async, не в тике и не при разборе пакета. */
    private volatile SortedSet<String> mapNames = new TreeSet<>();
    /** Тип мира карт (dimension из map.yml) — тоже из async, для /hosky profiles и профилей дыр. */
    private volatile Map<String, String> mapDims = Map.of();
    /** Тип мира карт для дыр — какой на деле у их миров (см. {@link #effectiveDims}). */
    private volatile Map<String, String> mapDimsEffective = Map.of();
    private volatile List<Holes.Hole> holes = List.of();
    /** Текст spacesky:holes.glsl последней сборки — пересобирать пак, только если он изменился. */
    private volatile String holesGlsl;
    /** Отписка от изменений дыр gravity (null — gravity нет). */
    private Runnable holesUnsubscribe;
    private boolean holesPending;
    /** Идёт сборка пака (syncPack ещё не дошёл до respack) — в тике. */
    private boolean syncing;
    /** Пока шла сборка, дыры или типы миров могли измениться — проверить ещё раз после неё (в тике). */
    private boolean holesAgain;
    /** Профили, зарегистрированные при загрузке (номера зашиты в метки типов миров); параметры — из config.yml. */
    private volatile SpaceDimension.Registration registration = new SpaceDimension.Registration(List.of(), Map.of());
    private volatile List<Profiles.Profile> profiles = List.of();
    /** Профили из config.yml, которых нет среди зарегистрированных (появятся после перезапуска). */
    private volatile List<String> pendingProfiles = List.of();

    @Override
    protected void onLoad() {
        // реестры Minestom — только до MinecraftServer.start; при горячей загрузке — предупреждение в лог
        List<String> errors = new ArrayList<>();
        List<Profiles.Profile> wanted = Profiles.read(config(), errors);
        for (String e : errors) {
            logger().warn("{}", e);
        }
        registration = SpaceDimension.register(logger(), wanted);
        profiles = registration.profiles();
    }

    @Override
    protected void onEnable() {
        root = jarRoot();
        if (root == null) {
            throw new IllegalStateException("не открыть jar модуля — шейдер неба не попадёт в пак");
        }
        registerCommand(new HoSkyCommand(this));
        try {
            holesUnsubscribe = GravityHoles.subscribe(services(), this::holesChanged);
        } catch (LinkageError e) {
            logger().debug("модуля gravity нет — чёрных дыр в небе не будет");
        }
        syncPack();
        refreshMapNames();
        // в мирах профилей дождь и гроза перекрашивают небо и туман поверх таймлайна — метка теряется,
        // небо профиля пропадает, сквозь дождь видно ванильное солнце; поэтому погода там всегда ясная
        scheduler().repeat(this::clearProfileWeather, 20, 20);
        scheduler().repeat(this::refreshMapNames, 200, 200);
    }

    SortedSet<String> cachedMapNames() {
        return mapNames;
    }

    Map<String, String> cachedMapDims() {
        return mapDims;
    }

    /** Тип мира карт, как его на деле видит клиент (с поправкой на ядро без патча и загруженные карты). */
    Map<String, String> cachedMapDimsEffective() {
        return mapDimsEffective;
    }

    void refreshMapNames() {
        scheduler().async(() -> {
            try {
                SortedSet<String> names = new TreeSet<>(server().mapNames());
                Map<String, String> dims = MapDimensions.read(MapDimensions.MAPS, names);
                Map<String, String> eff = effectiveDims(dims);
                boolean changed = !eff.equals(mapDimsEffective);
                mapNames = names;
                mapDims = Map.copyOf(dims);
                mapDimsEffective = Map.copyOf(eff);
                if (changed && !holes.isEmpty()) {
                    scheduler().run(this::holesChanged);               // профиль дыры — по типу мира её карты
                }
            } catch (RuntimeException e) {
                logger().debug("список карт не прочитан: {}", e.toString());
            }
        });
    }

    /** Профили (зарегистрированные, с номерами; параметры — из config.yml последней сборки). */
    List<Profiles.Profile> profiles() {
        return profiles;
    }

    SpaceDimension.Registration registration() {
        return registration;
    }

    List<String> pendingProfiles() {
        return pendingProfiles;
    }

    private void clearProfileWeather() {
        Set<Key> keys = new HashSet<>();
        for (Profiles.Profile p : profiles) {
            keys.add(Key.key(p.key()));
        }
        for (Instance i : MinecraftServer.getInstanceManager().getInstances()) {
            if (keys.contains(i.getDimensionType().key()) && i.getWeather().isRaining()) {
                i.setWeather(Weather.CLEAR, 1);
            }
        }
    }

    /**
     * Дыры gravity или типы миров карт могли измениться (вызов в тике): через 2 с пересобрать пак, если дыры другие.
     * Пока идёт сборка пака — проверка откладывается до её конца (сравнивать не с чем, и пак собрался бы дважды).
     * Пересборка ради дыр config.yml не перечитывает: недоделанная правка конфига не попадёт в небо раньше /hosky reload.
     */
    private void holesChanged() {
        if (syncing) {
            holesAgain = true;
            return;
        }
        if (holesPending) {
            return;
        }
        holesPending = true;
        scheduler().later(() -> {
            holesPending = false;
            if (syncing) {
                holesAgain = true;
                return;
            }
            List<Holes.Hole> raw = readHoles();
            Map<String, Integer> idx = Profiles.indexes(profiles);
            scheduler().async(() -> {
                String text = Holes.glsl(Holes.withProfiles(raw, holeMapDims(raw), idx));
                scheduler().run(() -> {
                    if (syncing) {
                        holesAgain = true;
                    } else if (!text.equals(holesGlsl)) {
                        syncPack(false).exceptionally(e -> {
                            logger().warn("небо с новыми чёрными дырами не собрано: {}", e.toString());
                            return List.of();
                        });
                    }
                });
            });
        }, 40);
    }

    /** Тип мира карт дыр — с диска, с поправкой на то, что применило ядро (вызывать из async). */
    private Map<String, String> holeMapDims(List<Holes.Hole> hs) {
        Set<String> maps = new TreeSet<>();
        for (Holes.Hole h : hs) {
            if (h.map() != null) {
                maps.add(h.map());
            }
        }
        return effectiveDims(MapDimensions.read(MapDimensions.MAPS, maps));
    }

    /**
     * Тип мира карт, как его увидит клиент. Ядро без патча docs/core-dimension.md ключ dimension не читает — тогда все
     * карты overworld и дыры уходят в профиль space (иначе на боевом сервере с everywhere: true дыра карты с
     * dimension: hoka:… пропала бы с неба). Ядро с патчем (видно по Maps.dimensionKeys или по миру в типе мира
     * профиля): у загруженной карты — тип мира её инстанса (map.yml мог измениться после загрузки), у остальных — map.yml.
     */
    private Map<String, String> effectiveDims(Map<String, String> file) {
        Map<String, String> loaded = new LinkedHashMap<>();
        for (Instance in : MinecraftServer.getInstanceManager().getInstances()) {
            String real = in.getDimensionType().key().asString();
            server().mapName(in).ifPresent(n -> loaded.put(n, real));
        }
        return effectiveDims(file, coreAppliesDimension(), loaded);
    }

    /**
     * Чистая часть {@link #effectiveDims(Map)}: file — карта → dimension из map.yml, coreApplies — ядро с патчем (по
     * {@link #coreAppliesDimension()}), loaded — загруженные карты → тип мира их инстанса. Патч виден и по миру,
     * загруженному с типом мира профиля. Без патча — все карты {@link MapDimensions#DEFAULT}.
     */
    static Map<String, String> effectiveDims(Map<String, String> file, boolean coreApplies, Map<String, String> loaded) {
        Map<String, String> out = new LinkedHashMap<>(file);
        boolean patched = coreApplies || loaded.values().stream().anyMatch(d -> Profiles.profileOf(d) != null);
        if (!patched) {
            out.replaceAll((k, v) -> MapDimensions.DEFAULT);
            return out;
        }
        loaded.forEach((map, real) -> {
            if (file.containsKey(map)) {
                out.put(map, real);
            }
        });
        return out;
    }

    /** Класс и метод, которые добавляет патч ядра docs/core-dimension.patch (по ним модуль узнаёт патч). */
    static final String CORE_MAPS_CLASS = "ink.hoka.odyssey.server.maps.Maps";
    static final String CORE_MAPS_METHOD = "dimensionKeys";

    private static volatile Boolean coreDimension;

    /** Ядро с патчем docs/core-dimension.md (в нём у карт ядра появился Maps.dimensionKeys())? */
    static boolean coreAppliesDimension() {
        Boolean b = coreDimension;
        if (b == null) {
            b = hasMethod(CORE_MAPS_CLASS, CORE_MAPS_METHOD);
            coreDimension = b;
        }
        return b;
    }

    /** Есть ли в классе cls (загрузчиком модуля) объявленный метод method. */
    static boolean hasMethod(String cls, String method) {
        try {
            for (java.lang.reflect.Method m : Class.forName(cls, false, SpaceSkyModule.class.getClassLoader())
                .getDeclaredMethods()) {
                if (m.getName().equals(method)) {
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return false;
        }
    }

    /** Дыры gravity (пусто, если модуля нет). */
    private List<Holes.Hole> readHoles() {
        try {
            return GravityHoles.read(services());
        } catch (LinkageError | RuntimeException e) {
            return List.of();
        }
    }

    List<Holes.Hole> holes() {
        return holes;
    }

    @Override
    protected void onDisable() {
        if (holesUnsubscribe != null) {
            holesUnsubscribe.run();
            holesUnsubscribe = null;
        }
        services().get(ResourcePackApi.class).ifPresent(p -> {
            p.clear(id());
            p.rebuild();
        });
        if (jarFs != null) {
            try {
                jarFs.close();
            } catch (IOException ignored) {
                // уже закрыт
            }
            jarFs = null;
        }
    }

    List<Zones.Zone> zones() {
        return zones;
    }

    /**
     * Профили для пака: зарегистрированные номера и виды, параметры — из config.yml сейчас (новые профили — только
     * после перезапуска, их id — в pending). Ошибки — в errors.
     */
    static List<Profiles.Profile> current(SpaceDimension.Registration reg, List<Profiles.Profile> fromConfig,
                                          List<String> pending, List<String> errors) {
        Map<String, Profiles.Profile> cfg = new LinkedHashMap<>();
        for (Profiles.Profile p : fromConfig) {
            cfg.put(p.id(), p);
        }
        List<Profiles.Profile> out = new ArrayList<>();
        for (Profiles.Profile r : reg.profiles()) {
            Profiles.Profile c = cfg.remove(r.id());
            if (c == null) {
                out.add(r);                                            // убран из config.yml — тип мира ещё есть
                continue;
            }
            if (c.kind() != r.kind()) {
                errors.add("profiles." + r.id() + ": вид " + c.kind().id + " — после перезапуска сервера (сейчас " + r.kind().id + ")");
                c = c.withKind(r.kind());
            }
            // облака планеты — в типе мира и таймлайне (реестр после старта не меняется, шейдер их цвет не трогает)
            if (r.planet() && (c.clouds() != r.clouds() || !c.cloudColor().equals(r.cloudColor()))) {
                errors.add("profiles." + r.id() + ": clouds/cloud-color — после перезапуска сервера (сейчас clouds: "
                    + r.clouds() + ", cloud-color: " + r.cloudColor().hex() + ")");
            }
            out.add(c.withIndex(r.index()));
        }
        for (String id : cfg.keySet()) {
            pending.add(id);
            errors.add("profiles." + id + ": новый профиль — тип мира hoka:" + id + " появится после перезапуска сервера");
        }
        return out;
    }

    /** {@link #syncPack(boolean)} с перечитыванием config.yml (запуск, /hosky reload|add|remove). */
    CompletableFuture<List<String>> syncPack() {
        return syncPack(true);
    }

    /**
     * Отдать respack файлы неба, профили, области и дыры, пересобрать пак (вызов в тике). reload — сначала перечитать
     * config.yml (иначе — как при прошлой сборке: так пересобирается пак ради дыр). Будущее — с ошибками конфига
     * (пусто — всё хорошо); завершается, когда respack пересобрал пак; битый config.yml — будущее с ошибкой.
     */
    CompletableFuture<List<String>> syncPack(boolean reload) {
        if (reload) {
            try {
                config().reload();
            } catch (RuntimeException e) {
                logger().warn("config.yml не прочитан — небо не пересобрано: {}", e.getMessage());
                return CompletableFuture.failedFuture(e);
            }
        }
        syncing = true;
        CompletableFuture<List<String>> out;
        try {
            out = buildPack();
        } catch (RuntimeException e) {
            logger().warn("небо не пересобрано: {}", e.toString());
            out = CompletableFuture.failedFuture(e);
        }
        out.whenComplete((v, err) -> {
            try {
                scheduler().run(() -> {
                    syncing = false;
                    if (holesAgain) {
                        holesAgain = false;
                        holesChanged();
                    }
                });
            } catch (IllegalStateException off) {
                // модуль выключен
            }
        });
        return out;
    }

    private CompletableFuture<List<String>> buildPack() {
        List<String> errors = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        List<Profiles.Profile> ps = current(registration, Profiles.read(config(), errors), pending, errors);
        profiles = List.copyOf(ps);
        pendingProfiles = List.copyOf(pending);
        Map<String, Integer> idx = Profiles.indexes(ps);
        List<String> zoneErrors = new ArrayList<>();
        List<Zones.Zone> zs = Zones.read(config(), zoneErrors);
        String zonesText = Zones.glsl(zs, idx, zoneErrors);
        for (String e : zoneErrors) {
            errors.add("zones: " + e);
        }
        for (String e : errors) {
            logger().warn("{}", e);
        }
        zones = List.copyOf(zs);
        List<Holes.Hole> hs = readHoles();
        if (hs.size() > Holes.MAX) {
            logger().warn("чёрных дыр {} — в небе только первые {}", hs.size(), Holes.MAX);
        }
        boolean everywhere = config().getBoolean("everywhere", true);
        boolean sun = config().getBoolean("hide-sun", true);
        boolean moon = config().getBoolean("hide-moon", true);
        List<String> parts = SkyMode.parts(everywhere, sun, moon, config().getBoolean("hide-clouds", true));
        byte[] zonesGlsl = zonesText.getBytes(StandardCharsets.UTF_8);
        byte[] modeGlsl = SkyMode.glsl(everywhere, sun, moon).getBytes(StandardCharsets.UTF_8);
        byte[] profilesGlsl = Profiles.glsl(ps).getBytes(StandardCharsets.UTF_8);
        Path r = root;
        CompletableFuture<List<String>> done = new CompletableFuture<>();
        scheduler().async(() -> {
            Map<String, byte[]> files = new TreeMap<>();
            List<Holes.Hole> withProf;
            String holesText;
            try {
                for (String part : parts) {
                    files.putAll(collect(r.resolve(part)));
                }
                withProf = Holes.withProfiles(hs, holeMapDims(hs), idx);
                holesText = Holes.glsl(withProf);
            } catch (IOException | RuntimeException e) {
                logger().error("файлы неба не прочитаны: {}", e.toString());
                done.completeExceptionally(e);
                return;
            }
            files.put(Zones.PATH, zonesGlsl);
            files.put(SkyMode.PATH, modeGlsl);
            files.put(Profiles.PATH, profilesGlsl);
            files.put(Holes.PATH, holesText.getBytes(StandardCharsets.UTF_8));
            scheduler().run(() -> {
                holes = List.copyOf(withProf.subList(0, Math.min(withProf.size(), Holes.MAX)));
                holesGlsl = holesText;
                try {
                    ResourcePackApi pack = services().require(ResourcePackApi.class);
                    pack.clear(id());
                    files.forEach((path, data) -> pack.put(id(), path, data));
                    logger().info("космическое небо: {}, профилей {}, файлов в паке {}, областей {}, чёрных дыр {}",
                        everywhere ? "во всех мирах" : "только в мирах профилей", ps.size(), files.size(), zs.size(),
                        holes.size());
                    pack.rebuild().whenComplete((v, err) -> {
                        if (err != null) {
                            done.completeExceptionally(err);
                        } else {
                            done.complete(errors);
                        }
                    });
                } catch (RuntimeException e) {
                    logger().error("файлы неба не переданы в respack: {}", e.toString());
                    done.completeExceptionally(e);
                }
            });
        });
        return done;
    }

    /** Корень jar модуля как файловая система (или папка ресурсов при запуске из IDE). */
    private Path jarRoot() {
        try {
            CodeSource cs = getClass().getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            Path jar = Path.of(cs.getLocation().toURI());
            if (Files.isDirectory(jar)) {
                return jar;
            }
            jarFs = FileSystems.newFileSystem(URI.create("jar:" + jar.toUri()), Map.of());
            return jarFs.getPath("/");
        } catch (Exception e) {
            logger().error("не открыть jar модуля: {}", e.toString());
            return null;
        }
    }

    /** Файлы под root: путь в zip («assets/…») → содержимое. */
    static Map<String, byte[]> collect(Path root) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        for (Path f : files) {
            out.put(root.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
        }
        return out;
    }
}
