package ink.hoka.odyssey.respack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Встроенный HTTP (com.sun.net.httpserver из JDK) — отдаёт zip пака: {@code GET /pack/<sha1>.zip}.
 * SHA-1 в адресе — чтобы клиент не взял старый пак из кэша. Хранит два последних пака: тот, кто начал качать
 * перед пересборкой, докачает свой. Свой поток-диспетчер JDK и виртуальные потоки на запросы; {@link #stop()} — в onDisable.
 */
final class PackHttpServer {

    private static final int KEEP = 2;

    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, byte[]> packs = new LinkedHashMap<>();

    PackHttpServer(String bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 64);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    int port() { return server.getAddress().getPort(); }

    synchronized void publish(String sha1, byte[] zip) {
        packs.remove(sha1);
        packs.put(sha1, zip);
        while (packs.size() > KEEP) packs.remove(packs.keySet().iterator().next());
    }

    private synchronized byte[] find(String sha1) { return packs.get(sha1); }

    static String path(String sha1) { return "/pack/" + sha1 + ".zip"; }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String method = ex.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                ex.getResponseHeaders().set("Allow", "GET, HEAD");
                ex.sendResponseHeaders(405, -1);
                return;
            }
            String p = ex.getRequestURI().getPath();
            byte[] zip = null;
            if (p.startsWith("/pack/") && p.endsWith(".zip")) zip = find(p.substring(6, p.length() - 4));
            if (zip == null) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "application/zip");
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            if (method.equals("HEAD")) {
                ex.getResponseHeaders().set("Content-Length", String.valueOf(zip.length));
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, zip.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(zip); }
        }
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
