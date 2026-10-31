package com.hanafubuki.updater.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.BindException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class HealthServerTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();

    private HealthServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private HealthServer start(java.util.function.Supplier<Map<String, Object>> status,
                               java.util.function.Supplier<String> metrics) throws IOException {
        server = HealthServer.start("127.0.0.1", 0, status, metrics);
        return server;
    }

    private HttpResponse<String> send(String method, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(5))
                .method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString("{}")
                        : HttpRequest.BodyPublishers.noBody())
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthReturnsStatusAsJson() throws Exception {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("pending_updates", 2);
        status.put("plugins", List.of("LuckPerms", "Vault"));
        start(() -> status, () -> "");
        assertTrue(server.port() > 0);

        HttpResponse<String> r = send("GET", "/health");
        assertEquals(200, r.statusCode());
        assertEquals("application/json", r.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElseThrow());
        JsonObject body = JsonParser.parseString(r.body()).getAsJsonObject();
        assertEquals("UP", body.get("status").getAsString());
        assertEquals(2, body.get("pending_updates").getAsInt());
        assertEquals("Vault", body.getAsJsonArray("plugins").get(1).getAsString());
    }

    @Test
    void statusIsReadOnEveryRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(() -> Map.of("calls", calls.incrementAndGet()), () -> "");
        assertEquals(1, JsonParser.parseString(send("GET", "/health").body()).getAsJsonObject().get("calls").getAsInt());
        assertEquals(2, JsonParser.parseString(send("GET", "/health").body()).getAsJsonObject().get("calls").getAsInt());
    }

    @Test
    void metricsReturnsPrometheusText() throws Exception {
        String metrics = "# TYPE hanafubuki_updater_runs_total counter\nhanafubuki_updater_runs_total 3\n";
        start(Map::of, () -> metrics);
        HttpResponse<String> r = send("GET", "/metrics");
        assertEquals(200, r.statusCode());
        assertEquals("text/plain; version=0.0.4", r.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(metrics, r.body());
    }

    @Test
    void utf8BodiesKeepTheirLength() throws Exception {
        start(() -> Map.of("server", "花吹雪"), () -> "");
        HttpResponse<String> r = send("GET", "/health");
        assertEquals("花吹雪", JsonParser.parseString(r.body()).getAsJsonObject().get("server").getAsString());
    }

    @Test
    void onlyGetIsAllowed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(() -> {
            calls.incrementAndGet();
            return Map.of();
        }, () -> "");
        for (String method : List.of("POST", "PUT", "DELETE")) {
            HttpResponse<String> r = send(method, "/health");
            assertEquals(405, r.statusCode(), method);
            assertEquals("GET", r.headers().firstValue("Allow").orElseThrow());
            assertEquals("", r.body());
        }
        assertEquals(405, send("POST", "/metrics").statusCode());
        assertEquals(0, calls.get(), "status is not computed for rejected requests");
    }

    @Test
    void failingSupplierAnswers500() throws Exception {
        start(() -> {
            throw new IllegalStateException("plugin is disabling");
        }, () -> {
            throw new IllegalStateException("no metrics");
        });
        HttpResponse<String> health = send("GET", "/health");
        assertEquals(500, health.statusCode());
        assertEquals("{\"status\":\"ERROR\"}", health.body());
        assertEquals(500, send("GET", "/metrics").statusCode());
        // the server keeps working after a failure
        assertEquals(500, send("GET", "/health").statusCode());
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        start(Map::of, () -> "");
        assertEquals(404, send("GET", "/").statusCode());
        assertEquals(404, send("GET", "/status").statusCode());
    }

    @Test
    void stopClosesThePort() throws Exception {
        start(Map::of, () -> "");
        assertEquals(200, send("GET", "/health").statusCode());
        server.stop();
        assertThrows(IOException.class, () -> send("GET", "/health"));
        server = null;
    }

    @Test
    void portInUseFailsToStart() throws Exception {
        start(Map::of, () -> "");
        assertThrows(BindException.class, () -> HealthServer.start("127.0.0.1", server.port(), Map::of, () -> ""));
    }
}
