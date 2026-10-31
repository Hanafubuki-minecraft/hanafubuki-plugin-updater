package com.hanafubuki.updater.health;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Small HTTP endpoint for monitoring: {@code GET /health} returns JSON status,
 * {@code GET /metrics} returns Prometheus text.
 *
 * <p>Listens on 127.0.0.1 unless configured otherwise, uses its own thread and answers only GET.
 */
public final class HealthServer {

    private final HttpServer server;
    private final ExecutorService executor;

    private HealthServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /**
     * Starts the server.
     *
     * @param status  JSON object returned by /health
     * @param metrics Prometheus text returned by /metrics
     */
    public static HealthServer start(String bindAddress, int port, Supplier<Map<String, Object>> status,
                                     Supplier<String> metrics) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(bindAddress, port), 16);
        Gson gson = new Gson();
        server.createContext("/health", exchange -> respond(exchange, "application/json", () -> gson.toJson(status.get())));
        server.createContext("/metrics", exchange -> respond(exchange, "text/plain; version=0.0.4", metrics));
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HanafubukiUpdater-health");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.start();
        return new HealthServer(server, executor);
    }

    private static void respond(HttpExchange exchange, String contentType, Supplier<String> body) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (!path.equals(exchange.getHttpContext().getPath())) {
                // Contexts match by prefix; only the exact paths exist.
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] bytes;
            int status = 200;
            try {
                bytes = body.get().getBytes(StandardCharsets.UTF_8);
            } catch (RuntimeException e) {
                bytes = "{\"status\":\"ERROR\"}".getBytes(StandardCharsets.UTF_8);
                status = 500;
            }
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }
}
