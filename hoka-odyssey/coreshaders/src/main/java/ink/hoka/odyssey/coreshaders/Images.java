package ink.hoka.odyssey.coreshaders;

import ink.hoka.odyssey.api.ModuleConfig;
import ink.hoka.odyssey.respack.ResourcePackApi;
import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minestom.server.command.CommandSender;

/**
 * Свои картинки: /hocs upload (https), /hocs import (файл из module/coreshaders/images/), /hocs delete-image.
 * Файлы пака пишутся в module/coreshaders/pack/assets/coreshaders/… (эта папка отдана модулю respack), после чего
 * пак пересобирается и рассылается. Скачивание и обработка — в scheduler().async, всё остальное — в тике.
 */
final class Images {
    static final List<String> EXTENSIONS = List.of(".png", ".jpg", ".jpeg", ".gif");
    private static final Object WRITE_LOCK = new Object();

    private final CoreShadersModule module;
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> pending = new ConcurrentHashMap<>();

    Images(CoreShadersModule module) {
        this.module = module;
    }

    Path folder() {
        return module.dataFolder().resolve("images");
    }

    /** Корень пака модуля (то, что отдаётся respack): pack/ → assets/coreshaders/… */
    Path packRoot() {
        return module.dataFolder().resolve("pack");
    }

    Path assets() {
        return packRoot().resolve("assets");
    }

    String folderName() {
        return "module/" + module.id() + "/images";
    }

    void prepare() {
        try {
            Files.createDirectories(folder());
            Files.createDirectories(assets());
        } catch (IOException e) {
            module.logger().warn("не создать папки картинок: {}", e.toString());
        }
    }

    List<String> importable() {
        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder())) {
            for (Path f : ds) {
                String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                for (String ext : EXTENSIONS) {
                    if (n.endsWith(ext)) {
                        out.add(n.substring(0, n.length() - ext.length()));
                    }
                }
            }
        } catch (IOException e) {
            return out;
        }
        out.sort(null);
        return out;
    }

    private interface Source {
        byte[] read() throws Exception;
    }

    private boolean nameOk(CommandSender sender, String name) {
        Settings s = module.settings();
        if (!Panorama.validName(name)) {
            sender.sendMessage(s.msg("image-bad-name", "value", name));
            return false;
        }
        if (Settings.DEFAULT_IMAGES.contains(name) || (s.images.contains(name) && !s.isCustom(name))) {
            sender.sendMessage(s.msg("image-builtin", "image", name));
            return false;
        }
        if (Panorama.reservedName(name)) {
            sender.sendMessage(s.msg("image-reserved", "image", name));
            return false;
        }
        Set<String> others = new HashSet<>(Settings.DEFAULT_IMAGES);
        others.addAll(s.images);
        others.addAll(pending.keySet());
        List<String> clash = PackFiles.clashes(name, others);
        if (clash.isEmpty() && !s.isCustom(name)) {
            Path root = assets();
            for (String rel : PackFiles.paths(name)) {
                if (Files.exists(root.resolve(rel))) {
                    clash.add(rel);
                }
            }
        }
        if (!clash.isEmpty()) {
            sender.sendMessage(s.msg("image-exists", "image", name, "value", clash.get(0)));
            return false;
        }
        return true;
    }

    private Integer reserve(CommandSender sender, String name) {
        Settings s = module.settings();
        if (!nameOk(sender, name)) {
            return null;
        }
        Integer kind = s.customKinds.get(name);
        if (kind == null) {
            if (s.customKinds.size() + pending.size() >= s.customMax) {
                sender.sendMessage(s.msg("image-limit", "max", s.customMax));
                return null;
            }
            Set<Integer> used = new HashSet<>(s.customKinds.values());
            used.addAll(pending.values());
            kind = Panorama.allocateKind(used);
            if (kind < 0) {
                sender.sendMessage(s.msg("image-limit", "max", s.customMax));
                return null;
            }
        }
        if (!busy.add(name)) {
            sender.sendMessage(s.msg("image-busy", "image", name));
            return null;
        }
        pending.put(name, kind);
        return kind;
    }

    void upload(CommandSender sender, String rawName, String url) {
        Settings s = module.settings();
        String name = rawName.toLowerCase(Locale.ROOT);
        if (!nameOk(sender, name)) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(s.msg("image-bad-url", "value", url));
            return;
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https") || uri.getHost() == null) {
            sender.sendMessage(s.msg("image-bad-url", "value", url));
            return;
        }
        Integer kind = reserve(sender, name);
        if (kind == null) {
            return;
        }
        long limit = s.maxDownloadBytes;
        int timeout = s.downloadTimeout;
        sender.sendMessage(s.msg("image-downloading", "image", name));
        start(sender, name, kind, () -> ImageBuilder.download(uri, limit, timeout));
    }

    void importFile(CommandSender sender, String rawName) {
        Settings s = module.settings();
        String name = rawName.toLowerCase(Locale.ROOT);
        if (!nameOk(sender, name)) {
            return;
        }
        Path file = null;
        for (String ext : EXTENSIONS) {
            Path f = folder().resolve(name + ext);
            if (Files.isRegularFile(f)) {
                file = f;
                break;
            }
        }
        if (file == null) {
            sender.sendMessage(s.msg("image-no-file", "image", name, "folder", folderName()));
            return;
        }
        Integer kind = reserve(sender, name);
        if (kind == null) {
            return;
        }
        Path src = file;
        long limit = s.maxDownloadBytes;
        start(sender, name, kind, () -> {
            if (Files.size(src) > limit) {
                throw new ImageBuilder.Fail("image-too-big", String.valueOf(limit / 1024 / 1024));
            }
            return Files.readAllBytes(src);
        });
    }

    private void start(CommandSender sender, String name, int kind, Source source) {
        Settings s = module.settings();
        Path root = assets();
        long maxPixels = s.maxPixels;
        ImageBuilder.Anim anim = s.anim;
        Set<String> others = new HashSet<>(s.customKinds.keySet());
        others.remove(name);
        module.scheduler().async(() -> {
            try {
                byte[] bytes = source.read();
                ImageBuilder.Built b = ImageBuilder.build(bytes, maxPixels, kind, anim);
                synchronized (WRITE_LOCK) {
                    if (b.animated()) {
                        Set<String> all = new HashSet<>(others);
                        all.addAll(pending.keySet());
                        all.remove(name);
                        long total = b.textureBytes() + ImageBuilder.animatedBytes(root, all);
                        if (total > anim.totalTextureBytes()) {
                            throw new ImageBuilder.Fail("image-anim-total", ImageBuilder.mbText(total) + " / "
                                + ImageBuilder.mbText(anim.totalTextureBytes()));
                        }
                    }
                    ImageBuilder.write(root, name, b);
                }
                module.scheduler().run(() -> finish(sender, name, kind, b));
            } catch (ImageBuilder.Fail f) {
                module.scheduler().run(() -> failed(sender, name, f.key, f.detail));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception | OutOfMemoryError ex) {
                module.logger().warn("картинка {} не вышла: {}", name, ex.toString());
                String d = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                module.scheduler().run(() -> failed(sender, name, "image-failed", d));
            }
        });
    }

    private void failed(CommandSender sender, String name, String key, String detail) {
        busy.remove(name);
        pending.remove(name);
        sender.sendMessage(module.settings().msg(key, "image", name, "value", detail == null ? "" : detail));
    }

    private void finish(CommandSender sender, String name, int kind, ImageBuilder.Built b) {
        busy.remove(name);
        pending.remove(name);
        ModuleConfig c = module.config();
        List<String> imgs = new ArrayList<>(module.settings().images);
        if (!imgs.contains(name)) {
            imgs.add(name);
        }
        c.set("images", imgs);
        if (!c.contains("image-names." + name)) {
            c.set("image-names." + name, name);
        }
        c.set("custom-images.kinds." + name, kind);
        c.save();
        module.reloadSettings();
        Settings s = module.settings();
        long ticks = 0;
        if (b.animated()) {
            for (int t : b.ticks()) {
                ticks += t;
            }
        }
        sender.sendMessage(s.msg(b.animated() ? "image-added-anim" : "image-added", "image", name,
            "kind", kind, "size", b.width() + "x" + b.height(),
            "mode", b.mode() == Panorama.Mode.PANORAMA ? "panorama" : "mirror",
            "frames", b.animated() ? b.ticks().length : 1,
            "frame", b.frameW() + "x" + b.frameH(),
            "seconds", String.format(Locale.ROOT, "%.1f", ticks / 20.0),
            "file", ImageBuilder.mbText(b.png().length)));
        rebuildPack(sender);
    }

    void delete(CommandSender sender, String rawName) {
        Settings s = module.settings();
        String name = rawName.toLowerCase(Locale.ROOT);
        if (!s.isCustom(name)) {
            sender.sendMessage(s.msg("image-not-custom", "image", name));
            return;
        }
        if (!busy.add(name)) {
            sender.sendMessage(s.msg("image-busy", "image", name));
            return;
        }
        Path root = assets();
        module.scheduler().async(() -> {
            int removed = 0;
            List<String> rels = new ArrayList<>(PackFiles.paths(name));
            rels.add(PackFiles.meta(name));
            for (String rel : rels) {
                try {
                    if (Files.deleteIfExists(root.resolve(rel))) {
                        removed++;
                    }
                } catch (IOException ex) {
                    module.logger().warn("не удалить {}: {}", rel, ex.getMessage());
                }
            }
            int files = removed;
            module.scheduler().run(() -> deleted(sender, name, files));
        });
    }

    private void deleted(CommandSender sender, String name, int files) {
        busy.remove(name);
        ModuleConfig c = module.config();
        List<String> imgs = new ArrayList<>(module.settings().images);
        imgs.remove(name);
        c.set("images", imgs);
        c.set("image-names." + name, null);
        c.set("custom-images.kinds." + name, null);
        c.save();
        module.reloadSettings();
        Settings s = module.settings();
        int changed = 0;
        for (EffectWorld w : module.effects().worlds()) {
            for (Effect e : List.copyOf(w.all())) {
                if (name.equals(e.data.image())) {
                    module.effects().update(w, e, e.data.withImage(s.defaultImage));
                    changed++;
                }
            }
        }
        sender.sendMessage(s.msg("image-deleted", "image", name, "files", files, "count", changed));
        rebuildPack(sender);
    }

    /** Пересобрать пак модулем respack и сказать, чем кончилось. */
    void rebuildPack(CommandSender sender) {
        ResourcePackApi pack = module.pack();
        Settings s = module.settings();
        String before = pack.current().map(ResourcePackApi.PackInfo::sha1).orElse("");
        sender.sendMessage(s.msg("pack-rebuilding"));
        pack.rebuild().whenComplete((info, err) -> {
            try {
                module.scheduler().run(() -> {
                    Settings st = module.settings();
                    if (err != null) {
                        Throwable c = err.getCause() != null ? err.getCause() : err;
                        sender.sendMessage(st.msg("pack-failed", "value", String.valueOf(c.getMessage())));
                    } else {
                        sender.sendMessage(st.msg(info.sha1().equals(before) ? "pack-same" : "pack-ready"));
                    }
                });
            } catch (IllegalStateException off) {
                // модуль уже выключен
            }
        });
    }
}
