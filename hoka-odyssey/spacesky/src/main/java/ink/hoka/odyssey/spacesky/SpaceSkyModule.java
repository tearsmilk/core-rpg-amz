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
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Космическое небо: в общий ресурспак (модуль respack) кладётся шейдер неба {@code minecraft:shaders/core/sky.*}
 * — космос со всех сторон, — и (по настройкам) прозрачные текстуры солнца, луны и облаков. Общий план (тёмный фон,
 * редкие звёзды) виден всегда; Млечный путь, туманности и скопления — только в областях из config.yml (zones), их
 * шейдер получает файлом spacesky:zones.glsl. Сервер в игре ничего не считает — модуль только отдаёт файлы в пак.
 */
public final class SpaceSkyModule extends OdysseyModule {
    /** Части пака внутри jar: всегда — pack/, по настройкам — hide/sun, hide/moon, hide/clouds. */
    static final String ALWAYS = "pack";
    static final Map<String, String> OPTIONAL = Map.of("hide-sun", "hide/sun", "hide-moon", "hide/moon",
        "hide-clouds", "hide/clouds");

    private FileSystem jarFs;
    private Path root;
    private volatile List<Zones.Zone> zones = List.of();

    @Override
    protected void onEnable() {
        root = jarRoot();
        if (root == null) {
            throw new IllegalStateException("не открыть jar модуля — шейдер неба не попадёт в пак");
        }
        registerCommand(new HoSkyCommand(this));
        syncPack();
    }

    @Override
    protected void onDisable() {
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
        List<String> parts = parts(config().getBoolean("hide-sun", true), config().getBoolean("hide-moon", true),
            config().getBoolean("hide-clouds", true));
        byte[] zonesGlsl = Zones.glsl(zs).getBytes(StandardCharsets.UTF_8);
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
            scheduler().run(() -> {
                try {
                    ResourcePackApi pack = services().require(ResourcePackApi.class);
                    pack.clear(id());
                    files.forEach((path, data) -> pack.put(id(), path, data));
                    logger().info("космическое небо: файлов в паке {}, областей {}", files.size(), zs.size());
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

    static List<String> parts(boolean sun, boolean moon, boolean clouds) {
        List<String> out = new ArrayList<>();
        out.add(ALWAYS);
        if (sun) {
            out.add(OPTIONAL.get("hide-sun"));
        }
        if (moon) {
            out.add(OPTIONAL.get("hide-moon"));
        }
        if (clouds) {
            out.add(OPTIONAL.get("hide-clouds"));
        }
        return out;
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
