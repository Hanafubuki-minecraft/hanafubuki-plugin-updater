package com.hanafubuki.updater.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hanafubuki.updater.testing.FakeHttpServer;
import com.hanafubuki.updater.testing.FakeHttpServer.Reply;
import com.hanafubuki.updater.testing.TestHttp;
import com.sun.net.httpserver.HttpServer;

class HttpServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path dir;

    private FakeHttpServer server;
    private HttpService http;

    @BeforeEach
    void setUp() throws IOException {
        TestHttp.SLEEPS.clear();
        server = FakeHttpServer.start();
        http = TestHttp.service();
    }

    @AfterEach
    void tearDown() {
        server.close();
        // Never leak an interrupt into the next test.
        Thread.interrupted();
    }

    // --- retries -------------------------------------------------------------------------------

    @Test
    void retriesOn503ThenSucceeds() throws IOException {
        server.sequence("/api", Reply.status(503), Reply.json(200, "{\"ok\":true}"));
        HttpService.Response r = http.get(server.uri("/api"), Map.of(), TIMEOUT);
        assertEquals(200, r.status());
        assertEquals("{\"ok\":true}", r.body());
        assertFalse(r.fromCache());
        assertEquals(2, server.requests("/api").size());
        assertEquals(List.of(0L), TestHttp.SLEEPS);
    }

    @Test
    void retriesOn429HonouringRetryAfter() throws IOException {
        server.sequence("/api", Reply.status(429).withHeader("Retry-After", "2"), Reply.text(200, "done"));
        assertEquals("done", http.getOk(server.uri("/api"), Map.of(), TIMEOUT));
        assertEquals(List.of(2000L), TestHttp.SLEEPS);
    }

    @Test
    void retryAfterIsCappedAtOneMinute() throws IOException {
        server.sequence("/api", Reply.status(503).withHeader("Retry-After", "3600"), Reply.text(200, "done"));
        http.getOk(server.uri("/api"), Map.of(), TIMEOUT);
        assertEquals(List.of(60_000L), TestHttp.SLEEPS);
    }

    @Test
    void unparsableRetryAfterFallsBackToBackoff() throws IOException {
        server.sequence("/api", Reply.status(429).withHeader("Retry-After", "soon"), Reply.text(200, "done"));
        http.getOk(server.uri("/api"), Map.of(), TIMEOUT);
        // The test policy has a base delay of 0.
        assertEquals(List.of(0L), TestHttp.SLEEPS);
    }

    @Test
    void retryAfterAsHttpDate() {
        String inTenSeconds = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(10));
        long ms = HttpService.parseRetryAfterMs(inTenSeconds);
        assertTrue(ms > 8000 && ms <= 10_000, "got " + ms);
        assertEquals(0, HttpService.parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"), "dates in the past mean 'now'");
    }

    @Test
    void parseRetryAfter() {
        assertEquals(2000, HttpService.parseRetryAfterMs("2"));
        assertEquals(5000, HttpService.parseRetryAfterMs(" 5 "));
        assertEquals(0, HttpService.parseRetryAfterMs("-3"));
        assertEquals(-1, HttpService.parseRetryAfterMs("soon"));
    }

    @Test
    void givesUpAfterMaxRetries() {
        server.route("/api", r -> Reply.text(503, "maintenance"));
        HttpStatusException e = assertThrows(HttpStatusException.class,
                () -> http.get(server.uri("/api"), Map.of(), TIMEOUT));
        assertEquals(503, e.status());
        assertTrue(e.getMessage().contains("maintenance"), e.getMessage());
        // first attempt + 3 retries
        assertEquals(4, server.requests("/api").size());
        assertEquals(3, TestHttp.SLEEPS.size());
    }

    @Test
    void noRetriesWithRetryPolicyNone() {
        server.route("/api", r -> Reply.status(502));
        HttpService noRetry = http.withRetry(RetryPolicy.NONE);
        assertThrows(HttpStatusException.class, () -> noRetry.get(server.uri("/api"), Map.of(), TIMEOUT));
        assertEquals(1, server.requests("/api").size());
        assertTrue(TestHttp.SLEEPS.isEmpty());
        assertEquals(TestHttp.USER_AGENT, noRetry.userAgent());
    }

    @Test
    void networkErrorsAreRetriedThenThrown() throws IOException {
        URI dead;
        try (FakeHttpServer gone = FakeHttpServer.start()) {
            dead = gone.uri("/api");
        }
        IOException e = assertThrows(IOException.class, () -> http.get(dead, Map.of(), TIMEOUT));
        assertFalse(e instanceof HttpStatusException);
        assertEquals(3, TestHttp.SLEEPS.size());
    }

    @Test
    void notFoundIsReturnedByGetAndNotRetried() throws IOException {
        server.route("/api", r -> Reply.text(404, "no such project"));
        HttpService.Response r = http.get(server.uri("/api"), Map.of(), TIMEOUT);
        assertEquals(404, r.status());
        assertEquals("no such project", r.body());
        assertEquals(1, server.requests("/api").size());
        assertTrue(TestHttp.SLEEPS.isEmpty());
    }

    @Test
    void getOkThrowsOnNotFound() {
        server.route("/api", r -> Reply.text(404, "  no\n such   project "));
        HttpStatusException e = assertThrows(HttpStatusException.class,
                () -> http.getOk(server.uri("/api?token=secret"), Map.of(), TIMEOUT));
        assertEquals(404, e.status());
        assertEquals("/api", e.uri().getPath());
        assertEquals("HTTP 404 from 127.0.0.1/api: no such project", e.getMessage());
        assertFalse(e.getMessage().contains("secret"));
        assertEquals(1, server.requests("/api").size());
    }

    @Test
    void longErrorBodiesAreShortened() {
        server.route("/api", r -> Reply.text(400, "x".repeat(1000)));
        HttpStatusException e = assertThrows(HttpStatusException.class,
                () -> http.getOk(server.uri("/api"), Map.of(), TIMEOUT));
        assertTrue(e.getMessage().endsWith("x".repeat(200) + "..."), e.getMessage());
        assertEquals("", HttpService.snippet(null));
    }

    @Test
    void retriesAreLoggedWithoutQueryString() throws IOException {
        List<String> warnings = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        HttpService logged = new HttpService(HttpService.newClient(TIMEOUT), TestHttp.USER_AGENT,
                new RetryPolicy(2, 0, 0), TestHttp.SLEEPS::add, HttpCache.none(), logger);
        server.sequence("/api", Reply.status(500), Reply.text(200, "ok"));
        logged.get(server.uri("/api?key=hunter2"), Map.of(), TIMEOUT);
        assertEquals(1, warnings.size());
        String w = warnings.get(0);
        assertTrue(w.contains("GET " + server.base() + "/api"), w);
        assertTrue(w.contains("(1/2)"), w);
        assertTrue(w.contains("HTTP 500"), w);
        assertFalse(w.contains("hunter2"), w);
    }

    // --- redirects -----------------------------------------------------------------------------

    @Test
    void followsRelativeRedirect() throws IOException {
        server.route("/start", r -> Reply.redirect("/final"));
        server.route("/final", r -> Reply.text(200, "arrived"));
        HttpService.Response r = http.get(server.uri("/start"), Map.of(), TIMEOUT);
        assertEquals(200, r.status());
        assertEquals("arrived", r.body());
        assertEquals(server.uri("/final"), r.uri());
    }

    @Test
    void followsRelativeRedirectWithoutLeadingSlash() throws IOException {
        server.route("/dir/start", r -> Reply.redirect("next"));
        server.route("/dir/next", r -> Reply.text(200, "arrived"));
        assertEquals("arrived", http.getOk(server.uri("/dir/start"), Map.of(), TIMEOUT));
    }

    @Test
    void followsAbsoluteRedirectChainOfEveryRedirectStatus() throws IOException {
        server.route("/a", r -> new Reply(301, Map.of("Location", server.base() + "/b"), new byte[0]));
        server.route("/b", r -> new Reply(303, Map.of("Location", server.base() + "/c"), new byte[0]));
        server.route("/c", r -> new Reply(307, Map.of("Location", server.base() + "/d"), new byte[0]));
        server.route("/d", r -> new Reply(308, Map.of("Location", server.base() + "/e"), new byte[0]));
        server.route("/e", r -> Reply.text(200, "end"));
        HttpService.Response r = http.get(server.uri("/a"), Map.of(), TIMEOUT);
        assertEquals("end", r.body());
        assertEquals(server.uri("/e"), r.uri());
        assertEquals(5, server.requests().size());
    }

    @Test
    void crossOriginRedirectDropsCredentialsButKeepsUserAgentAndAccept() throws IOException {
        try (FakeHttpServer cdn = FakeHttpServer.start()) {
            cdn.route("/file", r -> Reply.text(200, "from cdn"));
            server.route("/start", r -> Reply.redirect(cdn.base() + "/file"));
            Map<String, String> headers = Map.of(
                    "Authorization", "Bearer secret-token",
                    "X-Api-Key", "custom-secret",
                    "Accept", "application/octet-stream");
            HttpService.Response r = http.get(server.uri("/start"), headers, TIMEOUT);
            assertEquals("from cdn", r.body());

            FakeHttpServer.Request first = server.requests("/start").get(0);
            assertEquals("Bearer secret-token", first.header("Authorization"));
            assertEquals("custom-secret", first.header("X-Api-Key"));

            FakeHttpServer.Request second = cdn.requests("/file").get(0);
            assertNull(second.header("Authorization"));
            assertNull(second.header("X-Api-Key"));
            assertEquals(TestHttp.USER_AGENT, second.header("User-Agent"));
            assertEquals("application/octet-stream", second.header("Accept"));
        }
    }

    @Test
    void credentialsStayDroppedWhenRedirectReturnsToOriginalOrigin() throws IOException {
        try (FakeHttpServer other = FakeHttpServer.start()) {
            server.route("/start", r -> Reply.redirect(other.base() + "/bounce"));
            other.route("/bounce", r -> Reply.redirect(server.base() + "/end"));
            server.route("/end", r -> Reply.text(200, "end"));
            http.get(server.uri("/start"), Map.of("Authorization", "Bearer secret-token"), TIMEOUT);
            assertNull(other.requests("/bounce").get(0).header("Authorization"));
            assertNull(server.requests("/end").get(0).header("Authorization"));
        }
    }

    @Test
    void sameOriginRedirectKeepsHeaders() throws IOException {
        server.route("/start", r -> Reply.redirect("/final"));
        server.route("/final", r -> Reply.text(200, "ok"));
        http.get(server.uri("/start"), Map.of("Authorization", "Bearer secret-token", "X-Api-Key", "k"), TIMEOUT);
        FakeHttpServer.Request second = server.requests("/final").get(0);
        assertEquals("Bearer secret-token", second.header("Authorization"));
        assertEquals("k", second.header("X-Api-Key"));
        assertEquals(TestHttp.USER_AGENT, second.header("User-Agent"));
    }

    @Test
    void tooManyRedirectsFail() {
        server.route("/loop", r -> Reply.redirect("/loop"));
        HttpService noRetry = http.withRetry(RetryPolicy.NONE);
        IOException e = assertThrows(IOException.class, () -> noRetry.get(server.uri("/loop"), Map.of(), TIMEOUT));
        assertTrue(e.getMessage().startsWith("Too many redirects"), e.getMessage());
        // the original request plus 5 redirects
        assertEquals(6, server.requests("/loop").size());
    }

    @Test
    void fiveRedirectsAreAllowed() throws IOException {
        for (int i = 0; i < 5; i++) {
            String next = "/r" + (i + 1);
            server.route("/r" + i, r -> Reply.redirect(next));
        }
        server.route("/r5", r -> Reply.text(200, "ok"));
        assertEquals("ok", http.getOk(server.uri("/r0"), Map.of(), TIMEOUT));
    }

    @Test
    void redirectWithoutLocationFails() {
        server.route("/start", r -> Reply.status(302));
        IOException e = assertThrows(IOException.class,
                () -> http.withRetry(RetryPolicy.NONE).get(server.uri("/start"), Map.of(), TIMEOUT));
        assertTrue(e.getMessage().contains("without Location"), e.getMessage());
    }

    // --- ETag cache ----------------------------------------------------------------------------

    @Test
    void etagIsStoredAndNotModifiedIsServedFromCache() throws IOException {
        HttpService cached = TestHttp.service(HttpCache.inMemory(10));
        server.route("/releases", r -> "\"v1\"".equals(r.header("If-None-Match"))
                ? Reply.status(304)
                : Reply.json(200, "[\"release\"]").withHeader("ETag", "\"v1\""));

        HttpService.Response first = cached.get(server.uri("/releases"), Map.of(), TIMEOUT);
        assertEquals(200, first.status());
        assertFalse(first.fromCache());
        assertNull(server.requests("/releases").get(0).header("If-None-Match"));

        HttpService.Response second = cached.get(server.uri("/releases"), Map.of(), TIMEOUT);
        assertEquals("\"v1\"", server.requests("/releases").get(1).header("If-None-Match"));
        assertEquals(200, second.status());
        assertTrue(second.fromCache());
        assertEquals("[\"release\"]", second.body());
        assertEquals("[\"release\"]", cached.getOk(server.uri("/releases"), Map.of(), TIMEOUT));
    }

    @Test
    void lastModifiedIsSentAsIfModifiedSince() throws IOException {
        HttpCache cache = HttpCache.inMemory(10);
        HttpService cached = TestHttp.service(cache);
        String date = "Wed, 21 Oct 2026 07:28:00 GMT";
        server.sequence("/r", Reply.text(200, "body").withHeader("Last-Modified", date), Reply.status(304));
        cached.get(server.uri("/r"), Map.of(), TIMEOUT);
        assertEquals(new HttpCache.Entry(null, date, "body"), cache.get(server.uri("/r")).orElseThrow());
        HttpService.Response second = cached.get(server.uri("/r"), Map.of(), TIMEOUT);
        assertEquals(date, server.requests("/r").get(1).header("If-Modified-Since"));
        assertNull(server.requests("/r").get(1).header("If-None-Match"));
        assertTrue(second.fromCache());
        assertEquals("body", second.body());
    }

    @Test
    void changedResourceReplacesCacheEntry() throws IOException {
        HttpCache cache = HttpCache.inMemory(10);
        HttpService cached = TestHttp.service(cache);
        server.sequence("/r",
                Reply.text(200, "old").withHeader("ETag", "\"1\""),
                Reply.text(200, "new").withHeader("ETag", "\"2\""));
        cached.get(server.uri("/r"), Map.of(), TIMEOUT);
        HttpService.Response second = cached.get(server.uri("/r"), Map.of(), TIMEOUT);
        assertFalse(second.fromCache());
        assertEquals("new", second.body());
        assertEquals(new HttpCache.Entry("\"2\"", null, "new"), cache.get(server.uri("/r")).orElseThrow());
    }

    @Test
    void responsesWithoutValidatorsOrErrorsAreNotCached() throws IOException {
        HttpCache cache = HttpCache.inMemory(10);
        HttpService cached = TestHttp.service(cache);
        server.route("/plain", r -> Reply.text(200, "x"));
        server.route("/missing", r -> Reply.text(404, "gone").withHeader("ETag", "\"e\""));
        cached.get(server.uri("/plain"), Map.of(), TIMEOUT);
        cached.get(server.uri("/missing"), Map.of(), TIMEOUT);
        assertTrue(cache.get(server.uri("/plain")).isEmpty());
        assertTrue(cache.get(server.uri("/missing")).isEmpty());
    }

    @Test
    void notModifiedWithoutCacheEntryIsReturnedAsIs() throws IOException {
        server.route("/r", r -> Reply.status(304));
        HttpService.Response r = http.get(server.uri("/r"), Map.of(), TIMEOUT);
        assertEquals(304, r.status());
        assertFalse(r.fromCache());
    }

    @Test
    void inMemoryCacheEvictsLeastRecentlyUsed() {
        HttpCache cache = HttpCache.inMemory(2);
        URI a = URI.create("http://h/a");
        URI b = URI.create("http://h/b");
        URI c = URI.create("http://h/c");
        cache.put(a, new HttpCache.Entry("a", null, "A"));
        cache.put(b, new HttpCache.Entry("b", null, "B"));
        cache.get(a);
        cache.put(c, new HttpCache.Entry("c", null, "C"));
        assertTrue(cache.get(a).isPresent());
        assertTrue(cache.get(b).isEmpty());
        assertTrue(cache.get(c).isPresent());
        HttpCache none = HttpCache.none();
        none.put(a, new HttpCache.Entry("a", null, "A"));
        assertTrue(none.get(a).isEmpty());
    }

    // --- downloads -----------------------------------------------------------------------------

    @Test
    void downloadWritesFileOn200() throws IOException {
        byte[] jar = new byte[150_000];
        for (int i = 0; i < jar.length; i++) {
            jar[i] = (byte) i;
        }
        server.route("/file.jar", r -> Reply.bytes(200, jar));
        Path target = dir.resolve("file.jar");
        http.download(server.uri("/file.jar"), Map.of("Accept", "application/octet-stream"), target, TIMEOUT, 1_000_000);
        assertArrayEquals(jar, Files.readAllBytes(target));
        FakeHttpServer.Request req = server.requests("/file.jar").get(0);
        assertEquals(TestHttp.USER_AGENT, req.header("User-Agent"));
        assertEquals("application/octet-stream", req.header("Accept"));
    }

    @Test
    void downloadAcceptsFileExactlyAtLimit() throws IOException {
        server.route("/file.jar", r -> Reply.bytes(200, new byte[1000]));
        Path target = dir.resolve("file.jar");
        http.download(server.uri("/file.jar"), Map.of(), target, TIMEOUT, 1000);
        assertEquals(1000, Files.size(target));
    }

    @Test
    void downloadRetriesOn503() throws IOException {
        server.sequence("/file.jar", Reply.status(503), Reply.bytes(200, new byte[] {1, 2, 3}));
        Path target = dir.resolve("file.jar");
        http.download(server.uri("/file.jar"), Map.of(), target, TIMEOUT, 1000);
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(target));
        assertEquals(1, TestHttp.SLEEPS.size());
    }

    @Test
    void downloadFollowsCrossOriginRedirectWithoutCredentials() throws IOException {
        try (FakeHttpServer cdn = FakeHttpServer.start()) {
            cdn.route("/blob", r -> Reply.bytes(200, new byte[] {9}));
            server.route("/asset", r -> Reply.redirect(cdn.base() + "/blob?signature=abc"));
            Path target = dir.resolve("file.jar");
            http.download(server.uri("/asset"), Map.of("Authorization", "token ghp_x"), target, TIMEOUT, 1000);
            assertArrayEquals(new byte[] {9}, Files.readAllBytes(target));
            FakeHttpServer.Request req = cdn.requests("/blob").get(0);
            assertNull(req.header("Authorization"));
            assertEquals("signature=abc", req.query());
            assertEquals(TestHttp.USER_AGENT, req.header("User-Agent"));
        }
    }

    @Test
    void downloadErrorDeletesExistingTargetAndIsNotRetried() throws IOException {
        server.route("/file.jar", r -> Reply.text(404, "not found"));
        Path target = Files.writeString(dir.resolve("file.jar"), "stale partial download");
        HttpStatusException e = assertThrows(HttpStatusException.class,
                () -> http.download(server.uri("/file.jar"), Map.of(), target, TIMEOUT, 1000));
        assertEquals(404, e.status());
        assertFalse(Files.exists(target));
        assertEquals(1, server.requests("/file.jar").size());
    }

    @Test
    void downloadGivingUpAfterRetriesDeletesExistingTarget() throws IOException {
        server.route("/file.jar", r -> Reply.status(503));
        Path target = Files.writeString(dir.resolve("file.jar"), "stale partial download");
        HttpStatusException e = assertThrows(HttpStatusException.class,
                () -> http.download(server.uri("/file.jar"), Map.of(), target, TIMEOUT, 1000));
        assertEquals(503, e.status());
        assertEquals(4, server.requests("/file.jar").size());
        assertFalse(Files.exists(target));
    }

    @Test
    void downloadNetworkErrorDeletesExistingTarget() throws IOException {
        URI dead;
        try (FakeHttpServer gone = FakeHttpServer.start()) {
            dead = gone.uri("/file.jar");
        }
        Path target = Files.writeString(dir.resolve("file.jar"), "stale partial download");
        assertThrows(IOException.class,
                () -> http.withRetry(RetryPolicy.NONE).download(dead, Map.of(), target, TIMEOUT, 1000));
        assertFalse(Files.exists(target));
    }

    @Test
    void downloadRejectsDeclaredContentLengthAboveLimit() throws IOException {
        server.route("/big.jar", r -> Reply.bytes(200, new byte[2000]));
        Path target = dir.resolve("big.jar");
        IOException e = assertThrows(IOException.class,
                () -> http.withRetry(RetryPolicy.NONE).download(server.uri("/big.jar"), Map.of(), target, TIMEOUT, 1000));
        assertEquals("File is 2000 bytes, limit is 1000", e.getMessage());
        assertFalse(Files.exists(target));
    }

    @Test
    void downloadRejectsStreamedBodyAboveLimit() throws IOException {
        // Chunked response: no Content-Length, so only the streamed size can be checked.
        HttpServer chunked = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        chunked.createContext("/big.jar", ex -> {
            try (ex) {
                ex.sendResponseHeaders(200, 0);
                try (OutputStream out = ex.getResponseBody()) {
                    for (int i = 0; i < 10; i++) {
                        out.write(new byte[50_000]);
                    }
                } catch (IOException ignored) {
                    // the client stops reading once the limit is hit
                }
            }
        });
        chunked.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "chunked-http");
            t.setDaemon(true);
            return t;
        }));
        chunked.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + chunked.getAddress().getPort() + "/big.jar");
            Path target = dir.resolve("big.jar");
            IOException e = assertThrows(IOException.class,
                    () -> http.withRetry(RetryPolicy.NONE).download(uri, Map.of(), target, TIMEOUT, 120_000));
            assertTrue(e.getMessage().contains("larger than the limit of 120000"), e.getMessage());
            assertFalse(Files.exists(target));
        } finally {
            chunked.stop(0);
        }
    }

    // --- POST ----------------------------------------------------------------------------------

    @Test
    void postJsonSendsBodyAndHeaders() throws IOException {
        server.route("/hook", r -> Reply.status(204));
        HttpService.Response r = http.postJson(server.uri("/hook"), Map.of("X-Token", "t"), "{\"a\":\"ü\"}", TIMEOUT);
        assertEquals(204, r.status());
        FakeHttpServer.Request req = server.requests("/hook").get(0);
        assertEquals("POST", req.method());
        assertEquals("{\"a\":\"ü\"}", req.body());
        assertEquals("application/json", req.header("Content-Type"));
        assertEquals("t", req.header("X-Token"));
        assertEquals(TestHttp.USER_AGENT, req.header("User-Agent"));
    }

    @Test
    void postJsonDoesNotFollowRedirectsAndRetries5xx() throws IOException {
        server.route("/moved", r -> Reply.redirect("/elsewhere"));
        assertEquals(302, http.postJson(server.uri("/moved"), Map.of(), "{}", TIMEOUT).status());
        assertTrue(server.requests("/elsewhere").isEmpty());

        server.sequence("/flaky", Reply.status(500), Reply.status(200));
        assertEquals(200, http.postJson(server.uri("/flaky"), Map.of(), "{}", TIMEOUT).status());
        assertEquals(2, server.requests("/flaky").size());
    }

    // --- User-Agent and interruption -----------------------------------------------------------

    @Test
    void userAgentIsAlwaysSentAndCannotBeBlank() throws IOException {
        server.route("/a", r -> Reply.text(200, "a"));
        http.get(server.uri("/a"), Map.of(), TIMEOUT);
        assertEquals(TestHttp.USER_AGENT, server.requests("/a").get(0).header("User-Agent"));
        assertThrows(IllegalArgumentException.class, () -> new HttpService(HttpService.newClient(TIMEOUT), " ",
                RetryPolicy.NONE, ms -> { }, HttpCache.none(), null));
        assertThrows(IllegalArgumentException.class, () -> new HttpService(HttpService.newClient(TIMEOUT), null,
                RetryPolicy.NONE, ms -> { }, HttpCache.none(), null));
    }

    @Test
    void interruptingCallerAbortsSlowRequest() {
        server.route("/slow", r -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Reply.text(200, "too late");
        });
        Thread caller = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                return;
            }
            caller.interrupt();
        });
        long start = System.nanoTime();
        interrupter.start();
        assertThrows(InterruptedIOException.class, () -> http.get(server.uri("/slow"), Map.of(), Duration.ofSeconds(30)));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(Thread.interrupted(), "interrupt flag is restored");
        assertTrue(elapsedMs < 2000, "aborted after " + elapsedMs + " ms");
        assertTrue(TestHttp.SLEEPS.isEmpty(), "an interrupt is never retried");
    }

    @Test
    void alreadyInterruptedThreadSendsNothing() {
        server.route("/a", r -> Reply.text(200, "a"));
        Thread.currentThread().interrupt();
        assertThrows(InterruptedIOException.class, () -> http.get(server.uri("/a"), Map.of(), TIMEOUT));
        assertTrue(Thread.interrupted());
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void interruptDuringRetryWaitStopsRetrying() {
        server.route("/api", r -> Reply.status(503));
        HttpService interrupting = new HttpService(HttpService.newClient(TIMEOUT), TestHttp.USER_AGENT,
                new RetryPolicy(3, 0, 0), ms -> {
                    throw new InterruptedException();
                }, HttpCache.none(), null);
        InterruptedIOException e = assertThrows(InterruptedIOException.class,
                () -> interrupting.get(server.uri("/api"), Map.of(), TIMEOUT));
        assertTrue(e.getMessage().contains("waiting to retry"), e.getMessage());
        assertTrue(Thread.interrupted());
        assertEquals(1, server.requests("/api").size());
    }

    // --- helpers -------------------------------------------------------------------------------

    @Test
    void sameOrigin() {
        assertTrue(HttpService.sameOrigin(URI.create("https://api.github.com/a"), URI.create("https://API.GitHub.com/b?x")));
        assertTrue(HttpService.sameOrigin(URI.create("https://h/a"), URI.create("https://h:443/b")));
        assertTrue(HttpService.sameOrigin(URI.create("http://h:80/a"), URI.create("http://h/b")));
        assertFalse(HttpService.sameOrigin(URI.create("https://h/a"), URI.create("http://h/a")));
        assertFalse(HttpService.sameOrigin(URI.create("http://h:8080/a"), URI.create("http://h:8081/a")));
        assertFalse(HttpService.sameOrigin(URI.create("https://api.github.com/a"),
                URI.create("https://objects.githubusercontent.com/a")));
        assertFalse(HttpService.sameOrigin(URI.create("https://github.com/a"), URI.create("https://github.com.evil.io/a")));
        assertFalse(HttpService.sameOrigin(URI.create("file:/tmp/a"), URI.create("file:/tmp/a")));
    }

    @Test
    void describeHidesQueryUserInfoAndFragment() {
        assertEquals("https://api.modrinth.com/v2/project/x",
                HttpService.describe(URI.create("https://user:pass@api.modrinth.com/v2/project/x?token=secret#frag")));
        assertEquals("http://127.0.0.1:8080/health", HttpService.describe(URI.create("http://127.0.0.1:8080/health")));
    }

    @Test
    void responseHeaderLookupIsCaseInsensitive() {
        HttpService.Response r = new HttpService.Response(200, "", URI.create("http://h/"),
                Map.of("etag", List.of("\"x\""), "Empty", List.of()), false);
        assertEquals("\"x\"", r.header("ETag").orElseThrow());
        assertTrue(r.header("Empty").isEmpty());
        assertTrue(r.header("Missing").isEmpty());
    }

    @Test
    void withRetryKeepsCache() throws IOException {
        HttpCache cache = HttpCache.inMemory(5);
        HttpService base = TestHttp.service(cache);
        server.sequence("/r", Reply.text(200, "v").withHeader("ETag", "\"1\""), Reply.status(304));
        base.get(server.uri("/r"), Map.of(), TIMEOUT);
        HttpService.Response r = base.withRetry(RetryPolicy.NONE).get(server.uri("/r"), Map.of(), TIMEOUT);
        assertTrue(r.fromCache());
        assertNotNull(cache.get(server.uri("/r")).orElse(null));
        assertSame(TestHttp.USER_AGENT, base.withRetry(RetryPolicy.NONE).userAgent());
    }

    @Test
    void bodyIsDecodedAsUtf8() throws IOException {
        server.route("/u", r -> Reply.bytes(200, "Hanafubuki 花吹雪".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Hanafubuki 花吹雪", http.getOk(server.uri("/u"), Map.of(), TIMEOUT));
    }
}
