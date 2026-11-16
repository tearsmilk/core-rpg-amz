package core.amazake.systems.corepython;

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
import java.util.function.Consumer;

/**
 * Jar-ы GraalPy в systems/core-python/lib/. Список (имя, размер, sha256, путь в Maven Central) — libs.txt из .azs.
 * Недостающие или битые (не тот размер) файлы докачиваются из Maven Central и проверяются по sha256.
 */
final class Libraries {

    /** Одна строка libs.txt. */
    record Lib(String file, long size, String sha256, String mavenPath) {
    }

    private Libraries() {
    }

    static List<Lib> parse(final String text) {
        final List<Lib> out = new ArrayList<>();
        for (final String raw : text.split("\n")) {
            final String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final String[] p = line.split("\\s+");
            if (p.length != 4 || !p[0].endsWith(".jar") || p[0].contains("/") || p[0].contains("\\")) {
                throw new IllegalArgumentException("libs.txt: плохая строка «" + line + "»");
            }
            out.add(new Lib(p[0], Long.parseLong(p[1]), p[2].toLowerCase(java.util.Locale.ROOT), p[3]));
        }
        return out;
    }

    /** Отпечаток набора библиотек: при смене версии GraalPy общий загрузчик создаётся заново. */
    static String fingerprint(final List<Lib> libs) {
        final StringBuilder b = new StringBuilder();
        for (final Lib l : libs) {
            b.append(l.file()).append(':').append(l.sha256()).append(';');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Файлы, которых нет или у которых не тот размер. */
    static List<Lib> missing(final Path dir, final List<Lib> libs) {
        final List<Lib> out = new ArrayList<>();
        for (final Lib l : libs) {
            final Path f = dir.resolve(l.file());
            try {
                if (!Files.isRegularFile(f) || Files.size(f) != l.size()) {
                    out.add(l);
                }
            } catch (final IOException e) {
                out.add(l);
            }
        }
        return out;
    }

    /** Скачивает недостающие jar-ы (вызывать вне тиков). Файл пишется во временный и переименовывается после проверки sha256. */
    static void download(final Path dir, final List<Lib> todo, final String repository, final Consumer<String> log) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
        final String base = repository.endsWith("/") ? repository : repository + "/";
        long total = 0;
        for (final Lib l : todo) {
            total += l.size();
        }
        log.accept("скачиваю библиотеки GraalPy: " + todo.size() + " файлов, " + (total >> 20) + " МБ из " + base);
        for (final Lib l : todo) {
            final Path part = dir.resolve(l.file() + ".part");
            final HttpRequest req = HttpRequest.newBuilder(URI.create(base + l.mavenPath())).timeout(Duration.ofMinutes(10)).GET().build();
            final HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                resp.body().close();
                throw new IOException("HTTP " + resp.statusCode() + " для " + base + l.mavenPath());
            }
            final MessageDigest sha;
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (final NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            try (InputStream in = new DigestInputStream(resp.body(), sha); OutputStream out = Files.newOutputStream(part)) {
                in.transferTo(out);
            }
            final String got = HexFormat.of().formatHex(sha.digest());
            if (!got.equals(l.sha256()) || Files.size(part) != l.size()) {
                Files.deleteIfExists(part);
                throw new IOException("sha256 не совпал у " + l.file() + " (ожидалось " + l.sha256() + ", получено " + got + ")");
            }
            Files.move(part, dir.resolve(l.file()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.accept("  " + l.file() + " (" + (l.size() >> 10) + " КБ) — ok");
        }
    }
}
