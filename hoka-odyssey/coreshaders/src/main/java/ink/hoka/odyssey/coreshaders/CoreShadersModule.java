package ink.hoka.odyssey.coreshaders;

import ink.hoka.odyssey.api.OdysseyModule;
import ink.hoka.odyssey.respack.ResourcePackApi;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Map;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.instance.InstanceChunkLoadEvent;
import net.minestom.server.event.instance.InstanceChunkUnloadEvent;
import net.minestom.server.event.instance.InstanceRegisterEvent;
import net.minestom.server.event.instance.InstanceUnregisterEvent;
import net.minestom.server.instance.Instance;

/**
 * coreshaders — «окна в другой мир»: куб 16x16x16 или плоскость на грани блока, в которых видна панорама 360°
 * (шейдер в ресурспаке, картинка зависит от направления взгляда). Эффект — ItemDisplay с моделью
 * coreshaders:&lt;форма&gt;_&lt;картинка&gt; и Interaction для кликов. Шейдер, модели и панорамы кладутся в общий
 * ресурспак через сервис {@link ResourcePackApi} модуля respack (depends: [respack]).
 */
public final class CoreShadersModule extends OdysseyModule {
    private volatile Settings settings;
    private Map<String, Object> defaultMessages;
    private Effects effects;
    private Triggers triggers;
    private Images images;
    private FileSystem jarFs;
    private long tick;

    @Override
    protected void onEnable() {
        defaultMessages = Settings.bundledMessages(getClass().getClassLoader());
        settings = new Settings(config(), defaultMessages);
        effects = new Effects(this);
        triggers = new Triggers(this);
        images = new Images(this);
        images.prepare();

        registerPack();

        new Interactions(this).register();
        triggers.register();
        registerCommand(new HoCsCommand(this));

        events().addListener(InstanceRegisterEvent.class, e -> {
            Instance i = e.getInstance();
            scheduler().run(() -> effects.attach(i));          // ядро запоминает имя карты после регистрации
        });
        events().addListener(InstanceUnregisterEvent.class, e -> effects.detach(e.getInstance()));
        events().addListener(InstanceChunkLoadEvent.class, e -> {
            Instance i = e.getInstance();
            int cx = e.getChunkX();
            int cz = e.getChunkZ();
            scheduler().run(() -> effects.chunkLoaded(i, cx, cz));
        });
        events().addListener(InstanceChunkUnloadEvent.class, e ->
            effects.chunkUnloaded(e.getInstance(), e.getChunkX(), e.getChunkZ()));
        for (Instance i : MinecraftServer.getInstanceManager().getInstances()) {
            effects.attach(i);
        }

        scheduler().repeat(() -> tick++, 1, 1);
        scheduler().repeat(effects::saveAll, 100, 100);                // раз в 5 с — если что-то менялось
        scheduler().repeat(() -> {
            int n = effects.verify();
            if (n > 0) {
                logger().info("возвращено сущностей эффектов: {}", n);
            }
        }, 600, 600);
        logger().info("coreshaders: картинок {}, команда /hocs", settings.images.size());
    }

    @Override
    protected void onDisable() {
        if (effects != null) {
            effects.saveAllNow();
            effects.shutdown();
        }
        services().get(ResourcePackApi.class).ifPresent(p -> p.clear(id()));
        if (jarFs != null) {
            try {
                jarFs.close();
            } catch (IOException ignored) {
                // jar уже закрыт
            }
            jarFs = null;
        }
    }

    /**
     * Встроенный пак (шейдер, модели, 12 панорам) — папка pack/ внутри jar модуля, открытая как zip-файловая система:
     * respack читает её сам при сборке, без копирования. Свои картинки — module/coreshaders/pack/.
     */
    private void registerPack() {
        ResourcePackApi pack = pack();
        Path builtin = builtinPack();
        if (builtin != null) {
            pack.putDirectory(id(), builtin);
        } else {
            logger().error("встроенный пак не найден в jar модуля — шейдер и панорамы не попадут в ресурспак");
        }
        pack.putDirectory(id(), images.packRoot());
        pack.rebuild();
    }

    private Path builtinPack() {
        try {
            CodeSource cs = getClass().getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            Path jar = Path.of(cs.getLocation().toURI());
            if (Files.isDirectory(jar)) {                                  // запуск из IDE: classes/ рядом с resources/
                Path res = jar.resolve("pack");
                return Files.isDirectory(res) ? res : null;
            }
            jarFs = FileSystems.newFileSystem(URI.create("jar:" + jar.toUri()), Map.of());
            Path p = jarFs.getPath("/pack");
            return Files.isDirectory(p) ? p : null;
        } catch (IOException | URISyntaxException | RuntimeException e) {
            logger().error("не открыть jar модуля: {}", e.toString());
            return null;
        }
    }

    // ───────────────────────────── для частей модуля ─────────────────────────────

    Settings settings() {
        return settings;
    }

    void reloadSettings() {
        config().reload();
        settings = new Settings(config(), defaultMessages);
    }

    Effects effects() {
        return effects;
    }

    Triggers triggers() {
        return triggers;
    }

    Images images() {
        return images;
    }

    long tick() {
        return tick;
    }

    /** Сервис respack — брать каждый раз заново (после перезагрузки respack объект другой). */
    ResourcePackApi pack() {
        return services().require(ResourcePackApi.class);
    }
}
