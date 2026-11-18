package core.amazake.systems.coremechanics.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Полный скин тела через MineSkin (перенесено из SkinCubes): для каждой из {@link SkinBoxes#COUNT} коробок собирается
 * скин-«голова», MineSkin загружает его на сервера Mojang и возвращает подпись. Подписи кэшируются по хэшу скина в
 * {@code skins-cache.yml}. Генерация — один фоновый поток (не поток тиков!) с очередью: срочные (смерть, /rd) — вперёд,
 * фоновые (вход игрока) уступают им; паузы по лимитам API.
 *
 * <p>Если ключа нет — выключено. Если MineSkin недоступен (нет сети, таймаут) — одна строка в лог, пауза 10 минут, куклы
 * остаются в цветных блоках.</p>
 */
public final class SkinCubes {

    /** Настройки (читаются на каждый запрос: /rd reload меняет их на лету). */
    public record Config(String key, String apiUrl, int timeoutSeconds, String userAgent) {
    }

    private record Job(String hash, java.net.URL url, boolean slim, boolean urgent) {
    }

    private static final long OFFLINE_PAUSE_MS = 10 * 60_000L;

    private final Logger log;
    private final Path file;
    private final Supplier<Config> config;
    private final Consumer<String> onReady;
    private final Map<String, String[][]> cache = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final LinkedBlockingDeque<Job> queue = new LinkedBlockingDeque<>();
    private final Thread worker;
    private volatile Job running;
    private volatile boolean stopped;
    private volatile boolean keyWarned;
    private volatile long offlineUntil;

    /**
     * @param onReady вызывается в потоке MineSkin, когда все коробки скина с этим хэшем готовы
     */
    public SkinCubes(final Logger log, final Path dataFolder, final Supplier<Config> config, final Consumer<String> onReady) {
        this.log = log;
        this.file = dataFolder.resolve("skins-cache.yml");
        this.config = config;
        this.onReady = onReady;
        final YamlConfiguration y = YamlConfiguration.loadConfiguration(this.file.toFile());
        for (final String h : y.getKeys(false)) {
            final String[][] arr = new String[SkinBoxes.COUNT][];
            for (int i = 0; i < SkinBoxes.COUNT; i++) {
                final List<String> l = y.getStringList(h + "." + i);
                if (l.size() == 2) {
                    arr[i] = new String[] {l.get(0), l.get(1)};
                }
            }
            this.cache.put(h, arr);
        }
        this.worker = new Thread(this::loop, "core-mechanics-mineskin");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public boolean enabled() {
        return !this.config.get().key().isBlank();
    }

    /** Ключ есть и MineSkin не на паузе после ошибки сети — можно обещать игроку, что тело переоденется. */
    public boolean available() {
        return this.enabled() && !this.stopped && System.currentTimeMillis() >= this.offlineUntil;
    }

    /** Сколько коробок скина уже подписано. */
    public int done(final String hash) {
        final String[][] a = this.cache.get(hash);
        int n = 0;
        if (a != null) {
            for (final String[] x : a) {
                if (x != null) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Сколько скинов будет сделано раньше этого. */
    public int queuedBefore(final String hash) {
        final Job r = this.running;
        int n = r != null && !r.hash().equals(hash) ? 1 : 0;
        for (final Job j : this.queue) {
            if (j.hash().equals(hash)) {
                break;
            }
            n++;
        }
        return n;
    }

    /** Все подписи готовы — [value, signature] на каждую коробку, иначе null. */
    public String[][] get(final String hash) {
        final String[][] a = this.cache.get(hash);
        if (a == null) {
            return null;
        }
        for (final String[] x : a) {
            if (x == null) {
                return null;
            }
        }
        return a;
    }

    /** Любой поток: поставить скин в очередь (urgent — вперёд). */
    public synchronized void request(final String hash, final java.net.URL url, final boolean slim, final boolean urgent) {
        if (this.get(hash) != null || !this.available()) {
            return;
        }
        if (!this.pending.add(hash)) {
            if (urgent) {   // уже ждёт в очереди — поднять вперёд
                for (final Job j : this.queue) {
                    if (j.hash().equals(hash)) {
                        this.queue.remove(j);
                        this.queue.addFirst(new Job(hash, url, slim, true));
                        break;
                    }
                }
            }
            return;
        }
        final Job j = new Job(hash, url, slim, urgent);
        if (urgent) {
            this.queue.addFirst(j);
        } else {
            this.queue.addLast(j);
        }
    }

    private void loop() {
        while (!this.stopped) {
            final Job j;
            try {
                j = this.queue.take();
            } catch (final InterruptedException e) {
                return;
            }
            this.running = j;
            boolean requeued = false;
            try {
                requeued = this.generate(j);
                if (!requeued && this.get(j.hash()) != null && !this.stopped) {
                    this.onReady.accept(j.hash());
                }
            } catch (final InterruptedException e) {
                return;
            } catch (final IOException e) {
                this.offline(e);
            } catch (final RuntimeException e) {
                this.log.warning("MineSkin: " + e);
            } finally {
                this.running = null;
                if (!requeued) {
                    this.pending.remove(j.hash());
                }
            }
        }
    }

    /** Сеть недоступна: тихо (одна строка) и пауза, очередь сбросить — куклы останутся в цветных блоках. */
    private void offline(final IOException e) {
        final boolean first = this.offlineUntil == 0;
        this.offlineUntil = System.currentTimeMillis() + OFFLINE_PAUSE_MS;
        this.queue.clear();
        this.pending.clear();
        if (first) {
            this.log.info("MineSkin недоступен (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                + ") — тела кукол остаются цветными блоками; следующая попытка через 10 мин.");
        }
    }

    public void shutdown() {
        this.stopped = true;
        this.worker.interrupt();
        try {
            this.worker.join(2000);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** true — уступил место срочному скину и вернулся в очередь. */
    private boolean generate(final Job j) throws IOException, InterruptedException {
        final BufferedImage skin;
        try (InputStream in = j.url().openStream()) {
            skin = ImageIO.read(in);
        }
        if (skin == null) {
            return false;
        }
        final String[][] arr = this.cache.computeIfAbsent(j.hash(), h -> new String[SkinBoxes.COUNT][]);
        final BufferedImage[] imgs = new BufferedImage[SkinBoxes.COUNT];
        for (int i = 0; i < SkinBoxes.COUNT; i++) {
            imgs[i] = SkinBoxes.boxSkin(skin, i, j.slim());
        }
        for (int i = 0; i < SkinBoxes.COUNT && !this.stopped; i++) {
            if (arr[i] != null) {
                continue;
            }
            for (int k = 0; k < SkinBoxes.COUNT; k++) {   // такая же картинка уже подписана — взять её подпись
                if (k != i && arr[k] != null && SkinBoxes.samePixels(imgs[i], imgs[k])) {
                    arr[i] = arr[k];
                    this.save(j.hash(), i, arr[k]);
                    break;
                }
            }
            if (arr[i] != null) {
                continue;
            }
            final Job head = this.queue.peekFirst();
            if (!j.urgent() && head != null && head.urgent()) {
                this.queue.addLast(j);   // срочная кукла ждёт — уступить
                return true;
            }
            final String[] vs = this.upload(imgs[i]);
            if (vs == null) {
                return false;   // ошибка API — попробуем при следующем заходе игрока
            }
            arr[i] = vs;
            this.save(j.hash(), i, vs);
        }
        return false;
    }

    /** PNG → MineSkin; [value, signature] или null при ошибке API. IOException — сеть недоступна. */
    private String[] upload(final BufferedImage img) throws IOException, InterruptedException {
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        final String boundary = "----coremechanics" + UUID.randomUUID().toString().replace("-", "");
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (final String[] f : new String[][] {{"variant", "classic"}, {"visibility", "unlisted"}, {"name", "ragdoll"}}) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f[0] + "\"\r\n\r\n" + f[1] + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"ragdoll.png\"\r\n"
            + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(png.toByteArray());
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        final byte[] bytes = body.toByteArray();
        for (int attempt = 0; attempt < 8 && !this.stopped; attempt++) {
            final Config c = this.config.get();
            if (c.key().isBlank()) {
                return null;
            }
            final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(3, c.timeoutSeconds() / 4))).build();
            final HttpRequest req = HttpRequest.newBuilder(URI.create(c.apiUrl()))
                .timeout(Duration.ofSeconds(Math.max(5, c.timeoutSeconds())))
                .header("Authorization", "Bearer " + c.key().strip())
                .header("User-Agent", c.userAgent())
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
            final HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject js = null;
            try {
                final JsonElement e = JsonParser.parseString(res.body());
                js = e.isJsonObject() ? e.getAsJsonObject() : null;
            } catch (final RuntimeException ignored) {
                // не JSON
            }
            final long wait = nextDelay(js);
            if (res.statusCode() == 429) {
                final long ra = res.headers().firstValue("Retry-After").map(s -> {
                    try {
                        return (long) (Double.parseDouble(s) * 1000);
                    } catch (final NumberFormatException x) {
                        return 0L;
                    }
                }).orElse(0L);
                Thread.sleep(Math.max(5000, Math.max(wait, ra)));
                continue;
            }
            if (res.statusCode() / 100 == 2 && js != null && js.has("skin")) {
                final JsonObject data = js.getAsJsonObject("skin").getAsJsonObject("texture").getAsJsonObject("data");
                Thread.sleep(Math.max(500, wait));
                return new String[] {data.get("value").getAsString(), data.get("signature").getAsString()};
            }
            String msg = "HTTP " + res.statusCode();
            if (js != null && js.has("errors") && !js.getAsJsonArray("errors").isEmpty()) {
                final JsonObject er = js.getAsJsonArray("errors").get(0).getAsJsonObject();
                msg += " " + (er.has("code") ? er.get("code").getAsString() : "") + ": " + (er.has("message") ? er.get("message").getAsString() : "");
            }
            if (res.statusCode() == 401 || res.statusCode() == 403) {
                if (!this.keyWarned) {
                    this.keyWarned = true;
                    this.log.warning("MineSkin отклонил ключ (mineskin.key): " + msg);
                }
                return null;
            }
            if (res.statusCode() >= 500) {
                Thread.sleep(Math.max(5000, wait));
                continue;
            }
            this.log.warning("MineSkin: " + msg);
            return null;
        }
        return null;
    }

    private static long nextDelay(final JsonObject js) {
        try {
            return (long) js.getAsJsonObject("rateLimit").getAsJsonObject("next").get("relative").getAsDouble();
        } catch (final RuntimeException e) {
            return 0;
        }
    }

    /** Поток MineSkin: дописать подпись в skins-cache.yml (атомарно: временный файл + rename). */
    private synchronized void save(final String hash, final int i, final String[] vs) {
        final YamlConfiguration y = YamlConfiguration.loadConfiguration(this.file.toFile());
        y.set(hash + "." + i, List.of(vs[0], vs[1]));
        try {
            Files.createDirectories(this.file.getParent());
            final Path tmp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            Files.writeString(tmp, y.saveToString(), StandardCharsets.UTF_8);
            Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            this.log.warning("не сохранить " + this.file.getFileName() + ": " + e.getMessage());
        }
    }
}
