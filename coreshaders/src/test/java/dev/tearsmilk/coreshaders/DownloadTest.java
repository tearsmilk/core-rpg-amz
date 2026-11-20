package dev.tearsmilk.coreshaders;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DownloadTest {
    private HttpServer server;
    private String base;

    private static final ProxySelector DIRECT = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, java.io.IOException ioe) {
        }
    };

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/slow", ex -> {
            ex.sendResponseHeaders(200, 1000);
            try (OutputStream out = ex.getResponseBody()) {
                for (int i = 0; i < 1000; i++) {
                    out.write(7);
                    out.flush();
                    Thread.sleep(200);
                }
            } catch (Exception e) {
                ex.close();
            }
        });
        server.createContext("/big", ex -> {
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(new byte[5000]);
            } catch (Exception e) {
                ex.close();
            }
        });
        server.createContext("/declared", ex -> {
            ex.sendResponseHeaders(200, 5000);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(new byte[5000]);
            } catch (Exception e) {
                ex.close();
            }
        });
        server.createContext("/ok", ex -> {
            byte[] b = {1, 2, 3, 4};
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        });
        server.createContext("/moved", ex -> {
            ex.getResponseHeaders().add("Location", "https://127.0.0.1/secret");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/missing", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().proxy(DIRECT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static long deadline(double seconds) {
        return System.nanoTime() + (long) (seconds * TimeUnit.SECONDS.toNanos(1));
    }

    @Test
    void slowBodyHitsDeadline() {
        long t0 = System.nanoTime();
        ImageManager.Fail f = assertThrows(ImageManager.Fail.class,
            () -> ImageManager.fetch(client(), URI.create(base + "/slow"), 1 << 20, deadline(1.0)));
        assertEquals("image-timeout", f.key);
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(4));
    }

    @Test
    void sizeLimitWithoutLength() {
        ImageManager.Fail f = assertThrows(ImageManager.Fail.class,
            () -> ImageManager.fetch(client(), URI.create(base + "/big"), 1000, deadline(5)));
        assertEquals("image-too-big", f.key);
    }

    @Test
    void sizeLimitDeclared() {
        ImageManager.Fail f = assertThrows(ImageManager.Fail.class,
            () -> ImageManager.fetch(client(), URI.create(base + "/declared"), 1000, deadline(5)));
        assertEquals("image-too-big", f.key);
    }

    @Test
    void okBody() throws Exception {
        ImageManager.Fetched r = ImageManager.fetch(client(), URI.create(base + "/ok"), 1000, deadline(5));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, r.body());
        assertNull(r.location());
    }

    @Test
    void redirectIsReturnedNotFollowed() throws Exception {
        ImageManager.Fetched r = ImageManager.fetch(client(), URI.create(base + "/moved"), 1000, deadline(5));
        assertEquals("https://127.0.0.1/secret", r.location());
        assertNull(r.body());
        ImageManager.Fail f = assertThrows(ImageManager.Fail.class,
            () -> ImageManager.checkTarget(URI.create(r.location())));
        assertEquals("image-private", f.key);
    }

    @Test
    void httpError() {
        ImageManager.Fail f = assertThrows(ImageManager.Fail.class,
            () -> ImageManager.fetch(client(), URI.create(base + "/missing"), 1000, deadline(5)));
        assertEquals("image-http", f.key);
        assertEquals("404", f.detail);
    }

    @Test
    void privateAddressesRejected() throws Exception {
        for (String a : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "0.0.0.0",
            "100.64.0.1", "224.0.0.1", "::1", "fd00::1", "fe80::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1")) {
            assertFalse(ImageManager.publicAddress(InetAddress.getByName(a)), a);
        }
        for (String a : List.of("8.8.8.8", "1.1.1.1", "2606:4700::1111", "151.101.1.69")) {
            assertTrue(ImageManager.publicAddress(InetAddress.getByName(a)), a);
        }
    }

    @Test
    void targetChecks() {
        assertEquals("image-bad-url", assertThrows(ImageManager.Fail.class,
            () -> ImageManager.checkTarget(URI.create("http://example.com/a.png"))).key);
        assertEquals("image-private", assertThrows(ImageManager.Fail.class,
            () -> ImageManager.checkTarget(URI.create("https://[::1]/a.png"))).key);
        assertEquals("image-private", assertThrows(ImageManager.Fail.class,
            () -> ImageManager.checkTarget(URI.create("https://169.254.169.254/latest"))).key);
        assertEquals("image-private", assertThrows(ImageManager.Fail.class,
            () -> ImageManager.checkTarget(URI.create("https://localhost/a.png"))).key);
    }
}
