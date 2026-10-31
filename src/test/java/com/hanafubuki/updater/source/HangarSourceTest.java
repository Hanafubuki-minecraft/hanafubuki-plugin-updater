package com.hanafubuki.updater.source;

import static com.hanafubuki.updater.testing.TestJson.arr;
import static com.hanafubuki.updater.testing.TestJson.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hanafubuki.updater.config.PluginEntry;
import com.hanafubuki.updater.testing.FakeHttpServer;
import com.hanafubuki.updater.testing.TestHttp;
import com.hanafubuki.updater.testing.TestJars;
import com.hanafubuki.updater.testing.TestJson;
import com.hanafubuki.updater.util.Checksum;

class HangarSourceTest {

    private static final String MC = "1.21.9";
    private static final String VERSIONS = "/api/v1/projects/ViaVersion/versions";

    @TempDir
    Path dir;

    private FakeHttpServer server;
    private Path installedJar;

    @BeforeEach
    void setUp() throws IOException {
        server = FakeHttpServer.start();
        installedJar = TestJars.write(dir, "ViaVersion-5.12.1.jar", TestJars.plugin("ViaVersion", "5.12.1"));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private HangarSource source() {
        return new HangarSource(TestHttp.service(), new HangarSource.Settings(TestHttp.SETTINGS, "Release"), server.base() + "/api/v1");
    }

    private UpdateRequest request(Map<String, Object> options) {
        return new UpdateRequest(new PluginEntry("ViaVersion", "hangar", options),
                new InstalledPlugin("ViaVersion", "5.12.1", installedJar), MC);
    }

    private UpdateRequest request() {
        return request(Map.of("project_slug", "ViaVersion"));
    }

    private static JsonObject version(String name, String channel, String createdAt, String sha256, List<String> paperVersions) {
        JsonObject paper = obj("fileInfo", obj("name", "ViaVersion-" + name + ".jar", "sizeBytes", 5_000_000, "sha256Hash", sha256),
                "externalUrl", null, "downloadUrl", "https://hangarcdn.papermc.io/plugins/ViaVersion/ViaVersion/versions/" + name
                        + "/PAPER/ViaVersion-" + name + ".jar");
        return obj("name", name, "createdAt", createdAt, "channel", obj("name", channel, "flags", arr()),
                "downloads", obj("PAPER", paper), "platformDependencies", obj("PAPER", arr(paperVersions.toArray())));
    }

    private static JsonObject external(String name, String externalUrl) {
        JsonObject v = version(name, "Release", "2026-09-01T00:00:00Z", null, List.of(MC));
        JsonObject paper = v.getAsJsonObject("downloads").getAsJsonObject("PAPER");
        paper.add("downloadUrl", com.google.gson.JsonNull.INSTANCE);
        paper.addProperty("externalUrl", externalUrl);
        paper.add("fileInfo", com.google.gson.JsonNull.INSTANCE);
        return v;
    }

    private void page(String path, JsonObject... versions) {
        JsonArray result = arr((Object[]) versions);
        server.json(path, obj("pagination", obj("count", versions.length, "limit", 25, "offset", 0), "result", result).toString());
    }

    private static String sha256(String seed) {
        return TestJson.digest("SHA-256", seed);
    }

    @Test
    void releaseChannelIsUsedByDefaultAndSnapshotsIgnored() throws IOException {
        // Even if the server ignored the channel filter, a newer snapshot must not be picked.
        page(VERSIONS,
                version("5.13.0-SNAPSHOT+800", "Snapshot", "2026-10-01T00:00:00Z", sha256("snap"), List.of(MC)),
                version("5.12.2", "Release", "2026-09-01T00:00:00Z", sha256("rel"), List.of("1.21.8", MC)));

        UpdateCandidate c = source().findLatest(request()).orElseThrow();

        assertEquals("5.12.2", c.version());
        assertEquals(URI.create("https://hangarcdn.papermc.io/plugins/ViaVersion/ViaVersion/versions/5.12.2/PAPER/ViaVersion-5.12.2.jar"),
                c.downloadUrl());
        assertEquals(new Checksum(Checksum.Algorithm.SHA256, sha256("rel")), c.checksum());
        assertEquals(UpdateCandidate.Freshness.UNKNOWN, c.freshness());
        assertTrue(c.notes().isEmpty());
        FakeHttpServer.Request r = server.requests(VERSIONS).get(0);
        assertEquals("limit=25&offset=0&platform=PAPER&channel=Release", r.query());
        assertEquals(TestHttp.USER_AGENT, r.header("User-Agent"));
    }

    @Test
    void anyChannelWithStar() throws IOException {
        page(VERSIONS,
                version("5.12.2", "Release", "2026-09-01T00:00:00Z", sha256("rel"), List.of(MC)),
                version("5.13.0-SNAPSHOT+800", "Snapshot", "2026-10-01T00:00:00Z", sha256("snap"), List.of(MC)));

        UpdateCandidate c = source().findLatest(request(Map.of("project_slug", "ViaVersion", "channel", "*"))).orElseThrow();

        assertEquals("5.13.0-SNAPSHOT+800", c.version());
        assertEquals("limit=25&offset=0&platform=PAPER", server.requests(VERSIONS).get(0).query());
    }

    @Test
    void configuredChannelIsUsed() throws IOException {
        page(VERSIONS,
                version("5.12.2", "Release", "2026-10-01T00:00:00Z", sha256("rel"), List.of(MC)),
                version("5.13.0-SNAPSHOT+800", "Snapshot", "2026-09-01T00:00:00Z", sha256("snap"), List.of(MC)));

        UpdateCandidate c = source().findLatest(request(Map.of("project_slug", "ViaVersion", "channel", "Snapshot"))).orElseThrow();

        assertEquals("5.13.0-SNAPSHOT+800", c.version());
        assertTrue(server.requests(VERSIONS).get(0).query().endsWith("&channel=Snapshot"));
    }

    @Test
    void ownerSlugIsReducedToTheSlug() throws IOException {
        page(VERSIONS, version("5.12.2", "Release", "2026-09-01T00:00:00Z", sha256("rel"), List.of(MC)));

        UpdateCandidate c = source().findLatest(request(Map.of("project_slug", "ViaVersion/ViaVersion"))).orElseThrow();

        assertEquals("5.12.2", c.version());
        assertEquals(1, server.requests(VERSIONS).size());
        assertEquals("https://hangar.papermc.io/ViaVersion/versions/5.12.2", c.pageUrl());
    }

    @Test
    void onlyVersionsSupportingTheServerVersionAreUsed() throws IOException {
        page(VERSIONS,
                version("6.0.0", "Release", "2026-10-03T00:00:00Z", sha256("6"), List.of("1.21.8", "1.21.10")),
                version("5.12.3", "Release", "2026-10-02T00:00:00Z", sha256("x"), List.of("1.21.x")),
                version("5.12.2", "Release", "2026-10-01T00:00:00Z", sha256("r"), List.of("1.21-1.21.10")));

        assertEquals("5.12.3", source().findLatest(request()).orElseThrow().version());

        page(VERSIONS,
                version("6.0.0", "Release", "2026-10-03T00:00:00Z", sha256("6"), List.of("1.20.x")),
                version("5.12.2", "Release", "2026-10-01T00:00:00Z", sha256("r"), List.of("1.21-1.21.10")));
        assertEquals("5.12.2", source().findLatest(request()).orElseThrow().version());

        page(VERSIONS, version("6.0.0", "Release", "2026-10-03T00:00:00Z", sha256("6"), List.of("1.21.8")));
        assertEquals(Optional.empty(), source().findLatest(request()));
    }

    @Test
    void newestByCreationDateRegardlessOfOrder() throws IOException {
        page(VERSIONS,
                version("5.12.0", "Release", "2026-08-01T00:00:00Z", sha256("0"), List.of(MC)),
                version("5.12.2", "Release", "2026-10-01T00:00:00Z", sha256("2"), List.of(MC)),
                version("5.12.1", "Release", "2026-09-01T00:00:00Z", sha256("1"), List.of(MC)));

        assertEquals("5.12.2", source().findLatest(request()).orElseThrow().version());
    }

    @ParameterizedTest(name = "{0} supports {1}: {2}")
    @CsvSource({
            "1.21.9, 1.21.9, true",
            "' 1.21.9 ', 1.21.9, true",
            "1.21.8, 1.21.9, false",
            "1.21-1.21.10, 1.21.9, true",
            "1.21-1.21.10, 1.21.10, true",
            "1.21-1.21.8, 1.21.9, false",
            "1.21.9-1.21.10, 1.21.9, true",
            "1.21.x, 1.21.9, true",
            "1.21.x, 1.21, true",
            "1.21.x, 1.210.1, false",
            "1.20.x, 1.21.9, false",
            "1.21, 1.21.9, false",
    })
    void versionMatches(String declared, String mc, boolean expected) {
        assertEquals(expected, HangarSource.versionMatches(declared, mc));
    }

    @Test
    void installedJarWithTheSameHashIsSame() throws IOException {
        String installedHash = Checksum.hash(installedJar, Checksum.Algorithm.SHA256);
        page(VERSIONS, version("5.12.1", "Release", "2026-09-01T00:00:00Z", installedHash, List.of(MC)));

        UpdateCandidate c = source().findLatest(request()).orElseThrow();

        assertEquals(UpdateCandidate.Freshness.SAME, c.freshness());
        assertEquals(installedHash, c.checksum().hex());
    }

    @Test
    void missingHashGivesANote() throws IOException {
        page(VERSIONS, version("5.12.2", "Release", "2026-09-01T00:00:00Z", null, List.of(MC)));

        UpdateCandidate c = source().findLatest(request()).orElseThrow();

        assertNull(c.checksum());
        assertEquals(1, c.notes().size());
        assertTrue(c.notes().get(0).contains("no checksum"));
    }

    @Test
    void externalJarUrlIsAccepted() throws IOException {
        page(VERSIONS, external("5.12.2", "https://github.com/ViaVersion/ViaVersion/releases/download/5.12.2/ViaVersion-5.12.2.jar"));

        UpdateCandidate c = source().findLatest(request()).orElseThrow();

        assertEquals(URI.create("https://github.com/ViaVersion/ViaVersion/releases/download/5.12.2/ViaVersion-5.12.2.jar"),
                c.downloadUrl());
    }

    @Test
    void externalPagesAndPlainHttpJarsAreRefused() throws IOException {
        page(VERSIONS,
                external("5.12.4", "https://www.spigotmc.org/resources/viaversion.19254/"),
                external("5.12.3", "http://example.org/ViaVersion-5.12.3.jar"),
                external("5.12.2", "not a url at all"));

        assertEquals(Optional.empty(), source().findLatest(request()));
    }

    @Test
    void unknownProjectIsAnError() {
        IOException e = assertThrows(IOException.class, () -> source().findLatest(request(Map.of("project_slug", "DoesNotExist"))));
        assertTrue(e.getMessage().contains("DoesNotExist") && e.getMessage().contains("does not exist"), e.getMessage());
    }

    @Test
    void otherErrorStatusIsReported() {
        server.route(VERSIONS, r -> FakeHttpServer.Reply.status(403));
        IOException e = assertThrows(IOException.class, () -> source().findLatest(request()));
        assertTrue(e.getMessage().contains("403"), e.getMessage());
    }

    @Test
    void missingSlugIsAnError() {
        assertThrows(IOException.class, () -> source().findLatest(request(Map.of())));
    }

    @Test
    void validate() {
        HangarSource s = source();
        assertTrue(s.validate(new PluginEntry("V", "hangar", Map.of("project_slug", "ViaVersion"))).isEmpty());
        assertTrue(s.validate(new PluginEntry("V", "hangar", Map.of("project_slug", "ViaVersion/ViaVersion"))).isEmpty());
        assertTrue(s.validate(new PluginEntry("V", "hangar", Map.of())).get(0).contains("project_slug"));
        assertEquals(1, s.validate(new PluginEntry("V", "hangar", Map.of("project_slug", "Via Version"))).size());
        assertEquals(1, s.validate(new PluginEntry("V", "hangar", Map.of("project_slug", "a/b/c"))).size());
        assertEquals("hangar", s.id());
    }
}
