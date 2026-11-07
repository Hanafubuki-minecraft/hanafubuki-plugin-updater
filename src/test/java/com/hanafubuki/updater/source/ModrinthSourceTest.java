package com.hanafubuki.updater.source;

import static com.hanafubuki.updater.testing.TestJson.arr;
import static com.hanafubuki.updater.testing.TestJson.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.hanafubuki.updater.config.PluginEntry;
import com.hanafubuki.updater.testing.FakeHttpServer;
import com.hanafubuki.updater.testing.TestHttp;
import com.hanafubuki.updater.testing.TestJars;
import com.hanafubuki.updater.testing.TestJson;
import com.hanafubuki.updater.util.Checksum;

class ModrinthSourceTest {

    private static final String MC = "1.21.9";

    @TempDir
    Path dir;

    private FakeHttpServer server;
    private Path installedJar;
    private String installedSha512;

    @BeforeEach
    void setUp() throws IOException {
        server = FakeHttpServer.start();
        installedJar = TestJars.write(dir.resolve("plugins"), "LuckPerms-Bukkit-5.5.70.jar", TestJars.plugin("LuckPerms", "5.5.70"));
        installedSha512 = Checksum.hash(installedJar, Checksum.Algorithm.SHA512);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private ModrinthSource source() {
        return source(List.of("paper"), List.of(), List.of("release"));
    }

    private ModrinthSource source(List<String> loaders, List<String> gameVersions, List<String> versionTypes) {
        return new ModrinthSource(TestHttp.service(),
                new ModrinthSource.Settings(TestHttp.SETTINGS, loaders, gameVersions, versionTypes), server.base() + "/v2");
    }

    private UpdateRequest request(Map<String, Object> options) {
        return new UpdateRequest(new PluginEntry("LuckPerms", "modrinth", options),
                new InstalledPlugin("LuckPerms", "5.5.70", installedJar), MC);
    }

    private static String sha512(String seed) {
        return TestJson.digest("SHA-512", seed);
    }

    private static JsonObject file(String name, boolean primary, String sha512) {
        JsonObject hashes = sha512 == null ? obj("sha1", TestJson.digest("SHA-1", name)) : obj("sha1", TestJson.digest("SHA-1", name), "sha512", sha512);
        return obj("url", "https://cdn.modrinth.com/data/Vebnzrzj/versions/x/" + name, "filename", name,
                "primary", primary, "size", 1234, "hashes", hashes);
    }

    private static JsonObject version(String id, String project, String number, String type, String date, JsonObject... files) {
        return obj("id", id, "project_id", project, "version_number", number, "version_type", type,
                "date_published", date, "status", "listed", "loaders", arr("bukkit", "paper"),
                "game_versions", arr("1.21.8", MC), "files", arr((Object[]) files));
    }

    /** The installed jar is a known Modrinth file of project Vebnzrzj, published at the given date. */
    private void installedIsKnown(String date) {
        server.json("/v2/version_file/" + installedSha512,
                version("OLDVER", "Vebnzrzj", "v5.5.70-bukkit", "release", date,
                        file("LuckPerms-Bukkit-5.5.70.jar", true, installedSha512)).toString());
    }

    private void versions(String project, JsonObject... versions) {
        server.json("/v2/project/" + project + "/version", arr((Object[]) versions).toString());
    }

    private static Map<String, String> query(FakeHttpServer.Request r) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : r.query().split("&")) {
            int eq = part.indexOf('=');
            out.put(part.substring(0, eq), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void identifiesInstalledJarByHashAndReportsNewerVersion() throws IOException {
        installedIsKnown("2026-07-01T10:00:00.000000Z");
        String newHash = sha512("5.5.71");
        versions("Vebnzrzj", version("NEWVER", "Vebnzrzj", "v5.5.71-bukkit", "release", "2026-08-06T19:40:10.637955Z",
                file("LuckPerms-Bukkit-5.5.71.jar", true, newHash)));

        UpdateCandidate c = source().findLatest(request(Map.of())).orElseThrow();

        assertEquals("v5.5.71-bukkit", c.version());
        assertEquals(UpdateCandidate.Freshness.NEWER, c.freshness());
        assertEquals(new Checksum(Checksum.Algorithm.SHA512, newHash), c.checksum());
        assertEquals(URI.create("https://cdn.modrinth.com/data/Vebnzrzj/versions/x/LuckPerms-Bukkit-5.5.71.jar"), c.downloadUrl());
        assertEquals("https://modrinth.com/project/Vebnzrzj/version/NEWVER", c.pageUrl());
        assertTrue(c.downloadHeaders().isEmpty());

        FakeHttpServer.Request lookup = server.requests("/v2/version_file/" + installedSha512).get(0);
        assertEquals("algorithm=sha512", lookup.query());
    }

    @Test
    void explicitProjectIdIsUsedWhenTheJarIsNotOnModrinth() throws IOException {
        // No route for version_file: the fake server answers 404 like Modrinth for unknown files.
        versions("luckperms", version("NEWVER", "Vebnzrzj", "5.5.71", "release", "2026-08-06T19:40:10Z",
                file("LuckPerms-5.5.71.jar", true, sha512("a"))));

        UpdateCandidate c = source().findLatest(request(Map.of("project_id", "luckperms"))).orElseThrow();

        assertEquals("5.5.71", c.version());
        assertEquals(UpdateCandidate.Freshness.UNKNOWN, c.freshness());
        assertEquals(1, server.requests("/v2/project/luckperms/version").size());
    }

    @Test
    void explicitProjectIdWinsOverTheIdentifiedProject() throws IOException {
        installedIsKnown("2026-07-01T10:00:00Z");
        versions("other-project", version("X", "OTHERID", "1.0", "release", "2026-08-06T19:40:10Z",
                file("Other.jar", true, sha512("o"))));

        UpdateCandidate c = source().findLatest(request(Map.of("project_id", "other-project"))).orElseThrow();

        assertEquals("1.0", c.version());
        // The installed file belongs to a different project: dates of two projects are not compared.
        assertEquals(UpdateCandidate.Freshness.UNKNOWN, c.freshness());
        assertTrue(server.requests("/v2/project/Vebnzrzj/version").isEmpty());
    }

    @Test
    void failsWhenTheJarIsNotOnModrinthAndNoProjectIdIsSet() {
        IOException e = assertThrows(IOException.class, () -> source().findLatest(request(Map.of())));
        assertTrue(e.getMessage().contains("project_id"), e.getMessage());
    }

    @Test
    void hashLookupErrorOtherThan404IsReported() {
        server.route("/v2/version_file/" + installedSha512, r -> FakeHttpServer.Reply.status(403));
        IOException e = assertThrows(IOException.class, () -> source().findLatest(request(Map.of("project_id", "luckperms"))));
        assertTrue(e.getMessage().contains("403"), e.getMessage());
    }

    @Test
    void betaVersionsAreSkippedByDefault() throws IOException {
        versions("luckperms",
                version("B", "P", "5.6.0-beta", "beta", "2026-09-01T00:00:00Z", file("b.jar", true, sha512("b"))),
                version("A", "P", "5.6.0-alpha", "alpha", "2026-09-02T00:00:00Z", file("a.jar", true, sha512("a"))),
                version("R", "P", "5.5.71", "release", "2026-08-01T00:00:00Z", file("r.jar", true, sha512("r"))));

        UpdateCandidate c = source().findLatest(request(Map.of("project_id", "luckperms"))).orElseThrow();

        assertEquals("5.5.71", c.version());
    }

    @Test
    void betaVersionsAreUsedWhenAllowedByEntryOrGlobalSettings() throws IOException {
        versions("luckperms",
                version("B", "P", "5.6.0-beta", "beta", "2026-09-01T00:00:00Z", file("b.jar", true, sha512("b"))),
                version("A", "P", "5.6.0-alpha", "alpha", "2026-09-02T00:00:00Z", file("a.jar", true, sha512("a"))),
                version("R", "P", "5.5.71", "release", "2026-08-01T00:00:00Z", file("r.jar", true, sha512("r"))));

        UpdateCandidate perEntry = source().findLatest(
                request(Map.of("project_id", "luckperms", "version_types", List.of("release", "beta")))).orElseThrow();
        assertEquals("5.6.0-beta", perEntry.version());

        UpdateCandidate global = source(List.of("paper"), List.of(), List.of("release", "beta", "alpha"))
                .findLatest(request(Map.of("project_id", "luckperms"))).orElseThrow();
        assertEquals("5.6.0-alpha", global.version());
    }

    @Test
    void newestByPublishDateRegardlessOfListOrder() throws IOException {
        versions("luckperms",
                version("V1", "P", "1.0", "release", "2026-01-01T00:00:00Z", file("1.jar", true, sha512("1"))),
                version("V3", "P", "0.9-hotfix", "release", "2026-03-01T00:00:00Z", file("3.jar", true, sha512("3"))),
                version("V2", "P", "2.0", "release", "2026-02-01T00:00:00Z", file("2.jar", true, sha512("2"))));

        UpdateCandidate c = source().findLatest(request(Map.of("project_id", "luckperms"))).orElseThrow();

        // Version strings are not compared: the most recently published release wins.
        assertEquals("0.9-hotfix", c.version());
    }

    @Test
    void primaryJarIsChosenOverOtherFiles() throws IOException {
        String primaryHash = sha512("primary");
        versions("luckperms", version("V", "P", "2.0", "release", "2026-02-01T00:00:00Z",
                file("Plugin-2.0-sources.jar", false, sha512("sources")),
                file("Plugin-2.0.zip", false, sha512("zip")),
                file("Plugin-2.0.jar", true, primaryHash)));

        UpdateCandidate c = source().findLatest(request(Map.of("project_id", "luckperms"))).orElseThrow();

        assertTrue(c.downloadUrl().toString().endsWith("/Plugin-2.0.jar"), c.downloadUrl().toString());
        assertEquals(primaryHash, c.checksum().hex());
    }

    @Test
    void singleNonPrimaryJarIsUsedButAmbiguousJarsSkipTheVersion() throws IOException {
        // Newest version has two jars and no primary one: skipped. Older one has a single jar.
        versions("luckperms",
                version("NEW", "P", "3.0", "release", "2026-03-01T00:00:00Z",
                        file("a.jar", false, sha512("a")), file("b.jar", false, sha512("b"))),
                version("OLD", "P", "2.0", "release", "2026-02-01T00:00:00Z",
                        file("only.jar", false, sha512("only")), file("readme.txt", true, sha512("txt"))));

        UpdateCandidate c = source().findLatest(request(Map.of("project_id", "luckperms"))).orElseThrow();

        assertEquals("2.0", c.version());
        assertTrue(c.downloadUrl().toString().endsWith("/only.jar"));
    }

    @Test
    void sameVersionPrefersTheBuildForTheFirstConfiguredLoader() throws IOException {
        // Real BlueMap data: the Spigot build is published 10 seconds after the Paper build of the same version.
        JsonObject spigot = version("S", "P", "5.16-spigot", "release", "2026-02-13T13:59:53Z", file("bluemap-5.16-spigot.jar", true, sha512("s")));
        spigot.add("loaders", arr("spigot"));
        JsonObject paper = version("PB", "P", "5.16-paper", "release", "2026-02-13T13:59:43Z", file("bluemap-5.16-paper.jar", true, sha512("p")));
        paper.add("loaders", arr("folia", "paper", "purpur"));
        JsonObject olderPaper = version("O", "P", "5.15-paper", "release", "2025-12-11T11:47:36Z", file("bluemap-5.15-paper.jar", true, sha512("o")));
        olderPaper.add("loaders", arr("paper"));
        versions("bluemap", spigot, paper, olderPaper);

        UpdateCandidate c = source(List.of("paper", "spigot", "bukkit"), List.of(), List.of("release"))
                .findLatest(request(Map.of("project_id", "bluemap"))).orElseThrow();
        assertEquals("5.16-paper", c.version());
        assertTrue(c.downloadUrl().toString().endsWith("bluemap-5.16-paper.jar"), c.downloadUrl().toString());

        // A newer version that exists only for another loader still wins over an older Paper build.
        JsonObject newerSpigotOnly = version("N", "P", "5.17-spigot", "release", "2026-03-01T00:00:00Z", file("bluemap-5.17-spigot.jar", true, sha512("n")));
        newerSpigotOnly.add("loaders", arr("spigot"));
        versions("bluemap", newerSpigotOnly, spigot, paper);
        assertEquals("5.17-spigot", source(List.of("paper", "spigot", "bukkit"), List.of(), List.of("release"))
                .findLatest(request(Map.of("project_id", "bluemap"))).orElseThrow().version());
    }

    @Test
    void noUsableVersionGivesEmpty() throws IOException {
        // Only beta versions (like Geyser on Modrinth): say so instead of a bare "no compatible version".
        versions("luckperms", version("B", "P", "2.0-beta", "beta", "2026-02-01T00:00:00Z", file("b.jar", true, sha512("b"))));
        NoVersionException e = assertThrows(NoVersionException.class,
                () -> source().findLatest(request(Map.of("project_id", "luckperms"))));
        assertTrue(e.getMessage().contains("only beta") && e.getMessage().contains("version_types: [release, beta]"),
                e.getMessage());

        versions("luckperms");
        assertEquals(Optional.empty(), source().findLatest(request(Map.of("project_id", "luckperms"))));
    }

    @Test
    void sameHashAsInstalledIsSame() throws IOException {
        installedIsKnown("2026-07-01T10:00:00Z");
        versions("Vebnzrzj", version("OLDVER", "Vebnzrzj", "v5.5.70-bukkit", "release", "2026-07-01T10:00:00Z",
                file("LuckPerms-Bukkit-5.5.70.jar", true, installedSha512)));

        UpdateCandidate c = source().findLatest(request(Map.of())).orElseThrow();

        assertEquals(UpdateCandidate.Freshness.SAME, c.freshness());
    }

    @Test
    void sameHashIsSameEvenWithoutHashIdentification() throws IOException {
        versions("luckperms", version("V", "P", "5.5.70", "release", "2026-07-01T10:00:00Z",
                file("LuckPerms.jar", true, installedSha512)));

        UpdateCandidate c = source().findLatest(request(Map.of("project_id", "luckperms"))).orElseThrow();

        assertEquals(UpdateCandidate.Freshness.SAME, c.freshness());
    }

    @Test
    void installedVersionPublishedLaterThanLatestIsSame() throws IOException {
        // A beta was installed by hand; the newest release is older than that beta.
        installedIsKnown("2026-09-01T00:00:00Z");
        versions("Vebnzrzj", version("REL", "Vebnzrzj", "v5.6.0-bukkit", "release", "2026-08-01T00:00:00Z",
                file("LuckPerms-Bukkit-5.6.0.jar", true, sha512("rel"))));

        UpdateCandidate c = source().findLatest(request(Map.of())).orElseThrow();

        assertEquals(UpdateCandidate.Freshness.SAME, c.freshness());
    }

    @Test
    void defaultQueryUsesPaperLoaderAndServerVersionAsEncodedJsonArrays() throws IOException {
        versions("luckperms");
        source().findLatest(request(Map.of("project_id", "luckperms")));

        FakeHttpServer.Request r = server.requests("/v2/project/luckperms/version").get(0);
        assertEquals("loaders=%5B%22paper%22%5D&game_versions=%5B%221.21.9%22%5D", r.query());
        assertEquals(Map.of("loaders", "[\"paper\"]", "game_versions", "[\"1.21.9\"]"), query(r));
    }

    @Test
    void entryAndGlobalFiltersAreSentInTheQuery() throws IOException {
        versions("luckperms");
        source().findLatest(request(Map.of("project_id", "luckperms",
                "loaders", List.of("paper", "purpur"), "game_versions", List.of("1.21.8", "1.21.9"))));
        FakeHttpServer.Request perEntry = server.requests("/v2/project/luckperms/version").get(0);
        assertEquals("[\"paper\",\"purpur\"]", query(perEntry).get("loaders"));
        assertEquals("[\"1.21.8\",\"1.21.9\"]", query(perEntry).get("game_versions"));
        assertTrue(!perEntry.query().contains("\"") && !perEntry.query().contains("["), perEntry.query());

        source(List.of("spigot"), List.of("1.21.4"), List.of("release")).findLatest(request(Map.of("project_id", "luckperms")));
        FakeHttpServer.Request global = server.requests("/v2/project/luckperms/version").get(1);
        assertEquals("[\"spigot\"]", query(global).get("loaders"));
        assertEquals("[\"1.21.4\"]", query(global).get("game_versions"));
    }

    @Test
    void missingSha512IsAnError() {
        versions("luckperms", version("V", "P", "2.0", "release", "2026-02-01T00:00:00Z", file("Plugin-2.0.jar", true, null)));

        IOException e = assertThrows(IOException.class, () -> source().findLatest(request(Map.of("project_id", "luckperms"))));
        assertTrue(e.getMessage().contains("SHA-512") && e.getMessage().contains("Plugin-2.0.jar"), e.getMessage());
    }

    @Test
    void userAgentIsSentWithEveryRequest() throws IOException {
        installedIsKnown("2026-07-01T10:00:00Z");
        versions("Vebnzrzj", version("NEWVER", "Vebnzrzj", "5.5.71", "release", "2026-08-06T19:40:10Z",
                file("LuckPerms.jar", true, sha512("n"))));

        source().findLatest(request(Map.of()));

        assertEquals(2, server.requests().size());
        for (FakeHttpServer.Request r : server.requests()) {
            assertEquals(TestHttp.USER_AGENT, r.header("User-Agent"), r.path());
        }
    }

    @Test
    void projectIdOfReturnsTheIdentifiedProject() throws IOException {
        installedIsKnown("2026-07-01T10:00:00Z");
        assertEquals(Optional.of("Vebnzrzj"), source().projectIdOf(installedJar));

        Path unknown = TestJars.write(dir, "Unknown.jar", TestJars.plugin("Unknown", "1"));
        assertEquals(Optional.empty(), source().projectIdOf(unknown));
    }

    @Test
    void validateChecksProjectIdAndVersionTypes() {
        ModrinthSource s = source();
        assertTrue(s.validate(new PluginEntry("A", "modrinth", Map.of())).isEmpty());
        assertTrue(s.validate(new PluginEntry("A", "modrinth", Map.of("project_id", "Vebnzrzj",
                "version_types", List.of("release", "BETA")))).isEmpty());

        List<String> badId = s.validate(new PluginEntry("A", "modrinth", Map.of("project_id", "luck perms/../x")));
        assertEquals(1, badId.size());
        assertTrue(badId.get(0).contains("project_id"));

        List<String> badType = s.validate(new PluginEntry("A", "modrinth", Map.of("version_types", List.of("stable"))));
        assertEquals(1, badType.size());
        assertTrue(badType.get(0).contains("stable"));
        assertNotNull(s.id());
        assertEquals("modrinth", s.id());
    }
}
