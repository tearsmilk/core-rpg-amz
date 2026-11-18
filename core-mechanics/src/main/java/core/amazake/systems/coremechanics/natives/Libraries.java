package core.amazake.systems.coremechanics.natives;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Jar-ы jolt-jni в systems/core-mechanics/lib/. Список (имя, размер, sha256, путь в Maven Central) — libs.txt из .azs.
 * Нет файла или не тот sha256 → распаковать из .azs (bundled-lib/, если jar-ы вшиты) или скачать из Maven Central;
 * файл пишется во временный и переименовывается только после сверки sha256.
 */
public final class Libraries {

    /** Одна строка libs.txt. */
    public record Lib(String file, long size, String sha256, String mavenPath) {
    }

    /** Откуда взять jar, которого нет в lib/: из .azs (null — не вшит). */
    @FunctionalInterface
    public interface Bundled {
        InputStream open(String file) throws IOException;
    }

    private Libraries() {
    }

    public static List<Lib> parse(final String text) {
        final List<Lib> out = new ArrayList<>();
        for (final String raw : text.split("\n")) {
            final String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final String[] p = line.split("\\s+");
            if (p.length != 4 || !p[0].endsWith(".jar") || p[0].contains("/") || p[0].contains("\\") || p[0].startsWith(".")
                || !p[2].matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("libs.txt: плохая строка «" + line + "»");
            }
            out.add(new Lib(p[0], Long.parseLong(p[1]), p[2].toLowerCase(Locale.ROOT), p[3]));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("libs.txt пуст");
        }
        return out;
    }

    /** Отпечаток набора библиотек (общий загрузчик создаётся заново только при смене набора). */
    public static String fingerprint(final List<Lib> libs) {
        final StringBuilder b = new StringBuilder();
        for (final Lib l : libs) {
            b.append(l.file()).append(':').append(l.sha256()).append(';');
        }
        return sha256(b.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    /** Файл есть, размер и sha256 совпадают. */
    public static boolean valid(final Path dir, final Lib l) {
        final Path f = dir.resolve(l.file());
        try {
            if (!Files.isRegularFile(f) || Files.size(f) != l.size()) {
                return false;
            }
            try (InputStream in = Files.newInputStream(f)) {
                return l.sha256().equals(sha256(in));
            }
        } catch (final IOException e) {
            return false;
        }
    }

    /**
     * Привести lib/ в порядок: каждый jar проверить, недостающие/битые — из .azs, иначе скачать (если можно).
     * Вызывать вне тиков.
     */
    public static void ensure(final Path dir, final List<Lib> libs, final Bundled bundled, final boolean download,
                              final List<String> repositories, final Consumer<String> log) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        for (final Lib l : libs) {
            if (valid(dir, l)) {
                continue;
            }
            IOException last = null;
            try (InputStream in = bundled == null ? null : bundled.open(l.file())) {
                if (in != null) {
                    write(dir, l, in);
                    log.accept("распакован из .azs: " + l.file() + " (" + (l.size() >> 10) + " КБ)");
                    continue;
                }
            } catch (final IOException e) {
                last = e;
            }
            if (!download) {
                throw new IOException("нет " + l.file() + " в " + dir + " (автоскачивание выключено: libraries.auto-download)"
                    + (last != null ? ": " + last.getMessage() : ""));
            }
            final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
            boolean ok = false;
            for (final String repo : repositories) {
                final String base = repo.endsWith("/") ? repo : repo + "/";
                try {
                    final HttpRequest req = HttpRequest.newBuilder(URI.create(base + l.mavenPath())).timeout(Duration.ofMinutes(5)).GET().build();
                    final HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    if (resp.statusCode() != 200) {
                        resp.body().close();
                        throw new IOException("HTTP " + resp.statusCode() + " для " + base + l.mavenPath());
                    }
                    try (InputStream in = resp.body()) {
                        write(dir, l, in);
                    }
                    log.accept("скачан " + l.file() + " (" + (l.size() >> 10) + " КБ) из " + base);
                    ok = true;
                    break;
                } catch (final IOException e) {
                    last = e;
                }
            }
            if (!ok) {
                throw new IOException("не удалось получить " + l.file() + ": " + (last == null ? "нет репозиториев" : last.getMessage()));
            }
        }
    }

    /** Записать поток в lib/&lt;file&gt; через .part со сверкой размера и sha256. */
    static void write(final Path dir, final Lib l, final InputStream src) throws IOException {
        final Path part = dir.resolve(l.file() + ".part");
        final MessageDigest sha = digest();
        try (InputStream in = new DigestInputStream(src, sha); OutputStream out = Files.newOutputStream(part)) {
            in.transferTo(out);
        }
        final String got = HexFormat.of().formatHex(sha.digest());
        if (!got.equals(l.sha256()) || Files.size(part) != l.size()) {
            Files.deleteIfExists(part);
            throw new IOException("sha256 не совпал у " + l.file() + " (ожидалось " + l.sha256() + ", получено " + got + ")");
        }
        try {
            Files.move(part, dir.resolve(l.file()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(part, dir.resolve(l.file()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String sha256(final InputStream in) throws IOException {
        final MessageDigest sha = digest();
        final byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            sha.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    static String sha256(final byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
