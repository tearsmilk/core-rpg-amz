package core.amazake.systems.coremechanics.skin;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import core.amazake.systems.coremechanics.doll.Piece;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerTextures;

/**
 * Скины кукол: средние цвета тела (скачать PNG скина — асинхронно) и кубики MineSkin ({@link SkinCubes}). Карты —
 * потокобезопасные: читают и регионы (спавн куклы), и потоки игроков, и поток MineSkin.
 */
public final class SkinService {

    private final Plugin plugin;
    private final SkinCubes cubes;
    private final BooleanSupplier closed;
    private final Map<UUID, BodyColors> colors = new ConcurrentHashMap<>();
    /** Хэш текстуры скина игрока (ключ кэша кубиков MineSkin). */
    private final Map<UUID, String> hashes = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> downloading = new ConcurrentHashMap<>();

    public SkinService(final Plugin plugin, final SkinCubes cubes, final BooleanSupplier closed) {
        this.plugin = plugin;
        this.cubes = cubes;
        this.closed = closed;
    }

    public SkinCubes cubes() {
        return this.cubes;
    }

    public String hash(final UUID owner) {
        return this.hashes.get(owner);
    }

    /** Тело для куклы этого игрока: кубики скина, если готовы, иначе цветные блоки (или Стив). */
    public List<Piece> body(final UUID owner) {
        final String h = this.hashes.get(owner);
        final String[][] tex = h == null ? null : this.cubes.get(h);
        return tex != null ? BodyLooks.cubes(tex) : BodyLooks.blocks(this.colors.getOrDefault(owner, BodyColors.STEVE));
    }

    /** Кубики скина этого игрока готовы. */
    public boolean cubed(final UUID owner) {
        final String h = this.hashes.get(owner);
        return h != null && this.cubes.get(h) != null;
    }

    /** Текстура «textures» профиля: [value, signature] или null. */
    public static String[] texture(final PlayerProfile p) {
        for (final ProfileProperty pr : p.getProperties()) {
            if (pr.getName().equals("textures")) {
                return new String[] {pr.getValue(), pr.getSignature()};
            }
        }
        return null;
    }

    /**
     * Любой поток: узнать хэш скина, поставить кубики в очередь MineSkin и (раз) скачать скин для средних цветов.
     * Без текстуры (офлайн-режим) — ничего: кукла останется Стивом.
     */
    public void fetch(final PlayerProfile p, final boolean urgent) {
        final URL url;
        final boolean slim;
        try {
            final PlayerTextures t = p.getTextures();
            url = t.getSkin();
            slim = t.getSkinModel() == PlayerTextures.SkinModel.SLIM;
        } catch (final RuntimeException e) {
            return;
        }
        if (url == null || p.getId() == null) {
            return;
        }
        final UUID id = p.getId();
        final String path = url.getPath();
        final String hash = path.substring(path.lastIndexOf('/') + 1);
        this.hashes.put(id, hash);
        this.cubes.request(hash, url, slim, urgent);
        if (this.colors.containsKey(id) || this.downloading.putIfAbsent(id, Boolean.TRUE) != null) {
            return;
        }
        Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            try (InputStream in = url.openStream()) {
                if (this.closed.getAsBoolean()) {
                    return;
                }
                final BufferedImage img = javax.imageio.ImageIO.read(in);
                if (img != null) {
                    this.colors.put(id, SkinColors.of(img));
                }
            } catch (final Exception ignored) {
                // нет сети/скина — останутся цвета по умолчанию
            } finally {
                this.downloading.remove(id);
            }
        });
    }
}
