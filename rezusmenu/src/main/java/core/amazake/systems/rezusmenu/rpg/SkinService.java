package core.amazake.systems.rezusmenu.rpg;

import core.amazake.systems.rezusmenu.screen.SkinFront;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Скин игрока «спереди» для портрета: качает картинку по URL текстуры (асинхронно, таймаут 5 с), собирает вид
 * спереди 16 × 32 и кэширует по URL. Ответ — в {@code done} в асинхронном потоке (null — не вышло): дальше
 * вызывающий сам переходит в поток игрока.
 */
public final class SkinService {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_BYTES = 512 * 1024;

    private final Plugin plugin;
    private final Logger logger;
    private final Map<String, int[]> cache = new ConcurrentHashMap<>();
    /** Сам скин 64 × 64 ARGB (старые 64 × 32 приведены) по URL — для персонажа в меню профилей. */
    private final Map<String, int[]> raw = new ConcurrentHashMap<>();

    public SkinService(final Plugin plugin, final Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    /** Из кэша; null — ещё не качали. */
    public int[] cached(final String url, final boolean slim) {
        return this.cache.get(key(url, slim));
    }

    public void fetch(final String url, final boolean slim, final Consumer<int[]> done) {
        Bukkit.getAsyncScheduler().runNow(this.plugin, t -> done.accept(this.load(url, slim)));
    }

    /** Скин 64 × 64 из кэша; null — ещё не качали. */
    public int[] rawCached(final String url) {
        return this.raw.get(url);
    }

    /** Скачать скин (64 × 64 ARGB); ответ в асинхронном потоке, null — не вышло. */
    public void fetchRaw(final String url, final boolean slim, final Consumer<int[]> done) {
        Bukkit.getAsyncScheduler().runNow(this.plugin, t -> {
            if (this.raw.get(url) == null || this.cache.get(key(url, slim)) == null) {
                this.load(url, slim);
            }
            done.accept(this.raw.get(url));
        });
    }

    private int[] load(final String url, final boolean slim) {
        final String key = key(url, slim);
        final int[] hit = this.cache.get(key);
        if (hit != null && this.raw.containsKey(url)) {
            return hit;
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            final URI uri = URI.create(url.startsWith("http://textures.minecraft.net/")
                ? "https" + url.substring(4) : url);
            final HttpResponse<byte[]> res = client.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200 || res.body().length > MAX_BYTES) {
                this.logger.fine("скин " + url + ": HTTP " + res.statusCode());
                return null;
            }
            final java.awt.image.BufferedImage img = ImageIO.read(new ByteArrayInputStream(res.body()));
            if (img != null) {
                final int[] argb = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
                final int[] norm = core.amazake.systems.rezusmenu.profiles.CharacterRender.normalize(argb,
                    img.getWidth(), img.getHeight());
                if (norm != null) {
                    this.raw.put(url, norm);
                }
            }
            final int[] front = SkinFront.compose(img, slim);
            if (front != null) {
                this.cache.put(key, front);
            }
            return front;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (final Exception e) {
            this.logger.fine("скин " + url + ": " + e);
            return null;
        }
    }

    private static String key(final String url, final boolean slim) {
        return (slim ? "slim:" : "classic:") + url;
    }
}
