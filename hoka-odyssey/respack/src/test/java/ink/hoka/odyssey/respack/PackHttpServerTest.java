package ink.hoka.odyssey.respack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PackHttpServerTest {
    private PackHttpServer http;
    private final HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @BeforeEach
    void start() throws Exception {
        http = new PackHttpServer("127.0.0.1", 0);
    }

    @AfterEach
    void stop() {
        http.stop();
    }

    private HttpResponse<byte[]> get(String path, String method) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.port() + path))
            .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return client.send(r, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void servesKnownPacksOnly() throws Exception {
        byte[] one = {1, 2, 3};
        byte[] two = {4, 5};
        byte[] three = {6};
        http.publish("aaa", one);
        assertEquals(404, get("/pack/bbb.zip", "GET").statusCode());
        HttpResponse<byte[]> r = get(PackHttpServer.path("aaa"), "GET");
        assertEquals(200, r.statusCode());
        assertEquals("application/zip", r.headers().firstValue("Content-Type").orElse(""));
        assertArrayEquals(one, r.body());
        HttpResponse<byte[]> head = get(PackHttpServer.path("aaa"), "HEAD");
        assertEquals(200, head.statusCode());
        assertEquals("3", head.headers().firstValue("Content-Length").orElse(""));
        assertEquals(405, get(PackHttpServer.path("aaa"), "POST").statusCode());
        assertEquals(404, get("/", "GET").statusCode());

        http.publish("bbb", two);                                   // старый ещё отдаётся (докачка)
        assertArrayEquals(one, get("/pack/aaa.zip", "GET").body());
        http.publish("ccc", three);                                 // хранится только два последних
        assertEquals(404, get("/pack/aaa.zip", "GET").statusCode());
        assertArrayEquals(two, get("/pack/bbb.zip", "GET").body());
        assertArrayEquals(three, get("/pack/ccc.zip", "GET").body());
    }
}
