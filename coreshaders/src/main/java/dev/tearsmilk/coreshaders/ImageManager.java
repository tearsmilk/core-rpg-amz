package dev.tearsmilk.coreshaders;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ItemDisplay;

public final class ImageManager {
    public static final List<String> EXTENSIONS = List.of(".png", ".jpg", ".jpeg");
    public static final int MAX_REDIRECTS = 5;

    private final CoreShadersPlugin plugin;
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> pending = new ConcurrentHashMap<>();

    public ImageManager(CoreShadersPlugin plugin) {
        this.plugin = plugin;
    }

    public File folder() {
        return new File(plugin.getDataFolder(), "images");
    }

    public void prepare() {
        File f = folder();
        if (!f.isDirectory() && !f.mkdirs()) {
            plugin.getLogger().warning("Cannot create " + f);
        }
    }

    public Path assets(Settings s) {
        Path p = Path.of(s.packAssets);
        if (!p.isAbsolute()) {
            p = plugin.getDataFolder().getAbsoluteFile().getParentFile().toPath().resolve(p);
        }
        return p.normalize();
    }

    public List<String> importable() {
        List<String> out = new ArrayList<>();
        File[] files = folder().listFiles();
        if (files == null) {
            return out;
        }
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            for (String ext : EXTENSIONS) {
                if (n.endsWith(ext)) {
                    out.add(n.substring(0, n.length() - ext.length()));
                }
            }
        }
        return out;
    }

    private interface Source {
        byte[] read() throws Exception;
    }

    static final class Fail extends Exception {
        private static final long serialVersionUID = 1L;
        final String key;
        final String detail;

        Fail(String key, String detail) {
            super(key + ": " + detail);
            this.key = key;
            this.detail = detail;
        }
    }

    private boolean nameOk(CommandSender sender, String name) {
        Settings s = plugin.settings();
        if (!Panorama.validName(name)) {
            sender.sendMessage(s.msg("image-bad-name", Placeholder.unparsed("value", name)));
            return false;
        }
        if (Settings.DEFAULT_IMAGES.contains(name) || (s.images.contains(name) && !s.isCustom(name))) {
            sender.sendMessage(s.msg("image-builtin", Placeholder.unparsed("image", name)));
            return false;
        }
        if (Panorama.reservedName(name)) {
            sender.sendMessage(s.msg("image-reserved", Placeholder.unparsed("image", name)));
            return false;
        }
        Set<String> others = new HashSet<>(Settings.DEFAULT_IMAGES);
        others.addAll(s.images);
        others.addAll(pending.keySet());
        List<String> clash = PackFiles.clashes(name, others);
        if (clash.isEmpty() && !s.isCustom(name)) {
            Path root = assets(s);
            for (String rel : PackFiles.paths(name)) {
                if (Files.exists(root.resolve(rel))) {
                    clash.add(rel);
                }
            }
        }
        if (!clash.isEmpty()) {
            sender.sendMessage(s.msg("image-exists", Placeholder.unparsed("image", name),
                Placeholder.unparsed("value", clash.get(0))));
            return false;
        }
        return true;
    }

    private Integer reserve(CommandSender sender, String name) {
        Settings s = plugin.settings();
        if (!nameOk(sender, name)) {
            return null;
        }
        Integer kind = s.customKinds.get(name);
        if (kind == null) {
            if (s.customKinds.size() >= s.customMax) {
                sender.sendMessage(s.msg("image-limit", Placeholder.unparsed("max", String.valueOf(s.customMax))));
                return null;
            }
            if (s.customKinds.size() + pending.size() >= s.customMax) {
                sender.sendMessage(s.msg("image-limit", Placeholder.unparsed("max", String.valueOf(s.customMax))));
                return null;
            }
            Set<Integer> used = new HashSet<>(s.customKinds.values());
            used.addAll(pending.values());
            kind = Panorama.allocateKind(used);
            if (kind < 0) {
                sender.sendMessage(s.msg("image-limit", Placeholder.unparsed("max", String.valueOf(s.customMax))));
                return null;
            }
        }
        if (!busy.add(name)) {
            sender.sendMessage(s.msg("image-busy", Placeholder.unparsed("image", name)));
            return null;
        }
        pending.put(name, kind);
        return kind;
    }

    public void upload(CommandSender sender, String rawName, String url) {
        Settings s = plugin.settings();
        String name = rawName.toLowerCase(Locale.ROOT);
        if (!nameOk(sender, name)) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(s.msg("image-bad-url", Placeholder.unparsed("value", url)));
            return;
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https") || uri.getHost() == null) {
            sender.sendMessage(s.msg("image-bad-url", Placeholder.unparsed("value", url)));
            return;
        }
        Integer kind = reserve(sender, name);
        if (kind == null) {
            return;
        }
        long limit = s.maxDownloadBytes;
        int timeout = s.downloadTimeout;
        sender.sendMessage(s.msg("image-downloading", Placeholder.unparsed("image", name)));
        start(sender, name, kind, () -> download(uri, limit, timeout));
    }

    public void importFile(CommandSender sender, String rawName) {
        Settings s = plugin.settings();
        String name = rawName.toLowerCase(Locale.ROOT);
        if (!nameOk(sender, name)) {
            return;
        }
        File file = null;
        for (String ext : EXTENSIONS) {
            File f = new File(folder(), name + ext);
            if (f.isFile()) {
                file = f;
                break;
            }
        }
        if (file == null) {
            sender.sendMessage(s.msg("image-no-file", Placeholder.unparsed("image", name),
                Placeholder.unparsed("folder", "plugins/" + plugin.getName() + "/images")));
            return;
        }
        Integer kind = reserve(sender, name);
        if (kind == null) {
            return;
        }
        File src = file;
        long limit = s.maxDownloadBytes;
        start(sender, name, kind, () -> {
            if (src.length() > limit) {
                throw new Fail("image-too-big", String.valueOf(limit / 1024 / 1024));
            }
            return Files.readAllBytes(src.toPath());
        });
    }

    private void start(CommandSender sender, String name, int kind, Source source) {
        Settings s = plugin.settings();
        Path root = assets(s);
        long maxPixels = s.maxPixels;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                byte[] bytes = source.read();
                BufferedImage img = decode(bytes, maxPixels);
                int w = img.getWidth();
                int h = img.getHeight();
                int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
                img = null;
                int[] pano = Panorama.convert(argb, w, h);
                argb = null;
                int glow = Panorama.glowColor(pano);
                write(root, name, Panorama.withSentinel(pano, kind, glow), Panorama.glowSprite(glow));
                Panorama.Mode mode = Panorama.mode(w, h);
                Bukkit.getScheduler().runTask(plugin, () -> finish(sender, name, kind, w, h, mode));
            } catch (Fail f) {
                Bukkit.getScheduler().runTask(plugin, () -> failed(sender, name, f.key, f.detail));
            } catch (Exception | OutOfMemoryError ex) {
                plugin.getLogger().warning("Image " + name + " failed: " + ex);
                String d = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                Bukkit.getScheduler().runTask(plugin, () -> failed(sender, name, "image-failed", d));
            }
        });
    }

    private void failed(CommandSender sender, String name, String key, String detail) {
        busy.remove(name);
        pending.remove(name);
        sender.sendMessage(plugin.settings().msg(key, Placeholder.unparsed("image", name),
            Placeholder.unparsed("value", detail == null ? "" : detail)));
    }

    static boolean publicAddress(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
            || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 255;
            int b1 = b[1] & 255;
            int b2 = b[2] & 255;
            if (b0 == 0 || b0 >= 224) {
                return false;
            }
            if (b0 == 100 && (b1 & 0xC0) == 64) {
                return false;
            }
            if (b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2)) {
                return false;
            }
            if (b0 == 198 && (b1 == 18 || b1 == 19 || (b1 == 51 && b2 == 100))) {
                return false;
            }
            return !(b0 == 203 && b1 == 0 && b2 == 113);
        }
        if ((b[0] & 0xFE) == 0xFC) {
            return false;
        }
        if ((b[0] & 255) == 0x20 && (b[1] & 255) == 0x01 && (b[2] & 255) == 0x0D && (b[3] & 255) == 0xB8) {
            return false;
        }
        boolean mapped = true;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                mapped = false;
                break;
            }
        }
        if (mapped && (b[10] & 255) == 0xFF && (b[11] & 255) == 0xFF) {
            try {
                return publicAddress(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
            } catch (UnknownHostException ex) {
                return false;
            }
        }
        return true;
    }

    static void checkTarget(URI u) throws Fail {
        if (u.getScheme() == null || !u.getScheme().equalsIgnoreCase("https") || u.getHost() == null) {
            throw new Fail("image-bad-url", u.toString());
        }
        String host = u.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new Fail("image-failed", "unknown host " + host);
        }
        for (InetAddress a : all) {
            if (!publicAddress(a)) {
                throw new Fail("image-private", host);
            }
        }
    }

    private static byte[] download(URI uri, long limit, int timeout) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .proxy(ProxySelector.getDefault()).connectTimeout(Duration.ofSeconds(timeout)).build();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        URI cur = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkTarget(cur);
            Fetched f = fetch(client, cur, limit, deadline);
            if (f.location() == null) {
                return f.body();
            }
            try {
                cur = cur.resolve(f.location().trim());
            } catch (IllegalArgumentException ex) {
                throw new Fail("image-bad-url", f.location());
            }
        }
        throw new Fail("image-http", "redirects > " + MAX_REDIRECTS);
    }

    record Fetched(int status, byte[] body, String location) {
    }

    static final class Limited implements HttpResponse.BodySubscriber<byte[]> {
        private final long limit;
        private final long declared;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private volatile Flow.Subscription sub;
        private long total;

        Limited(long limit, long declared) {
            this.limit = limit;
            this.declared = declared;
        }

        void abort(Throwable t) {
            result.completeExceptionally(t);
            Flow.Subscription s = sub;
            if (s != null) {
                s.cancel();
            }
        }

        private Fail tooBig() {
            return new Fail("image-too-big", String.valueOf(limit / 1024 / 1024));
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            sub = subscription;
            if (declared > limit) {
                abort(tooBig());
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer bb : items) {
                int n = bb.remaining();
                total += n;
                if (total > limit) {
                    abort(tooBig());
                    return;
                }
                byte[] chunk = new byte[n];
                bb.get(chunk);
                out.write(chunk, 0, n);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }
    }

    private static boolean redirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    static Fetched fetch(HttpClient client, URI uri, long limit, long deadline) throws Exception {
        long left = deadline - System.nanoTime();
        String secs = String.valueOf(Math.max(1L, TimeUnit.NANOSECONDS.toSeconds(left)));
        if (left <= 0) {
            throw new Fail("image-timeout", "0");
        }
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(left))
            .header("User-Agent", "coreshaders").header("Accept", "image/png,image/jpeg").GET().build();
        AtomicReference<Limited> body = new AtomicReference<>();
        CompletableFuture<HttpResponse<byte[]>> fut = client.sendAsync(req, info -> {
            if (info.statusCode() / 100 != 2) {
                return HttpResponse.BodySubscribers.replacing(null);
            }
            Limited l = new Limited(limit, info.headers().firstValueAsLong("Content-Length").orElse(-1L));
            body.set(l);
            return l;
        });
        HttpResponse<byte[]> resp;
        try {
            resp = fut.get(left, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            Fail f = new Fail("image-timeout", secs);
            Limited l = body.get();
            if (l != null) {
                l.abort(f);
            }
            fut.cancel(true);
            throw f;
        } catch (InterruptedException ex) {
            fut.cancel(true);
            Thread.currentThread().interrupt();
            throw ex;
        } catch (ExecutionException ex) {
            Throwable c = ex.getCause();
            for (Throwable t = c; t != null; t = t.getCause()) {
                if (t instanceof Fail f) {
                    throw f;
                }
            }
            if (c instanceof Exception e) {
                throw e;
            }
            throw ex;
        }
        int st = resp.statusCode();
        if (redirect(st)) {
            String loc = resp.headers().firstValue("Location").orElse(null);
            if (loc == null || loc.isBlank()) {
                throw new Fail("image-http", String.valueOf(st));
            }
            return new Fetched(st, null, loc);
        }
        if (st / 100 != 2 || resp.body() == null) {
            throw new Fail("image-http", String.valueOf(st));
        }
        return new Fetched(st, resp.body(), null);
    }

    static BufferedImage decode(byte[] bytes, long maxPixels) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                throw new Fail("image-not-image", "");
            }
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) {
                throw new Fail("image-not-image", "");
            }
            ImageReader r = it.next();
            try {
                String fmt = r.getFormatName().toLowerCase(Locale.ROOT);
                if (!fmt.equals("png") && !fmt.equals("jpeg") && !fmt.equals("jpg")) {
                    throw new Fail("image-not-image", fmt);
                }
                r.setInput(in, true, true);
                long px = (long) r.getWidth(0) * r.getHeight(0);
                if (px > maxPixels || r.getWidth(0) < 2 || r.getHeight(0) < 2) {
                    throw new Fail("image-too-large", r.getWidth(0) + "x" + r.getHeight(0));
                }
                BufferedImage img = r.read(0);
                if (img == null) {
                    throw new Fail("image-not-image", fmt);
                }
                return img;
            } catch (IOException ex) {
                throw new Fail("image-not-image", ex.getMessage());
            } finally {
                r.dispose();
            }
        }
    }

    private static BufferedImage image(int[] argb, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        return img;
    }

    private static void writeFile(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", out)) {
            throw new IOException("no png writer");
        }
        return out.toByteArray();
    }

    static void write(Path root, String name, int[] pano, int[] glow) throws IOException {
        List<String> p = PackFiles.paths(name);
        writeFile(root.resolve(p.get(0)), png(image(pano, Panorama.W, Panorama.H)));
        writeFile(root.resolve(p.get(1)), png(image(glow, Panorama.GLOW, Panorama.GLOW)));
        writeFile(root.resolve(p.get(2)), PackFiles.json(PackFiles.cubeModel(name)).getBytes(StandardCharsets.UTF_8));
        writeFile(root.resolve(p.get(3)), PackFiles.json(PackFiles.planeModel(name)).getBytes(StandardCharsets.UTF_8));
        writeFile(root.resolve(p.get(4)),
            PackFiles.json(PackFiles.itemDefinition("cube_" + name, Faces.CUBE_TINTS)).getBytes(StandardCharsets.UTF_8));
        writeFile(root.resolve(p.get(5)),
            PackFiles.json(PackFiles.itemDefinition("plane_" + name, Faces.PLANE_TINTS)).getBytes(StandardCharsets.UTF_8));
    }

    private void finish(CommandSender sender, String name, int kind, int w, int h, Panorama.Mode mode) {
        busy.remove(name);
        pending.remove(name);
        FileConfiguration c = plugin.getConfig();
        List<String> imgs = new ArrayList<>(plugin.settings().images);
        if (!imgs.contains(name)) {
            imgs.add(name);
        }
        c.set("images", imgs);
        if (!c.isSet("image-names." + name)) {
            c.set("image-names." + name, name);
        }
        c.set("custom-images.kinds." + name, kind);
        plugin.saveConfig();
        plugin.reload();
        Settings s = plugin.settings();
        sender.sendMessage(s.msg("image-added", Placeholder.unparsed("image", name),
            Placeholder.unparsed("kind", String.valueOf(kind)), Placeholder.unparsed("size", w + "x" + h),
            Placeholder.unparsed("mode", mode == Panorama.Mode.PANORAMA ? "panorama" : "mirror"),
            Placeholder.unparsed("path", assets(s).toString())));
        reloadPack(sender);
    }

    public void delete(CommandSender sender, String rawName) {
        Settings s = plugin.settings();
        String name = rawName.toLowerCase(Locale.ROOT);
        if (!s.isCustom(name)) {
            sender.sendMessage(s.msg("image-not-custom", Placeholder.unparsed("image", name)));
            return;
        }
        if (!busy.add(name)) {
            sender.sendMessage(s.msg("image-busy", Placeholder.unparsed("image", name)));
            return;
        }
        Path root = assets(s);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int removed = 0;
            for (String rel : PackFiles.paths(name)) {
                try {
                    if (Files.deleteIfExists(root.resolve(rel))) {
                        removed++;
                    }
                } catch (IOException ex) {
                    plugin.getLogger().warning("Cannot delete " + rel + ": " + ex.getMessage());
                }
            }
            int files = removed;
            Bukkit.getScheduler().runTask(plugin, () -> deleted(sender, name, files));
        });
    }

    private void deleted(CommandSender sender, String name, int files) {
        busy.remove(name);
        FileConfiguration c = plugin.getConfig();
        List<String> imgs = new ArrayList<>(plugin.settings().images);
        imgs.remove(name);
        c.set("images", imgs);
        c.set("image-names." + name, null);
        c.set("custom-images.kinds." + name, null);
        plugin.saveConfig();
        plugin.reload();
        Settings s = plugin.settings();
        int changed = 0;
        for (World w : Bukkit.getWorlds()) {
            for (ItemDisplay d : plugin.effects().all(w)) {
                if (name.equals(plugin.effects().storedImage(d))) {
                    plugin.effects().update(d, plugin.effects().data(d).withImage(s.defaultImage));
                    changed++;
                }
            }
        }
        sender.sendMessage(s.msg("image-deleted", Placeholder.unparsed("image", name),
            Placeholder.unparsed("files", String.valueOf(files)), Placeholder.unparsed("count", String.valueOf(changed))));
        reloadPack(sender);
    }

    public void reloadPack(CommandSender sender) {
        Settings s = plugin.settings();
        if (s.reloadCommands.isEmpty()) {
            sender.sendMessage(s.msg("pack-manual"));
            return;
        }
        if (Bukkit.getPluginManager().getPlugin(s.packPlugin) == null) {
            sender.sendMessage(s.msg("pack-no-plugin", Placeholder.unparsed("plugin", s.packPlugin)));
            return;
        }
        for (String raw : s.reloadCommands) {
            String cmd = Actions.clean(raw);
            String label = cmd.split(" ", 2)[0];
            TagResolver r = Placeholder.unparsed("command", cmd);
            if (Bukkit.getCommandMap().getCommand(label) == null) {
                sender.sendMessage(s.msg("pack-reload-failed", r));
                continue;
            }
            try {
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                sender.sendMessage(s.msg(ok ? "pack-reloading" : "pack-reload-failed", r));
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Pack reload command failed: " + ex);
                sender.sendMessage(s.msg("pack-reload-failed", r));
            }
        }
    }
}
