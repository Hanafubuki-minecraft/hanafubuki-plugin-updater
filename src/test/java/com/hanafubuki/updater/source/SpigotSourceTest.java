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

class SpigotSourceTest {

    @TempDir
    Path dir;

    private FakeHttpServer server;
    private Path installedJar;

    @BeforeEach
    void setUp() throws IOException {
        server = FakeHttpServer.start();
        installedJar = TestJars.write(dir, "Vault.jar", TestJars.plugin("Vault", "1.7.3"));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private SpigotSource source() {
        return new SpigotSource(TestHttp.service(), TestHttp.SETTINGS, server.base() + "/v2");
    }

    private UpdateRequest request(Map<String, Object> options) {
        return new UpdateRequest(new PluginEntry("Vault", "spigotmc", options), new InstalledPlugin("Vault", "1.7.3", installedJar), "1.21.9");
    }

    private static JsonObject resource(int id, String name, boolean premium, JsonObject file) {
        return obj("id", id, "name", name, "premium", premium, "external", "external".equals(file.get("type").getAsString()),
                "file", file);
    }

    private static JsonObject jarFile(int id) {
        return obj("type", ".jar", "size", 120, "sizeUnit", "KB", "url", "resources/x." + id + "/download?version=1");
    }

    private static JsonObject externalFile(String externalUrl) {
        return obj("type", "external", "url", "resources/x/download", "externalUrl", externalUrl);
    }

    private void serve(int id, JsonObject resource, String latestVersion) {
        server.json("/v2/resources/" + id, resource.toString());
        server.json("/v2/resources/" + id + "/versions/latest", obj("id", 555, "name", latestVersion, "releaseDate", 1760000000).toString());
    }

    @Test
    void resourceIdGivesTheLatestVersionAndSpigetDownload() throws IOException {
        serve(34315, resource(34315, "Vault", false, jarFile(34315)), "1.7.3");

        UpdateCandidate c = source().findLatest(request(Map.of("resource_id", 34315))).orElseThrow();

        assertEquals("1.7.3", c.version());
        assertEquals(URI.create(server.base() + "/v2/resources/34315/download"), c.downloadUrl());
        assertNull(c.checksum());
        assertEquals(UpdateCandidate.Freshness.UNKNOWN, c.freshness());
        assertEquals("https://www.spigotmc.org/resources/34315/", c.pageUrl());
        assertTrue(c.notes().get(0).contains("no checksums"));
        assertTrue(server.requests().stream().allMatch(r -> TestHttp.USER_AGENT.equals(r.header("User-Agent"))));
    }

    @Test
    void resourceNamePicksTheExactMatchNotTheFirstResult() throws IOException {
        // Searching "Vault" really returns "Vaults" first.
        server.json("/v2/search/resources/Vault", arr(obj("id", 99001, "name", "Vaults"), obj("id", 34315, "name", "Vault")).toString());
        serve(34315, resource(34315, "Vault", false, jarFile(34315)), "1.7.3");

        UpdateCandidate c = source().findLatest(request(Map.of("resource_name", "Vault"))).orElseThrow();

        assertEquals(URI.create(server.base() + "/v2/resources/34315/download"), c.downloadUrl());
        assertEquals("field=name&size=100&page=1&fields=id,name", server.requests("/v2/search/resources/Vault").get(0).query());
        assertTrue(server.requests("/v2/resources/99001").isEmpty());
    }

    @Test
    void exactMatchesOnLaterSearchPagesAreFound() {
        // Spiget pages search results: a full first page means there may be more.
        com.google.gson.JsonArray firstPage = new com.google.gson.JsonArray();
        for (int i = 0; i < 100; i++) {
            firstPage.add(obj("id", 50_000 + i, "name", "Essentials Addon " + i));
        }
        firstPage.add(obj("id", 39793, "name", "Essentials"));
        com.google.gson.JsonArray first = new com.google.gson.JsonArray();
        for (int i = 0; i < 100; i++) {
            first.add(firstPage.get(i == 99 ? 100 : i));
        }
        server.route("/v2/search/resources/Essentials", req -> req.query().contains("page=1")
                ? com.hanafubuki.updater.testing.FakeHttpServer.Reply.json(200, first.toString())
                : com.hanafubuki.updater.testing.FakeHttpServer.Reply.json(200,
                        arr(obj("id", 49641, "name", "Essentials")).toString()));

        IOException e = assertThrows(IOException.class, () -> source().findLatest(request(Map.of("resource_name", "Essentials"))));
        assertTrue(e.getMessage().contains("39793") && e.getMessage().contains("49641"),
                "both exact matches, on page 1 and page 2, must be found: " + e.getMessage());
        assertEquals(2, server.requests("/v2/search/resources/Essentials").size());
    }

    @Test
    void exactNameMatchIgnoresCase() throws IOException {
        server.json("/v2/search/resources/vault", arr(obj("id", 99001, "name", "Vaults"), obj("id", 34315, "name", "VAULT")).toString());
        serve(34315, resource(34315, "VAULT", false, jarFile(34315)), "1.7.3");

        assertEquals("1.7.3", source().findLatest(request(Map.of("resource_name", "vault"))).orElseThrow().version());
    }

    @Test
    void resourceNameWithSpacesIsEncodedInThePath() throws IOException {
        server.json("/v2/search/resources/Essentials X", arr(obj("id", 9089, "name", "Essentials X")).toString());
        serve(9089, resource(9089, "Essentials X", false, jarFile(9089)), "2.21.0");

        assertEquals("2.21.0", source().findLatest(request(Map.of("resource_name", "Essentials X"))).orElseThrow().version());
    }

    @Test
    void onlyASimilarNameIsAnError() {
        server.json("/v2/search/resources/Vault", arr(obj("id", 99001, "name", "Vaults"), obj("id", 99002, "name", "VaultUnlocked")).toString());

        IOException e = assertThrows(IOException.class, () -> source().findLatest(request(Map.of("resource_name", "Vault"))));
        assertTrue(e.getMessage().contains("exactly 'Vault'") && e.getMessage().contains("resource_id"), e.getMessage());
        assertTrue(server.requests("/v2/resources/99001").isEmpty());
    }

    @Test
    void twoExactMatchesIsAnErrorListingTheIds() {
        server.json("/v2/search/resources/Vault", arr(obj("id", 34315, "name", "Vault"), obj("id", 41918, "name", "Vault")).toString());

        IOException e = assertThrows(IOException.class, () -> source().findLatest(request(Map.of("resource_name", "Vault"))));
        assertTrue(e.getMessage().contains("34315") && e.getMessage().contains("41918"), e.getMessage());
    }

    @Test
    void resourceIdWinsOverName() throws IOException {
        serve(34315, resource(34315, "Vault", false, jarFile(34315)), "1.7.3");

        source().findLatest(request(Map.of("resource_id", "34315", "resource_name", "Vault")));

        assertTrue(server.requests().stream().noneMatch(r -> r.path().startsWith("/v2/search")));
    }

    @Test
    void premiumResourceIsAnError() {
        serve(1000, resource(1000, "Paid", true, jarFile(1000)), "1.0");

        IOException e = assertThrows(IOException.class, () -> source().findLatest(request(Map.of("resource_id", 1000))));
        assertTrue(e.getMessage().contains("premium"), e.getMessage());
    }

    @Test
    void externalPageIsAnError() {
        serve(19254, resource(19254, "ViaVersion", false, externalFile("https://hangar.papermc.io/ViaVersion/ViaVersion")), "5.12.2");

        IOException e = assertThrows(IOException.class, () -> source().findLatest(request(Map.of("resource_id", 19254))));
        assertTrue(e.getMessage().contains("external page") && e.getMessage().contains("hangar.papermc.io"), e.getMessage());
    }

    @Test
    void externalPlainHttpJarIsAnError() {
        serve(19254, resource(19254, "ViaVersion", false, externalFile("http://example.org/ViaVersion.jar")), "5.12.2");

        assertThrows(IOException.class, () -> source().findLatest(request(Map.of("resource_id", 19254))));
    }

    @Test
    void externalHttpsJarIsUsed() throws IOException {
        String url = "https://github.com/ViaVersion/ViaVersion/releases/download/5.12.2/ViaVersion-5.12.2.jar";
        serve(19254, resource(19254, "ViaVersion", false, externalFile(url)), "5.12.2");

        UpdateCandidate c = source().findLatest(request(Map.of("resource_id", 19254))).orElseThrow();

        assertEquals(URI.create(url), c.downloadUrl());
        assertEquals("5.12.2", c.version());
        assertTrue(c.notes().stream().anyMatch(n -> n.contains("github.com")), c.notes().toString());
    }

    @Test
    void unsupportedFileTypeIsAnError() {
        serve(5, resource(5, "Zip", false, obj("type", ".zip", "url", "x")), "1.0");

        IOException e = assertThrows(IOException.class, () -> source().findLatest(request(Map.of("resource_id", 5))));
        assertTrue(e.getMessage().contains(".zip"), e.getMessage());
    }

    @Test
    void missingLatestVersionNameGivesEmpty() throws IOException {
        server.json("/v2/resources/7", resource(7, "X", false, jarFile(7)).toString());
        server.json("/v2/resources/7/versions/latest", obj("id", 1).toString());

        assertEquals(Optional.empty(), source().findLatest(request(Map.of("resource_id", 7))));
    }

    @Test
    void unknownResourceIsAnError() {
        IOException e = assertThrows(IOException.class, () -> source().findLatest(request(Map.of("resource_id", 424242))));
        assertTrue(e.getMessage().contains("not found"), e.getMessage());
    }

    @Test
    void invalidOrMissingIdIsAnError() {
        assertThrows(IOException.class, () -> source().findLatest(request(Map.of("resource_id", "abc"))));
        assertThrows(IOException.class, () -> source().findLatest(request(Map.of("resource_id", -4))));
        assertThrows(IOException.class, () -> source().findLatest(request(Map.of())));
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void validate() {
        SpigotSource s = source();
        assertTrue(s.validate(new PluginEntry("V", "spigotmc", Map.of("resource_id", 34315))).isEmpty());
        assertTrue(s.validate(new PluginEntry("V", "spigotmc", Map.of("resource_name", "Vault"))).isEmpty());
        assertEquals(1, s.validate(new PluginEntry("V", "spigotmc", Map.of())).size());
        assertTrue(s.validate(new PluginEntry("V", "spigotmc", Map.of("resource_id", "0"))).get(0).contains("positive"));
        assertTrue(s.validate(new PluginEntry("V", "spigotmc", Map.of("resource_id", "x12"))).get(0).contains("positive"));
        assertEquals("spigotmc", s.id());
    }
}
