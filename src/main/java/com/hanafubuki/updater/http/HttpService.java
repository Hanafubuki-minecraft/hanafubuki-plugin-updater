package com.hanafubuki.updater.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * All outgoing HTTP traffic of the updater goes through this class.
 *
 * <ul>
 *   <li>One shared {@link HttpClient}, which pools and reuses connections.</li>
 *   <li>Every request sends the updater's User-Agent (Modrinth requires a unique one).</li>
 *   <li>Retries with exponential backoff on network errors, HTTP 429 and 5xx; honours Retry-After.</li>
 *   <li>Redirects are followed by hand (max 5). When a redirect leaves the original host,
 *       every header except User-Agent and Accept is dropped, so tokens never leak to CDNs.
 *       Redirects from https to http are refused.</li>
 *   <li>GET responses with an ETag/Last-Modified are cached; the next request is conditional
 *       and a 304 answer is served from the cache.</li>
 *   <li>Response bodies and downloads have size limits.</li>
 *   <li>Calls are interruptible: interrupting the calling thread aborts the request with
 *       an {@link InterruptedIOException}.</li>
 * </ul>
 */
public final class HttpService {

    /** Largest API response body that is read into memory. */
    public static final int MAX_BODY_BYTES = 32 * 1024 * 1024;
    /** Hard cap on Retry-After waits. */
    private static final long MAX_RETRY_AFTER_MS = 60_000;
    private static final int MAX_REDIRECTS = 5;
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final Set<String> SAFE_CROSS_HOST_HEADERS = Set.of("user-agent", "accept");

    /** Sleeps between retries. Replaceable in tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * A finished response.
     *
     * @param status    HTTP status (a 304 served from cache is reported as 200)
     * @param body      response body as text
     * @param uri       final URI after redirects
     * @param headers   response headers
     * @param fromCache true if the body came from the ETag cache
     */
    public record Response(int status, String body, URI uri, Map<String, List<String>> headers, boolean fromCache) {
        public Optional<String> header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return Optional.of(e.getValue().get(0));
                }
            }
            return Optional.empty();
        }
    }

    private final HttpClient client;
    private final String userAgent;
    private final RetryPolicy retry;
    private final Sleeper sleeper;
    private final HttpCache cache;
    private final Logger logger;

    public HttpService(HttpClient client, String userAgent, RetryPolicy retry, Sleeper sleeper, HttpCache cache, Logger logger) {
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalArgumentException("A User-Agent is required");
        }
        this.client = client;
        this.userAgent = userAgent;
        this.retry = retry;
        this.sleeper = sleeper;
        this.cache = cache;
        this.logger = logger;
    }

    /** Creates the shared HTTP client. Redirects are handled by this class, not the client. */
    public static HttpClient newClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Same client, cache and User-Agent, different retry policy. */
    public HttpService withRetry(RetryPolicy policy) {
        return new HttpService(client, userAgent, policy, sleeper, cache, logger);
    }

    /**
     * Closes the shared client: aborts open requests and releases its selector thread and pooled
     * connections. Called when the plugin is disabled. Every copy made by {@link #withRetry}
     * shares the client, so all of them stop working.
     */
    public void shutdown() {
        client.shutdownNow();
    }

    public String userAgent() {
        return userAgent;
    }

    /**
     * Sends a GET request and returns the final response, whatever its status
     * (except retryable statuses, which are retried first).
     */
    public Response get(URI uri, Map<String, String> headers, Duration timeout) throws IOException {
        Optional<HttpCache.Entry> cached = cache.get(uri);
        Map<String, String> requestHeaders = new LinkedHashMap<>(headers);
        cached.ifPresent(entry -> {
            if (entry.etag() != null) {
                requestHeaders.put("If-None-Match", entry.etag());
            }
            if (entry.lastModified() != null) {
                requestHeaders.put("If-Modified-Since", entry.lastModified());
            }
        });

        Response response = withRetries("GET " + describe(uri), () -> {
            Exchange<InputStream> ex = sendFollowingRedirects(uri, requestHeaders, timeout, HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try (InputStream in = ex.response.body()) {
                body = readLimited(in, MAX_BODY_BYTES);
            }
            return new Response(ex.response.statusCode(), body, ex.finalUri, ex.response.headers().map(), false);
        });

        if (response.status() == 304 && cached.isPresent()) {
            return new Response(200, cached.get().body(), response.uri(), response.headers(), true);
        }
        if (response.status() == 200) {
            String etag = response.header("ETag").orElse(null);
            String lastModified = response.header("Last-Modified").orElse(null);
            if (etag != null || lastModified != null) {
                cache.put(uri, new HttpCache.Entry(etag, lastModified, response.body()));
            }
        }
        return response;
    }

    /** GET that requires HTTP 200 and returns the body. */
    public String getOk(URI uri, Map<String, String> headers, Duration timeout) throws IOException {
        Response r = get(uri, headers, timeout);
        if (r.status() != 200) {
            throw new HttpStatusException(r.status(), r.uri(), snippet(r.body()));
        }
        return r.body();
    }

    /**
     * Downloads a file. The target is written only on HTTP 200; on any failure it is deleted.
     *
     * @param maxBytes largest accepted file size
     */
    public void download(URI uri, Map<String, String> headers, Path target, Duration timeout, long maxBytes) throws IOException {
        try {
            downloadWithRetries(uri, headers, target, timeout, maxBytes);
        } catch (IOException e) {
            // Also covers failures outside a single attempt: connection errors and giving up after retries.
            Files.deleteIfExists(target);
            throw e;
        }
    }

    private void downloadWithRetries(URI uri, Map<String, String> headers, Path target, Duration timeout, long maxBytes)
            throws IOException {
        withRetries("download " + describe(uri), () -> {
            Exchange<InputStream> ex = sendFollowingRedirects(uri, headers, timeout, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = ex.response.body()) {
                int status = ex.response.statusCode();
                if (status != 200) {
                    String body = readLimited(in, 4096);
                    if (isRetryable(status)) {
                        return new Response(status, body, ex.finalUri, ex.response.headers().map(), false);
                    }
                    throw new HttpStatusException(status, ex.finalUri, snippet(body));
                }
                long declared = ex.response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > maxBytes) {
                    throw new IOException("File is " + declared + " bytes, limit is " + maxBytes);
                }
                try (OutputStream out = Files.newOutputStream(target)) {
                    copyLimited(in, out, maxBytes);
                }
                return new Response(200, "", ex.finalUri, ex.response.headers().map(), false);
            } catch (IOException e) {
                Files.deleteIfExists(target);
                throw e;
            }
        });
    }

    /**
     * Sends a POST with a JSON body. Redirects are not followed and nothing is cached.
     * Retries on network errors, 429 and 5xx like other requests.
     */
    public Response postJson(URI uri, Map<String, String> headers, String json, Duration timeout) throws IOException {
        return withRetries("POST " + describe(uri), () -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("User-Agent", userAgent)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            headers.forEach(b::header);
            HttpResponse<InputStream> r = send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try (InputStream in = r.body()) {
                body = readLimited(in, 64 * 1024);
            }
            return new Response(r.statusCode(), body, uri, r.headers().map(), false);
        });
    }

    // ------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Attempt {
        Response run() throws IOException;
    }

    private Response withRetries(String what, Attempt attempt) throws IOException {
        for (int i = 0; ; i++) {
            Response response;
            try {
                response = attempt.run();
            } catch (InterruptedIOException e) {
                throw e;
            } catch (HttpStatusException e) {
                if (!isRetryable(e.status()) || i >= retry.maxRetries()) {
                    throw e;
                }
                pause(what, i, e.getMessage(), -1);
                continue;
            } catch (IOException e) {
                if (i >= retry.maxRetries()) {
                    throw e;
                }
                pause(what, i, e.toString(), -1);
                continue;
            }
            if (isRetryable(response.status()) && i < retry.maxRetries()) {
                long retryAfter = response.header("Retry-After").map(HttpService::parseRetryAfterMs).orElse(-1L);
                pause(what, i, "HTTP " + response.status(), retryAfter);
                continue;
            }
            if (isRetryable(response.status())) {
                throw new HttpStatusException(response.status(), response.uri(), snippet(response.body()));
            }
            return response;
        }
    }

    private void pause(String what, int attempt, String reason, long retryAfterMs) throws InterruptedIOException {
        long delay = retryAfterMs >= 0 ? Math.min(retryAfterMs, MAX_RETRY_AFTER_MS) : retry.delayMs(attempt);
        if (logger != null) {
            logger.warning("Retrying " + what + " (" + (attempt + 1) + "/" + retry.maxRetries() + ") in " + delay + " ms: " + reason);
        }
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting to retry " + what);
        }
    }

    private record Exchange<T>(HttpResponse<T> response, URI finalUri) {
    }

    private <T> Exchange<T> sendFollowingRedirects(URI start, Map<String, String> headers, Duration timeout,
                                                   HttpResponse.BodyHandler<T> handler) throws IOException {
        URI current = start;
        Map<String, String> currentHeaders = new LinkedHashMap<>(headers);
        for (int hop = 0; ; hop++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(current).timeout(timeout).GET();
            b.header("User-Agent", userAgent);
            currentHeaders.forEach(b::header);
            HttpResponse<T> response = send(b.build(), handler);
            int status = response.statusCode();
            if (!REDIRECTS.contains(status)) {
                return new Exchange<>(response, current);
            }
            closeQuietly(response.body());
            if (hop >= MAX_REDIRECTS) {
                throw new IOException("Too many redirects starting at " + describe(start));
            }
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("Redirect without Location header from " + describe(start)));
            URI next = current.resolve(location.trim());
            if ("https".equalsIgnoreCase(current.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())) {
                throw new IOException("Refusing redirect from https to " + next.getScheme() + ": " + describe(next));
            }
            if (!sameOrigin(current, next)) {
                currentHeaders.keySet().removeIf(name -> !SAFE_CROSS_HOST_HEADERS.contains(name.toLowerCase(Locale.ROOT)));
            }
            current = next;
        }
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Cancelled before request to " + describe(request.uri()));
        }
        try {
            return client.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Request to " + describe(request.uri()) + " was cancelled");
        }
    }

    /** True if both URIs have the same scheme, host and port. */
    public static boolean sameOrigin(URI a, URI b) {
        return a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b)
                && a.getScheme().equalsIgnoreCase(b.getScheme());
    }

    private static int effectivePort(URI u) {
        if (u.getPort() != -1) {
            return u.getPort();
        }
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    private static boolean isRetryable(int status) {
        return status == 429 || (status >= 500 && status <= 599);
    }

    /** Retry-After as seconds ("120") or an HTTP date ("Wed, 21 Oct 2026 07:28:00 GMT"); -1 if unreadable. */
    static long parseRetryAfterMs(String value) {
        String v = value.trim();
        try {
            return Math.max(0, Long.parseLong(v)) * 1000;
        } catch (NumberFormatException e) {
            try {
                java.time.ZonedDateTime when = java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                return Math.max(0, java.time.Duration.between(java.time.Instant.now(), when.toInstant()).toMillis());
            } catch (java.time.format.DateTimeParseException ex) {
                return -1;
            }
        }
    }

    private static String readLimited(InputStream in, int limit) throws IOException {
        byte[] data = in.readNBytes(limit + 1);
        if (data.length > limit) {
            throw new IOException("Response is larger than " + limit + " bytes");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static void copyLimited(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("Download is larger than the limit of " + limit + " bytes");
            }
            out.write(buffer, 0, read);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Download cancelled");
            }
        }
    }

    private static void closeQuietly(Object body) {
        if (body instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception ignored) {
                // Nothing useful to do; the connection is discarded.
            }
        }
    }

    static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String s = body.strip().replaceAll("\\s+", " ");
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    /** Host and path only: query strings may contain secrets and are never logged. */
    public static String describe(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + uri.getPath();
    }
}
