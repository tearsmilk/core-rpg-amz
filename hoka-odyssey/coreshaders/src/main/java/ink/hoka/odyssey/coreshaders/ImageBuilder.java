package ink.hoka.odyssey.coreshaders;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Своя картинка: скачать (только https на публичный адрес, с пределом размера и времени), превратить PNG/JPG/GIF в
 * панораму 1024x512 (или анимацию: кадры столбиком + .mcmeta) с подписью для шейдера и записать файлы пака.
 * Без Minestom — работает в async и проверяется тестами. Ошибка для игрока — {@link Fail} с ключом сообщения.
 */
final class ImageBuilder {
    static final int MAX_REDIRECTS = 5;

    private ImageBuilder() {
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

    record Anim(int maxFrames, int width, boolean interpolate, long maxTextureBytes, long totalTextureBytes,
        long maxFileBytes, int maxSourceFrames) {
        int height() {
            return width / 2;
        }
    }

    record Built(int width, int height, Panorama.Mode mode, int frameW, int frameH, int[] ticks,
        boolean interpolate, byte[] png, int glow) {
        boolean animated() {
            return ticks != null;
        }

        long textureBytes() {
            return (long) frameW * frameH * (ticks == null ? 1 : ticks.length) * 4L;
        }
    }

    static String mbText(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0);
    }

    static long animatedBytes(Path root, Set<String> names) {
        long sum = 0;
        for (String n : names) {
            if (!Files.isRegularFile(root.resolve(PackFiles.meta(n)))) {
                continue;
            }
            int[] wh = pngSize(root.resolve(PackFiles.paths(n).get(0)));
            if (wh != null) {
                sum += (long) wh[0] * wh[1] * 4L;
            }
        }
        return sum;
    }

    static int[] pngSize(Path file) {
        try (java.io.InputStream in = Files.newInputStream(file)) {
            byte[] h = in.readNBytes(24);
            if (h.length < 24 || (h[0] & 255) != 0x89 || h[1] != 'P' || h[12] != 'I' || h[15] != 'R') {
                return null;
            }
            ByteBuffer bb = ByteBuffer.wrap(h);
            return new int[] {bb.getInt(16), bb.getInt(20)};
        } catch (IOException ex) {
            return null;
        }
    }

    static Built build(byte[] bytes, long maxPixels, int kind, Anim anim) throws Exception {
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
                if (fmt.equals("gif")) {
                    r.setInput(in, false, false);
                    return gif(r, maxPixels, kind, anim);
                }
                if (!fmt.equals("png") && !fmt.equals("jpeg") && !fmt.equals("jpg")) {
                    throw new Fail("image-not-image", fmt);
                }
                r.setInput(in, true, true);
                int w = r.getWidth(0);
                int h = r.getHeight(0);
                checkSize(w, h, maxPixels);
                BufferedImage img = r.read(0);
                if (img == null) {
                    throw new Fail("image-not-image", fmt);
                }
                int[] argb = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
                return still(argb, img.getWidth(), img.getHeight(), kind);
            } catch (IOException ex) {
                throw new Fail("image-not-image", ex.getMessage());
            } finally {
                r.dispose();
            }
        }
    }

    private static void checkSize(int w, int h, long maxPixels) throws Fail {
        if ((long) w * h > maxPixels || w < 2 || h < 2) {
            throw new Fail("image-too-large", w + "x" + h);
        }
    }

    static void checkFrames(Gif.Header hd, long maxPixels) throws Fail {
        long screen = (long) hd.width() * hd.height();
        for (Gif.FrameInfo f : hd.frames()) {
            long area = (long) f.width() * f.height();
            if (area > maxPixels || area > screen * 4L + 65536L) {
                throw new Fail("image-too-large", f.width() + "x" + f.height());
            }
        }
    }

    static Built still(int[] argb, int w, int h, int kind) throws IOException {
        int[] pano = Panorama.convert(argb, w, h);
        int glow = Panorama.glowColor(pano);
        byte[] data = png(image(Panorama.withSentinel(pano, kind, glow), Panorama.W, Panorama.H));
        return new Built(w, h, Panorama.mode(w, h), Panorama.W, Panorama.H, null, false, data, glow);
    }

    static Built gif(ImageReader r, long maxPixels, int kind, Anim anim) throws Exception {
        Gif.Header hd;
        try {
            hd = Gif.header(r, anim.maxSourceFrames());
        } catch (Gif.FrameLimit ex) {
            throw new Fail("image-anim-frames", ex.count + " > " + anim.maxSourceFrames());
        }
        int w = hd.width();
        int h = hd.height();
        checkSize(w, h, maxPixels);
        checkFrames(hd, maxPixels);
        int count = hd.frames().size();
        int fw = anim.width();
        int fh = anim.height();
        int fit = (int) Math.min(anim.maxFrames(), anim.maxTextureBytes() / ((long) fw * fh * 4L));
        if (count > 1 && fit < 2) {
            long two = (long) fw * fh * 8L;
            throw new Fail("image-anim-budget", "2 x " + fw + "x" + fh + " = " + mbText(two) + " / "
                + mbText(anim.maxTextureBytes()));
        }
        Gif.Plan plan = count == 1 ? new Gif.Plan(new int[] {0}, new int[] {1}) : Gif.plan(hd.delays(), fit);
        boolean[] wanted = new boolean[count];
        int[] out = new int[count];
        for (int i = 0; i < plan.count(); i++) {
            wanted[plan.sources()[i]] = true;
            out[plan.sources()[i]] = i;
        }
        if (plan.count() == 1) {
            int[][] one = new int[1][];
            Gif.decode(r, hd, wanted, (i, canvas, cw, ch) -> one[0] = canvas.clone());
            return still(one[0], w, h, kind);
        }
        int n = plan.count();
        long tex = (long) fw * fh * n * 4L;
        if (tex > anim.maxTextureBytes()) {
            throw new Fail("image-anim-budget", n + " x " + fw + "x" + fh + " = " + mbText(tex) + " / "
                + mbText(anim.maxTextureBytes()));
        }
        int frame = fw * fh;
        int[] strip = new int[frame * n];
        Gif.decode(r, hd, wanted, (i, canvas, cw, ch) ->
            System.arraycopy(Panorama.convert(canvas, cw, ch, fw, fh), 0, strip, out[i] * frame, frame));
        int glow = Panorama.glowColor(strip);
        for (int k = 0; k < n; k++) {
            int[] f = Arrays.copyOfRange(strip, k * frame, (k + 1) * frame);
            System.arraycopy(Panorama.withSentinel(f, fw, fh, kind, glow), 0, strip, k * frame, frame);
        }
        byte[] data = png(image(strip, fw, fh * n));
        if (data.length > anim.maxFileBytes()) {
            throw new Fail("image-anim-file", mbText(data.length) + " / " + mbText(anim.maxFileBytes()));
        }
        return new Built(w, h, Panorama.mode(w, h), fw, fh, plan.ticks(), anim.interpolate(), data, glow);
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

    static byte[] download(URI uri, long limit, int timeout) throws Exception {
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
            .header("User-Agent", "hoka-odyssey-coreshaders").header("Accept", "image/png,image/jpeg,image/gif").GET().build();
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
        write(root, name, new Built(Panorama.W, Panorama.H, Panorama.Mode.PANORAMA, Panorama.W, Panorama.H, null,
            false, png(image(pano, Panorama.W, Panorama.H)), glow[2] & 0xFFFFFF));
    }

    static void write(Path root, String name, Built b) throws IOException {
        List<String> p = PackFiles.paths(name);
        Path meta = root.resolve(PackFiles.meta(name));
        if (b.animated()) {
            writeFile(meta, PackFiles.json(PackFiles.animation(b.frameW(), b.frameH(), b.ticks(), b.interpolate()))
                .getBytes(StandardCharsets.UTF_8));
        } else {
            Files.deleteIfExists(meta);
        }
        writeFile(root.resolve(p.get(0)), b.png());
        writeFile(root.resolve(p.get(1)), png(image(Panorama.glowSprite(b.glow()), Panorama.GLOW, Panorama.GLOW)));
        writeFile(root.resolve(p.get(2)), PackFiles.json(PackFiles.cubeModel(name)).getBytes(StandardCharsets.UTF_8));
        writeFile(root.resolve(p.get(3)), PackFiles.json(PackFiles.planeModel(name)).getBytes(StandardCharsets.UTF_8));
        writeFile(root.resolve(p.get(4)),
            PackFiles.json(PackFiles.itemDefinition("cube_" + name, Faces.CUBE_TINTS)).getBytes(StandardCharsets.UTF_8));
        writeFile(root.resolve(p.get(5)),
            PackFiles.json(PackFiles.itemDefinition("plane_" + name, Faces.PLANE_TINTS)).getBytes(StandardCharsets.UTF_8));
    }
}
