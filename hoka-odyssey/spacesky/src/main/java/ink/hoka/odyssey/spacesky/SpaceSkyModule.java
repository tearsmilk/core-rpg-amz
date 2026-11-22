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
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Weather;

/**
 * Космическое небо: в общий ресурспак (модуль respack) кладётся шейдер неба {@code minecraft:shaders/core/sky.*}
 * — космос со всех сторон. Общий план (тёмный фон, редкие звёзды) виден всегда; Млечный путь, туманности и
 * скопления — только в областях из config.yml (zones), их шейдер получает файлом spacesky:zones.glsl.
 *
 * <p>Режим ({@code everywhere}, файл spacesky:mode.glsl, см. {@link SkyMode}): космос во всех мирах или только в мирах
 * типа «hoka:space» ({@link SpaceDimension}; модуль регистрирует его до старта сервера, карта выбирает ключом
 * {@code dimension} в map.yml — /hosky world). Сервер в игре ничего не считает — модуль только отдаёт файлы в пак.
 *
 * <p>Чёрные дыры ({@link Holes}) — из модуля gravity (softdepends, сервис BlackHoles): файл spacesky:holes.glsl.
 * После /hograv hole add|remove|reload пак пересобирается сам (с задержкой, и только если дыры изменились).
 */
public final class SpaceSkyModule extends OdysseyModule {
    private FileSystem jarFs;
    private Path root;
    private volatile List<Zones.Zone> zones = List.of();
    /** Имена карт для подсказок /hosky world — читаются с диска в async, не в тике и не при разборе пакета. */
    private volatile SortedSet<String> mapNames = new TreeSet<>();
    private volatile List<Holes.Hole> holes = List.of();
    /** Текст spacesky:holes.glsl последней сборки — пересобирать пак, только если он изменился. */
    private volatile String holesGlsl;
    /** Отписка от изменений дыр gravity (null — gravity нет). */
    private Runnable holesUnsubscribe;
    private boolean holesPending;

    @Override
    protected void onLoad() {
        // реестры Minestom — только до MinecraftServer.start; при горячей загрузке — предупреждение в лог
        SpaceDimension.register(logger());
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
        // в мирах hoka:space дождь и гроза перекрашивают небо и туман поверх таймлайна — метка теряется,
        // космос пропадает, сквозь дождь видно солнце; поэтому погода там всегда ясная
        scheduler().repeat(this::clearSpaceWeather, 20, 20);
        scheduler().repeat(this::refreshMapNames, 200, 200);
    }

    SortedSet<String> cachedMapNames() {
        return mapNames;
    }

    void refreshMapNames() {
        scheduler().async(() -> {
            try {
                mapNames = new TreeSet<>(server().mapNames());
            } catch (RuntimeException e) {
                logger().debug("список карт не прочитан: {}", e.toString());
            }
        });
    }

    private void clearSpaceWeather() {
        Key space = Key.key(SpaceDimension.KEY);
        for (Instance i : MinecraftServer.getInstanceManager().getInstances()) {
            if (space.equals(i.getDimensionType().key()) && i.getWeather().isRaining()) {
                i.setWeather(Weather.CLEAR, 1);
            }
        }
    }

    /** Дыры gravity могли измениться (вызов в тике): через 2 с перечитать и пересобрать пак, если они другие. */
    private void holesChanged() {
        if (holesPending) {
            return;
        }
        holesPending = true;
        scheduler().later(() -> {
            holesPending = false;
            if (!Holes.glsl(readHoles()).equals(holesGlsl)) {
                syncPack().exceptionally(e -> {
                    logger().warn("небо с новыми чёрными дырами не собрано: {}", e.toString());
                    return List.of();
                });
            }
        }, 40);
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
     * Перечитать config.yml, отдать respack файлы неба и области, пересобрать пак. Будущее — с ошибками конфига
     * (пусто — всё хорошо); завершается, когда respack пересобрал пак.
     */
    CompletableFuture<List<String>> syncPack() {
        config().reload();
        List<String> errors = new ArrayList<>();
        List<Zones.Zone> zs = Zones.read(config(), errors);
        for (String e : errors) {
            logger().warn("zones: {}", e);
        }
        zones = List.copyOf(zs);
        List<Holes.Hole> hs = readHoles();
        if (hs.size() > Holes.MAX) {
            logger().warn("чёрных дыр {} — в небе только первые {}", hs.size(), Holes.MAX);
        }
        holes = List.copyOf(hs.subList(0, Math.min(hs.size(), Holes.MAX)));
        String holesText = Holes.glsl(hs);
        holesGlsl = holesText;
        byte[] holesBytes = holesText.getBytes(StandardCharsets.UTF_8);
        boolean everywhere = config().getBoolean("everywhere", true);
        boolean sun = config().getBoolean("hide-sun", true);
        boolean moon = config().getBoolean("hide-moon", true);
        List<String> parts = SkyMode.parts(everywhere, sun, moon, config().getBoolean("hide-clouds", true));
        byte[] zonesGlsl = Zones.glsl(zs).getBytes(StandardCharsets.UTF_8);
        byte[] modeGlsl = SkyMode.glsl(everywhere, sun, moon).getBytes(StandardCharsets.UTF_8);
        Path r = root;
        CompletableFuture<List<String>> done = new CompletableFuture<>();
        scheduler().async(() -> {
            Map<String, byte[]> files = new TreeMap<>();
            try {
                for (String part : parts) {
                    files.putAll(collect(r.resolve(part)));
                }
            } catch (IOException | RuntimeException e) {
                logger().error("файлы неба не прочитаны: {}", e.toString());
                done.completeExceptionally(e);
                return;
            }
            files.put(Zones.PATH, zonesGlsl);
            files.put(SkyMode.PATH, modeGlsl);
            files.put(Holes.PATH, holesBytes);
            scheduler().run(() -> {
                try {
                    ResourcePackApi pack = services().require(ResourcePackApi.class);
                    pack.clear(id());
                    files.forEach((path, data) -> pack.put(id(), path, data));
                    logger().info("космическое небо: {}, файлов в паке {}, областей {}, чёрных дыр {}",
                        everywhere ? "во всех мирах" : "только в мирах " + SpaceDimension.KEY, files.size(), zs.size(),
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
