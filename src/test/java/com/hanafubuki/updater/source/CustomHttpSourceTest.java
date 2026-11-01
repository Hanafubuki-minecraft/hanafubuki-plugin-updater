package com.hanafubuki.updater.source;

import static com.hanafubuki.updater.testing.TestJson.arr;
import static com.hanafubuki.updater.testing.TestJson.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hanafubuki.updater.config.PluginEntry;
import com.hanafubuki.updater.testing.FakeHttpServer;
import com.hanafubuki.updater.testing.TestHttp;
import com.hanafubuki.updater.testing.TestJars;
import com.hanafubuki.updater.testing.TestJson;
import com.hanafubuki.updater.util.Checksum;

class CustomHttpSourceTest {

    @TempDir
    Path dir;

    private FakeHttpServer server;
    private Path installedJar;
    private final Map<String, String> env = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        server = FakeHttpServer.start();
        installedJar = TestJars.write(dir, "Internal.jar", TestJars.plugin("Internal", "1.0"));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private CustomHttpSource source() {
        return new CustomHttpSource(TestHttp.service(), TestHttp.SETTINGS, env::get);
    }

    /** Entry pointing at /api/latest on the fake server, with allow_http (the fake server speaks http). */
    private Map<String, Object> options(Object... extra) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("url", server.base() + "/api/latest");
        o.put("allow_http", true);
        for (int i = 0; i < extra.length; i += 2) {
            o.put((String) extra[i], extra[i + 1]);
        }
        return o;
    }

    private UpdateRequest request(Map<String, Object> options) {
        return new UpdateRequest(new PluginEntry("Internal", "custom", options), new InstalledPlugin("Internal", "1.0", installedJar), "1.21.9");
    }

    private UpdateCandidate find(Map<String, Object> options) throws IOException {
        return source().findLatest(request(options)).orElseThrow();
    }

    private void api(String json) {
        server.json("/api/latest", json);
    }

    private static String sha256(String seed) {
        return TestJson.digest("SHA-256", seed);
    }

    @Test
    void jsonPathsSelectVersionAndUrl() throws IOException {
        api(obj("data", obj("latest", obj("ver", "3.0.0", "files", arr(obj("link", "https://cdn.example.org/Internal-3.0.0.jar"))))).toString());

        UpdateCandidate c = find(options("version_path", "$.data.latest.ver", "download_url_path", "data.latest.files[0].link"));

        assertEquals("3.0.0", c.version());
        assertEquals(URI.create("https://cdn.example.org/Internal-3.0.0.jar"), c.downloadUrl());
        assertEquals(UpdateCandidate.Freshness.UNKNOWN, c.freshness());
        assertEquals(List.of("The custom source publishes no checksum"), c.notes());
        FakeHttpServer.Request r = server.requests("/api/latest").get(0);
        assertEquals("application/json", r.header("Accept"));
        assertEquals(TestHttp.USER_AGENT, r.header("User-Agent"));
    }

    @Test
    void commonFieldNamesAreUsedWithoutPaths() throws IOException {
        api(obj("latest_version", "1.2", "download_url", "https://cdn.example.org/a.jar").toString());
        UpdateCandidate a = find(options());
        assertEquals("1.2", a.version());
        assertEquals(URI.create("https://cdn.example.org/a.jar"), a.downloadUrl());

        api(obj("tag_name", "v2.0", "browser_download_url", "https://cdn.example.org/b.jar").toString());
        UpdateCandidate b = find(options());
        assertEquals("v2.0", b.version());
        assertEquals(URI.create("https://cdn.example.org/b.jar"), b.downloadUrl());

        // "version" wins over "name"; "download_url" over "url".
        api(obj("name", "Internal", "version", "3.0", "url", "https://cdn.example.org/page", "download_url", "https://cdn.example.org/c.jar")
                .toString());
        UpdateCandidate c = find(options());
        assertEquals("3.0", c.version());
        assertEquals(URI.create("https://cdn.example.org/c.jar"), c.downloadUrl());
    }

    @Test
    void relativeDownloadUrlIsResolvedAgainstTheApiUrl() throws IOException {
        api(obj("version", "2.0", "download_url", "/files/Internal-2.0.jar").toString());
        assertEquals(URI.create(server.base() + "/files/Internal-2.0.jar"), find(options()).downloadUrl());

        api(obj("version", "2.0", "download_url", "Internal-2.0.jar").toString());
        assertEquals(URI.create(server.base() + "/api/Internal-2.0.jar"), find(options()).downloadUrl());
    }

    @Test
    void missingVersionGivesEmptyAndMissingUrlIsAnError() throws IOException {
        api(obj("download_url", "https://cdn.example.org/a.jar").toString());
        assertEquals(Optional.empty(), source().findLatest(request(options())));

        api(obj("version", "2.0").toString());
        IOException e = assertThrows(IOException.class, () -> find(options()));
        assertTrue(e.getMessage().contains("no download URL"), e.getMessage());

        api(arr("not", "an", "object").toString());
        assertEquals(Optional.empty(), source().findLatest(request(options())));
    }

    @Test
    void httpIsRefusedUnlessAllowed() {
        api(obj("version", "2.0", "download_url", "/a.jar").toString());
        Map<String, Object> noHttp = options();
        noHttp.remove("allow_http");

        IOException e = assertThrows(IOException.class, () -> find(noHttp));
        assertTrue(e.getMessage().contains("https") && e.getMessage().contains("allow_http"), e.getMessage());
        assertTrue(server.requests().isEmpty(), "no request may be sent over plain http");
        assertEquals(1, source().validate(new PluginEntry("Internal", "custom", noHttp)).size());
    }

    @Test
    void nonHttpDownloadSchemeIsRefused() {
        api(obj("version", "2.0", "download_url", "file:///etc/passwd").toString());
        assertThrows(IOException.class, () -> find(options()));

        api(obj("version", "2.0", "download_url", "ftp://example.org/a.jar").toString());
        assertThrows(IOException.class, () -> find(options()));
    }

    @Test
    void bearerAuthIsSentToApiAndToSameOriginDownload() throws IOException {
        api(obj("version", "2.0", "download_url", "/files/a.jar").toString());

        UpdateCandidate c = find(options("auth", Map.of("type", "bearer", "token", "s3cret"),
                "headers", Map.of("X-Team", "ops")));

        FakeHttpServer.Request r = server.requests("/api/latest").get(0);
        assertEquals("Bearer s3cret", r.header("Authorization"));
        assertEquals("ops", r.header("X-Team"));
        assertEquals("Bearer s3cret", c.downloadHeaders().get("Authorization"));
        assertEquals("ops", c.downloadHeaders().get("X-Team"));
    }

    @Test
    void authIsNotSentToADownloadOnAnotherOrigin() throws IOException {
        try (FakeHttpServer cdn = FakeHttpServer.start()) {
            api(obj("version", "2.0", "download_url", cdn.base() + "/files/a.jar").toString());

            UpdateCandidate c = find(options("auth", Map.of("type", "bearer", "token", "s3cret")));

            assertEquals(URI.create(cdn.base() + "/files/a.jar"), c.downloadUrl());
            assertTrue(c.downloadHeaders().isEmpty(), c.downloadHeaders().toString());
            assertEquals("Bearer s3cret", server.requests("/api/latest").get(0).header("Authorization"));
        }
    }

    @Test
    void basicAuth() throws IOException {
        api(obj("version", "2.0", "download_url", "/a.jar").toString());

        find(options("auth", Map.of("type", "Basic", "username", "deploy", "password", "pässword")));

        String expected = "Basic " + Base64.getEncoder().encodeToString("deploy:pässword".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, server.requests("/api/latest").get(0).header("Authorization"));
    }

    @Test
    void apiKeyAuthWithDefaultAndCustomHeader() throws IOException {
        api(obj("version", "2.0", "download_url", "/a.jar").toString());

        find(options("auth", Map.of("type", "api_key", "key", "k1")));
        assertEquals("k1", server.requests("/api/latest").get(0).header("X-API-Key"));

        find(options("auth", Map.of("type", "api_key", "key", "k2", "header", "X-Token")));
        FakeHttpServer.Request second = server.requests("/api/latest").get(1);
        assertEquals("k2", second.header("X-Token"));
        assertNull(second.header("X-API-Key"));
    }

    @Test
    void missingAuthFieldsAreErrors() {
        api(obj("version", "2.0", "download_url", "/a.jar").toString());

        assertThrows(IOException.class, () -> find(options("auth", Map.of("type", "bearer"))));
        assertThrows(IOException.class, () -> find(options("auth", Map.of("type", "basic", "username", "u"))));
        assertThrows(IOException.class, () -> find(options("auth", Map.of("type", "oauth"))));
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void environmentReferencesAreResolved() throws IOException {
        env.put("UPDATER_TOKEN", "from-env");
        env.put("TEAM", "ops");
        api(obj("version", "2.0", "download_url", "/a.jar").toString());

        find(options("auth", Map.of("type", "bearer", "token", "${UPDATER_TOKEN}"), "headers", Map.of("X-Team", " ${TEAM} ")));

        FakeHttpServer.Request r = server.requests("/api/latest").get(0);
        assertEquals("Bearer from-env", r.header("Authorization"));
        assertEquals("ops", r.header("X-Team"));
    }

    @Test
    void environmentReferencesInsideValues() throws IOException {
        env.put("TOKEN", "abc$1\\x");
        api(obj("version", "2.0", "download_url", "/a.jar").toString());

        find(options("headers", Map.of("Authorization", "Bearer ${TOKEN}", "X-Path", "/teams/${TOKEN}/x")));

        FakeHttpServer.Request r = server.requests("/api/latest").get(0);
        assertEquals("Bearer abc$1\\x", r.header("Authorization"));
        assertEquals("/teams/abc$1\\x/x", r.header("X-Path"));
    }

    @Test
    void invalidChecksumOptionIsAnError() {
        api(obj("version", "2.0", "download_url", "/a.jar").toString());

        IOException e = assertThrows(IOException.class, () -> find(options("checksum", "not-a-hash")));
        assertTrue(e.getMessage().contains("checksum"), e.getMessage());
    }

    @Test
    void missingEnvironmentVariableIsAnError() {
        api(obj("version", "2.0", "download_url", "/a.jar").toString());

        IOException e = assertThrows(IOException.class,
                () -> find(options("auth", Map.of("type", "bearer", "token", "${UPDATER_TOKEN}"))));
        assertTrue(e.getMessage().contains("UPDATER_TOKEN"), e.getMessage());
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void checksumFromPath() throws IOException {
        String hash = sha256("a");
        api(obj("version", "2.0", "download_url", "/a.jar", "meta", obj("sum", "sha256:" + hash)).toString());

        assertEquals(new Checksum(Checksum.Algorithm.SHA256, hash), find(options("checksum_path", "$.meta.sum")).checksum());
    }

    @Test
    void checksumFromCommonField() throws IOException {
        String hash = TestJson.digest("SHA-512", "a");
        api(obj("version", "2.0", "download_url", "/a.jar", "sha512", hash).toString());

        UpdateCandidate c = find(options());

        assertEquals(new Checksum(Checksum.Algorithm.SHA512, hash), c.checksum());
        assertTrue(c.notes().isEmpty());
    }

    @Test
    void checksumOptionWinsOverJson() throws IOException {
        String configured = sha256("configured");
        api(obj("version", "2.0", "download_url", "/a.jar", "sha256", sha256("json")).toString());

        assertEquals(configured, find(options("checksum", configured)).checksum().hex());
    }

    @Test
    void checksumFromHashesObjectPrefersSha512() throws IOException {
        String sha512 = TestJson.digest("SHA-512", "x");
        api(obj("version", "2.0", "download_url", "/a.jar",
                "hashes", obj("sha1", TestJson.digest("SHA-1", "x"), "sha256", sha256("x"), "sha512", sha512)).toString());
        assertEquals(new Checksum(Checksum.Algorithm.SHA512, sha512), find(options()).checksum());

        String sha1 = TestJson.digest("SHA-1", "y");
        api(obj("version", "2.0", "download_url", "/a.jar", "hashes", obj("sha1", sha1)).toString());
        assertEquals(new Checksum(Checksum.Algorithm.SHA1, sha1), find(options()).checksum());
    }

    @Test
    void invalidChecksumIsAnError() {
        api(obj("version", "2.0", "download_url", "/a.jar", "sha256", "abc123").toString());

        IOException e = assertThrows(IOException.class, () -> find(options()));
        assertTrue(e.getMessage().contains("abc123"), e.getMessage());

        api(obj("version", "2.0", "download_url", "/a.jar", "meta", obj("sum", "md5:0123")).toString());
        assertThrows(IOException.class, () -> find(options("checksum_path", "meta.sum")));
    }

    @Test
    void validate() {
        CustomHttpSource s = source();
        assertTrue(s.validate(new PluginEntry("I", "custom", Map.of("url", "https://example.org/latest.json"))).isEmpty());
        assertTrue(s.validate(new PluginEntry("I", "custom", Map.of())).get(0).contains("url"));
        assertTrue(s.validate(new PluginEntry("I", "custom", Map.of("url", "http://example.org/latest.json"))).get(0)
                .contains("https"));
        assertTrue(s.validate(new PluginEntry("I", "custom", Map.of("url", "http://example.org/x", "allow_http", true))).isEmpty());
        assertTrue(s.validate(new PluginEntry("I", "custom", Map.of("url", "https://example.org/x", "version_path", "$.a[x]")))
                .get(0).startsWith("version_path"));
        assertTrue(s.validate(new PluginEntry("I", "custom", Map.of("url", "https://example.org/x",
                "auth", Map.of("type", "oauth")))).get(0).contains("auth.type"));
        assertTrue(s.validate(new PluginEntry("I", "custom", Map.of("url", "https://example.org/x", "checksum", "nope")))
                .get(0).contains("checksum"));
        assertEquals("custom", s.id());
    }
}
