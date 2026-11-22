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
    private volatile List<Holes.Hole> holes = List.of();
    /** Текст spacesky:holes.glsl последней сборки — пересобирать пак, только если он изменился. */
    private volatile String holesGlsl;
    /** Отписка от изменений дыр gravity (null — gravity нет). */
    private Runnable holesUnsubscribe;
    private boolean holesPending;
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

    void refreshMapNames() {
        scheduler().async(() -> {
            try {
                SortedSet<String> names = new TreeSet<>(server().mapNames());
                Map<String, String> dims = MapDimensions.read(MapDimensions.MAPS, names);
                boolean changed = !dims.equals(mapDims);
                mapNames = names;
                mapDims = Map.copyOf(dims);
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

    /** Дыры gravity или типы миров карт могли измениться (вызов в тике): через 2 с пересобрать пак, если дыры другие. */
    private void holesChanged() {
        if (holesPending) {
            return;
        }
        holesPending = true;
        scheduler().later(() -> {
            holesPending = false;
            List<Holes.Hole> raw = readHoles();
            Map<String, Integer> idx = Profiles.indexes(profiles);
            scheduler().async(() -> {
                String text = Holes.glsl(Holes.withProfiles(raw, holeMapDims(raw), idx));
                if (!text.equals(holesGlsl)) {
                    scheduler().run(() -> syncPack().exceptionally(e -> {
                        logger().warn("небо с новыми чёрными дырами не собрано: {}", e.toString());
                        return List.of();
                    }));
                }
            });
        }, 40);
    }

    /** Тип мира карт дыр — с диска (вызывать из async). */
    private static Map<String, String> holeMapDims(List<Holes.Hole> hs) {
        Set<String> maps = new TreeSet<>();
        for (Holes.Hole h : hs) {
            if (h.map() != null) {
                maps.add(h.map());
            }
        }
        return MapDimensions.read(MapDimensions.MAPS, maps);
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
            out.add(c.withIndex(r.index()));
        }
        for (String id : cfg.keySet()) {
            pending.add(id);
            errors.add("profiles." + id + ": новый профиль — тип мира hoka:" + id + " появится после перезапуска сервера");
        }
        return out;
    }

    /**
     * Перечитать config.yml, отдать respack файлы неба, профили, области и дыры, пересобрать пак. Будущее — с ошибками
     * конфига (пусто — всё хорошо); завершается, когда respack пересобрал пак.
     */
    CompletableFuture<List<String>> syncPack() {
        config().reload();
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
