package com.hanafubuki.updater.testing;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A real HTTP server on localhost for tests. Routes are matched on the request path
 * (without query). Every request is recorded with its headers and query.
 *
 * <pre>
 * try (FakeHttpServer server = FakeHttpServer.start()) {
 *     server.json("/v2/project/abc/version", "[...]");
 *     server.route("/file.jar", req -> FakeHttpServer.Reply.bytes(200, jarBytes));
 *     URI base = server.uri("/v2");
 * }
 * </pre>
 */
public final class FakeHttpServer implements AutoCloseable {

    /** A recorded request. Header names are lower-case. */
    public record Request(String method, String path, String query, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    /** A canned reply. */
    public record Reply(int status, Map<String, String> headers, byte[] body) {
        public static Reply json(int status, String json) {
            return new Reply(status, Map.of("Content-Type", "application/json"), json.getBytes(StandardCharsets.UTF_8));
        }

        public static Reply text(int status, String text) {
            return new Reply(status, Map.of("Content-Type", "text/plain"), text.getBytes(StandardCharsets.UTF_8));
        }

        public static Reply bytes(int status, byte[] body) {
            return new Reply(status, Map.of("Content-Type", "application/octet-stream"), body);
        }

        public static Reply redirect(String location) {
            return new Reply(302, Map.of("Location", location), new byte[0]);
        }

        public static Reply status(int status) {
            return new Reply(status, Map.of(), new byte[0]);
        }

        /** Same reply with an extra header. */
        public Reply withHeader(String name, String value) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put(name, value);
            return new Reply(status, h, body);
        }
    }

    private final HttpServer server;
    private final Map<String, Function<Request, Reply>> routes = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private FakeHttpServer(HttpServer server) {
        this.server = server;
    }

    public static FakeHttpServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeHttpServer fake = new FakeHttpServer(server);
        server.createContext("/", fake::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        return fake;
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            Map<String, String> headers = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.isEmpty() ? "" : v.get(0)));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            URI uri = ex.getRequestURI();
            Request req = new Request(ex.getRequestMethod(), uri.getPath(), uri.getRawQuery(), headers, body);
            requests.add(req);
            Function<Request, Reply> route = routes.get(uri.getPath());
            Reply reply = route == null ? Reply.text(404, "no route for " + uri.getPath()) : route.apply(req);
            reply.headers().forEach((k, v) -> ex.getResponseHeaders().set(k, v));
            if (reply.body().length == 0) {
                ex.sendResponseHeaders(reply.status(), -1);
            } else {
                ex.sendResponseHeaders(reply.status(), reply.body().length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(reply.body());
                }
            }
        }
    }

    /** Registers a handler for a path. Later registrations replace earlier ones. */
    public FakeHttpServer route(String path, Function<Request, Reply> handler) {
        routes.put(path, handler);
        return this;
    }

    /** Always answers 200 with this JSON. */
    public FakeHttpServer json(String path, String json) {
        return route(path, r -> Reply.json(200, json));
    }

    /** Answers with the given replies in order; the last one repeats. */
    public FakeHttpServer sequence(String path, Reply... replies) {
        List<Reply> list = new ArrayList<>(List.of(replies));
        return route(path, r -> list.size() > 1 ? list.remove(0) : list.get(0));
    }

    /** http://127.0.0.1:port + path */
    public URI uri(String path) {
        return URI.create(base() + path);
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Request> requests() {
        return Collections.unmodifiableList(requests);
    }

    /** Requests for one path. */
    public List<Request> requests(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
