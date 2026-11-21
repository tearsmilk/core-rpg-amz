package ink.hoka.odyssey.respack;

import ink.hoka.odyssey.api.ModuleConfig;
import ink.hoka.odyssey.api.OdysseyModule;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerResourcePackStatusEvent;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Модуль respack: собирает ресурспак из файлов модулей (сервис {@link ResourcePackApi}) и папки администратора
 * module/respack/pack/, отдаёт его встроенным HTTP и ставит игрокам при входе (в фазе конфигурации — игрок появляется
 * в мире уже с паком). Отказ или ошибка загрузки — кик по настройке.
 */
public final class RespackModule extends OdysseyModule {

    /** Один и тот же id у всех версий пака: новый пак у клиента заменяет старый, а не добавляется рядом. */
    static final UUID PACK_ID = UUID.nameUUIDFromBytes("hoka-odyssey:respack".getBytes(StandardCharsets.UTF_8));

    /** Настройки из config.yml. */
    record Settings(int port, String bind, String publicUrl, String description, boolean sendOnJoin, boolean required,
                    String prompt, boolean kickOnDecline, String kickDecline, boolean kickOnFail, String kickFail,
                    int waitBuildSeconds, boolean resendOnRebuild) {

        static Settings read(ModuleConfig c) {
            String url = c.getString("http.public-url", "").trim();
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            return new Settings(
                    Math.max(1, Math.min(65535, c.getInt("http.port", 25574))),
                    c.getString("http.bind", "0.0.0.0").trim(),
                    url,
                    c.getString("description", "Hoka Odyssey"),
                    c.getBoolean("send-on-join", true),
                    c.getBoolean("required", true),
                    c.getString("prompt", "&bРесурспак Hoka Odyssey"),
                    c.getBoolean("kick-on-decline", true),
                    c.getString("kick-message-decline", "&cБез ресурспака играть нельзя."),
                    c.getBoolean("kick-on-fail", false),
                    c.getString("kick-message-fail", "&cРесурспак не загрузился."),
                    Math.max(0, Math.min(120, c.getInt("wait-build-seconds", 15))),
                    c.getBoolean("resend-on-rebuild", true));
        }
    }

    private final PackSources sources = new PackSources();
    private final Map<UUID, ResourcePackStatus> statuses = new ConcurrentHashMap<>();
    private final Object buildLock = new Object();
    private volatile Settings settings;
    private volatile PackHttpServer http;
    private volatile ResourcePackApi.PackInfo current;
    private volatile boolean disabled;
    private CompletableFuture<ResourcePackApi.PackInfo> running;   // под buildLock
    private CompletableFuture<ResourcePackApi.PackInfo> queued;    // под buildLock
    private boolean runningStarted;                                 // под buildLock: снимок файлов уже взят
    private boolean warnedNoAddress;

    @Override
    protected void onEnable() {
        settings = Settings.read(config());
        try {
            Files.createDirectories(adminDir());
        } catch (IOException e) {
            logger().warn("не создать {}: {}", adminDir(), e.toString());
        }
        startHttp();
        services().register(ResourcePackApi.class, new Api());

        events().addListener(AsyncPlayerConfigurationEvent.class, this::onConfiguration);
        events().addListener(PlayerResourcePackStatusEvent.class, this::onStatus);
        events().addListener(PlayerDisconnectEvent.class, e -> statuses.remove(e.getPlayer().getUuid()));
        registerCommand(new HoPackCommand(this));

        rebuild();   // первая сборка — в следующем тике, когда зависимые модули уже положат свои файлы
        logger().info("ресурспак: HTTP на {}:{}, адрес для клиентов — {}", settings.bind(), http.port(),
                settings.publicUrl().isEmpty() ? "адрес входа игрока + порт " + http.port() : settings.publicUrl());
    }

    @Override
    protected void onDisable() {
        disabled = true;
        PackHttpServer h = http;
        http = null;
        if (h != null) h.stop();
        synchronized (buildLock) {
            IllegalStateException off = new IllegalStateException("модуль respack выключен");
            if (running != null) running.completeExceptionally(off);
            if (queued != null) queued.completeExceptionally(off);
            running = null;
            queued = null;
        }
        statuses.clear();
    }

    // ───────────────────────────── настройки и HTTP ─────────────────────────────

    Settings settings() { return settings; }

    Path adminDir() { return dataFolder().resolve("pack"); }

    private void startHttp() {
        Settings s = settings;
        try {
            http = new PackHttpServer(s.bind(), s.port());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP ресурспака не запустился на " + s.bind() + ":" + s.port()
                    + " (" + e.getMessage() + ") — порт занят или не выделен; поменяйте http.port в config.yml", e);
        }
        ResourcePackApi.PackInfo c = current;
        if (c != null && lastZip != null) http.publish(c.sha1(), lastZip);
    }

    private volatile byte[] lastZip;

    /** /hopack reload: перечитать конфиг, перезапустить HTTP при смене адреса, пересобрать пак. */
    CompletableFuture<ResourcePackApi.PackInfo> reloadSettings() {
        config().reload();
        Settings old = settings;
        settings = Settings.read(config());
        if (old.port() != settings.port() || !old.bind().equals(settings.bind())) {
            PackHttpServer h = http;
            if (h != null) h.stop();
            http = null;
            try {
                startHttp();
            } catch (IllegalStateException e) {
                settings = old;                                   // вернуть старый адрес, чтобы пак продолжал раздаваться
                startHttp();
                throw e;
            }
        }
        return rebuild();
    }

    // ───────────────────────────── сборка ─────────────────────────────

    CompletableFuture<ResourcePackApi.PackInfo> rebuild() {
        synchronized (buildLock) {
            if (disabled) return CompletableFuture.failedFuture(new IllegalStateException("модуль respack выключен"));
            if (running == null) {
                running = new CompletableFuture<>();
                runningStarted = false;
                CompletableFuture<ResourcePackApi.PackInfo> f = running;
                scheduler().run(() -> startBuild(f));            // в следующем тике: склеить вызовы из одного тика
                return f;
            }
            if (!runningStarted) return running;                 // сборка ещё не взяла файлы — новые попадут в неё
            if (queued == null) queued = new CompletableFuture<>();
            return queued;
        }
    }

    private void startBuild(CompletableFuture<ResourcePackApi.PackInfo> f) {
        synchronized (buildLock) {
            if (running == f) runningStarted = true;
        }
        Settings s = settings;
        Path admin = adminDir();
        int format = MinecraftServer.RESOURCE_PACK_VERSION.major();
        scheduler().async(() -> {
            PackBuilder.Built b;
            try {
                b = PackBuilder.build(sources.snapshot(), admin, s.description(), format);
            } catch (Throwable e) {
                if (!disabled) logger().error("ресурспак не собран: {}", e.toString());
                finishBuild(f, null, e);
                return;
            }
            try {
                Files.write(dataFolder().resolve("pack.zip"), b.zip());        // копия для администратора
            } catch (IOException e) {
                logger().warn("не записать pack.zip: {}", e.toString());
            }
            if (disabled) return;
            scheduler().run(() -> {
                publish(b);
                finishBuild(f, b.info(), null);
            });
        });
    }

    private void finishBuild(CompletableFuture<ResourcePackApi.PackInfo> f, ResourcePackApi.PackInfo info, Throwable err) {
        if (err != null) f.completeExceptionally(err); else f.complete(info);
        synchronized (buildLock) {
            running = null;
            if (queued != null && !disabled) {
                running = queued;
                queued = null;
                runningStarted = false;
                CompletableFuture<ResourcePackApi.PackInfo> next = running;
                try {
                    scheduler().run(() -> startBuild(next));
                } catch (IllegalStateException e) {
                    next.completeExceptionally(e);
                    running = null;
                }
            }
        }
    }

    /** В тике сервера: выложить на HTTP и (если пак изменился) разослать. */
    private void publish(PackBuilder.Built b) {
        for (String w : b.warnings()) logger().warn("ресурспак: {}", w);
        ResourcePackApi.PackInfo prev = current;
        PackHttpServer h = http;
        if (h != null) h.publish(b.info().sha1(), b.zip());
        lastZip = b.zip();
        current = b.info();
        boolean changed = prev == null || !prev.sha1().equals(b.info().sha1());
        if (!changed) return;
        logger().info("ресурспак собран: файлов {}, {} КБ, sha1 {}", b.info().files(), b.info().size() / 1024, b.info().sha1());
        if (prev != null && settings.resendOnRebuild()) {
            int n = sendAll();
            if (n > 0) logger().info("новый ресурспак отправлен игрокам: {}", n);
        }
    }

    // ───────────────────────────── отправка ─────────────────────────────

    /** Адрес zip для игрока: public-url или адрес, по которому он зашёл, + порт HTTP. */
    String url(Player p, ResourcePackApi.PackInfo info) {
        Settings s = settings;
        String base = s.publicUrl();
        if (base.isEmpty()) {
            String host = p == null ? null : p.getPlayerConnection().getServerAddress();
            if (host != null) {
                int nul = host.indexOf('\0');                                  // Forge дописывает \0FML\0
                if (nul >= 0) host = host.substring(0, nul);
                while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            }
            if (p == null && (host == null || host.isBlank())) {
                host = "<адрес входа>";                                     // для /hopack из консоли
            } else if (host == null || host.isBlank()) {
                if (!warnedNoAddress) {
                    warnedNoAddress = true;
                    logger().warn("клиент не прислал адрес сервера — укажите http.public-url в config.yml");
                }
                host = "127.0.0.1";
            }
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";   // IPv6
            PackHttpServer h = http;
            base = "http://" + host + ":" + (h == null ? s.port() : h.port());
        }
        return base + PackHttpServer.path(info.sha1());
    }

    boolean send(Player p) {
        ResourcePackApi.PackInfo info = current;
        if (info == null) return false;
        Settings s = settings;
        ResourcePackInfo pack = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url(p, info)), info.sha1());
        p.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(pack).required(s.required()).replace(false).prompt(Text.of(s.prompt())).build());
        return true;
    }

    int sendAll() {
        int n = 0;
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) if (send(p)) n++;
        return n;
    }

    /** Вход: фаза конфигурации (отдельный поток) — дождаться первой сборки, отправить пак; ядро ждёт ответа клиента. */
    private void onConfiguration(AsyncPlayerConfigurationEvent e) {
        Settings s = settings;
        if (!s.sendOnJoin() || !e.isFirstConfig()) return;
        if (current == null) {
            CompletableFuture<ResourcePackApi.PackInfo> f;
            synchronized (buildLock) { f = running != null ? running : queued; }
            if (f != null) {
                try {
                    f.get(s.waitBuildSeconds(), TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) {
                    // не собрался или долго — игрок войдёт без пака, ошибка уже в логе
                }
            }
        }
        if (!send(e.getPlayer())) logger().warn("{} вошёл без ресурспака: пак ещё не собран", e.getPlayer().getUsername());
    }

    private void onStatus(PlayerResourcePackStatusEvent e) {
        if (!PACK_ID.equals(e.getPackUuid())) return;
        Player p = e.getPlayer();
        ResourcePackStatus st = e.getStatus();
        statuses.put(p.getUuid(), st);
        Settings s = settings;
        switch (st) {
            case DECLINED -> {
                logger().info("{} отказался от ресурспака", p.getUsername());
                if (s.kickOnDecline()) p.kick(Text.of(s.kickDecline()));
            }
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                logger().warn("{}: ресурспак не загрузился ({})", p.getUsername(), st);
                if (s.kickOnFail()) p.kick(Text.of(s.kickFail()));
            }
            default -> { }
        }
    }

    /** Сколько игроков в каком состоянии пака (для /hopack). */
    Map<ResourcePackStatus, Integer> statusCounts() {
        Map<ResourcePackStatus, Integer> out = new EnumMap<>(ResourcePackStatus.class);
        for (ResourcePackStatus st : statuses.values()) out.merge(st, 1, Integer::sum);
        return out;
    }

    ResourcePackStatus status(Player p) { return statuses.get(p.getUuid()); }

    Optional<ResourcePackApi.PackInfo> current() { return Optional.ofNullable(current); }

    PackSources sources() { return sources; }

    int httpPort() {
        PackHttpServer h = http;
        return h == null ? settings.port() : h.port();
    }

    // ───────────────────────────── сервис ─────────────────────────────

    private final class Api implements ResourcePackApi {
        @Override public void putFile(String owner, String path, byte[] data) { sources.putFile(owner, path, data); }
        @Override public void removeFile(String owner, String path) { sources.removeFile(owner, path); }
        @Override public void putDirectory(String owner, Path directory) { sources.putDirectory(owner, directory); }
        @Override public void clear(String owner) { sources.clear(owner); }
        @Override public CompletableFuture<PackInfo> rebuild() { return RespackModule.this.rebuild(); }
        @Override public Optional<PackInfo> current() { return RespackModule.this.current(); }
        @Override public boolean send(Player player) { return RespackModule.this.send(player); }
        @Override public int sendAll() { return RespackModule.this.sendAll(); }
    }
}
