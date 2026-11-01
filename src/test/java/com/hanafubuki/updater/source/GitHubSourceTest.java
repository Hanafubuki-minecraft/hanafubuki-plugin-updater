package com.hanafubuki.updater.source;

import static com.hanafubuki.updater.testing.TestJson.arr;
import static com.hanafubuki.updater.testing.TestJson.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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

class GitHubSourceTest {

    private static final String REPO = "Owner/Repo";
    private static final String API = "/repos/" + REPO;

    @TempDir
    Path dir;

    private FakeHttpServer server;
    private Path installedJar;
    private final AtomicInteger assetIds = new AtomicInteger(1000);

    @BeforeEach
    void setUp() throws IOException {
        server = FakeHttpServer.start();
        installedJar = TestJars.write(dir, "Plugin.jar", TestJars.plugin("Plugin", "2.0.0"));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private GitHubSource source() {
        return source(null, GitHubSource.SignatureMode.OFF);
    }

    private GitHubSource source(String token, GitHubSource.SignatureMode mode) {
        return new GitHubSource(TestHttp.service(), new GitHubSource.Settings(TestHttp.SETTINGS, token, mode, false), server.base());
    }

    private UpdateRequest request(Map<String, Object> extra) {
        Map<String, Object> options = new java.util.LinkedHashMap<>(Map.of("repo", REPO));
        options.putAll(extra);
        return new UpdateRequest(new PluginEntry("Plugin", "github", options), new InstalledPlugin("Plugin", "2.0.0", installedJar), "1.21.9");
    }

    private UpdateCandidate find(Map<String, Object> extra) throws IOException {
        return source().findLatest(request(extra)).orElseThrow();
    }

    private JsonObject asset(String name, String digest) {
        return asset(name, 2048, digest);
    }

    private JsonObject asset(String name, long size, String digest) {
        JsonObject a = obj("name", name, "size", size,
                "url", server.base() + API + "/releases/assets/" + assetIds.incrementAndGet(),
                "browser_download_url", server.base() + "/dl/" + name);
        if (digest != null) {
            a.addProperty("digest", digest);
        }
        return a;
    }

    private static JsonObject release(String tag, String body, boolean prerelease, JsonObject... assets) {
        return obj("tag_name", tag, "html_url", "https://github.com/" + REPO + "/releases/tag/" + tag, "body", body,
                "draft", false, "prerelease", prerelease, "assets", arr((Object[]) assets));
    }

    private void latest(JsonObject release) {
        server.json(API + "/releases/latest", release.toString());
    }

    private void file(String name, String content) {
        server.route("/dl/" + name, r -> FakeHttpServer.Reply.text(200, content));
    }

    private static String sha256(String seed) {
        return TestJson.digest("SHA-256", seed);
    }

    private static String sha512(String seed) {
        return TestJson.digest("SHA-512", seed);
    }

    // --- release and asset choice ------------------------------------------------------------

    @Test
    void latestReleaseWithVPrefixedTagAndSingleJar() throws IOException {
        String digest = sha256("jar");
        latest(release("v2.1.0", "Bug fixes", false, asset("Plugin-2.1.0.jar", "sha256:" + digest)));

        UpdateCandidate c = find(Map.of());

        assertEquals("v2.1.0", c.version());
        assertEquals(URI.create(server.base() + "/dl/Plugin-2.1.0.jar"), c.downloadUrl());
        assertEquals("https://github.com/Owner/Repo/releases/tag/v2.1.0", c.pageUrl());
        assertEquals(UpdateCandidate.Freshness.UNKNOWN, c.freshness());
        assertEquals(new Checksum(Checksum.Algorithm.SHA256, digest), c.checksum());
        assertTrue(c.notes().isEmpty(), c.notes().toString());

        FakeHttpServer.Request r = server.requests(API + "/releases/latest").get(0);
        assertEquals("application/vnd.github+json", r.header("Accept"));
        assertEquals("2022-11-28", r.header("X-GitHub-Api-Version"));
        assertEquals(TestHttp.USER_AGENT, r.header("User-Agent"));
    }

    @Test
    void sourcesAndJavadocJarsAreIgnored() throws IOException {
        latest(release("v2.1.0", "", false,
                asset("Plugin-2.1.0-sources.jar", null),
                asset("Plugin-2.1.0.jar", null),
                asset("Plugin-2.1.0-javadoc.jar", null),
                asset("Plugin-2.1.0.zip", null)));

        assertEquals(URI.create(server.base() + "/dl/Plugin-2.1.0.jar"), find(Map.of()).downloadUrl());
    }

    @Test
    void severalJarsWithoutPatternIsAnErrorNamingThem() {
        latest(release("v2.1.0", "", false, asset("Plugin-Paper.jar", null), asset("Plugin-Velocity.jar", null)));

        IOException e = assertThrows(IOException.class, () -> find(Map.of()));
        assertTrue(e.getMessage().contains("Plugin-Paper.jar") && e.getMessage().contains("Plugin-Velocity.jar"), e.getMessage());
        assertTrue(e.getMessage().contains("asset_pattern"), e.getMessage());
    }

    @Test
    void assetPatternGlobChoosesTheAsset() throws IOException {
        latest(release("v2.1.0", "", false,
                asset("Plugin-Velocity-2.1.0.jar", null), asset("Plugin-Paper-2.1.0.jar", null), asset("Plugin-Fabric-2.1.0.jar", null)));

        UpdateCandidate c = find(Map.of("asset_pattern", "plugin-paper-*.jar"));

        assertEquals(URI.create(server.base() + "/dl/Plugin-Paper-2.1.0.jar"), c.downloadUrl());
    }

    @Test
    void assetPatternWithoutMatchIsAnError() {
        latest(release("v2.1.0", "", false, asset("Plugin-Velocity-2.1.0.jar", null)));

        IOException e = assertThrows(IOException.class, () -> find(Map.of("asset_pattern", "*-Paper-*.jar")));
        assertTrue(e.getMessage().contains("*-Paper-*.jar"), e.getMessage());
    }

    @Test
    void releaseWithoutJarIsAnError() {
        latest(release("v2.1.0", "", false, asset("Plugin-2.1.0.zip", null), asset("Plugin-2.1.0-sources.jar", null)));

        IOException e = assertThrows(IOException.class, () -> find(Map.of()));
        assertTrue(e.getMessage().contains(".jar"), e.getMessage());
    }

    @Test
    void missingLatestReleaseGivesEmpty() throws IOException {
        // No route: the fake server answers 404 like GitHub for repos without releases.
        assertEquals(Optional.empty(), source().findLatest(request(Map.of())));
    }

    @Test
    void rateLimitGivesAHelpfulMessage() {
        server.route(API + "/releases/latest", r -> FakeHttpServer.Reply.json(403, "{\"message\":\"API rate limit exceeded\"}")
                .withHeader("X-RateLimit-Remaining", "0"));

        IOException e = assertThrows(IOException.class, () -> find(Map.of()));
        assertTrue(e.getMessage().contains("rate limit") && e.getMessage().contains("github.token"), e.getMessage());
    }

    @Test
    void otherForbiddenAnswerIsReportedWithStatus() {
        server.route(API + "/releases/latest", r -> FakeHttpServer.Reply.json(403, "{\"message\":\"Forbidden\"}")
                .withHeader("X-RateLimit-Remaining", "42"));

        IOException e = assertThrows(IOException.class, () -> find(Map.of()));
        assertTrue(e.getMessage().contains("403") && !e.getMessage().contains("rate limit"), e.getMessage());
    }

    @Test
    void prereleasesUseTheReleaseListAndSkipDrafts() throws IOException {
        JsonObject draft = release("v3.0.0", "", false, asset("Plugin-3.0.0.jar", null));
        draft.addProperty("draft", true);
        server.json(API + "/releases", arr(draft,
                release("v2.2.0-beta.1", "", true, asset("Plugin-2.2.0-beta.1.jar", null)),
                release("v2.1.0", "", false, asset("Plugin-2.1.0.jar", null))).toString());

        UpdateCandidate c = find(Map.of("include_prereleases", true));

        assertEquals("v2.2.0-beta.1", c.version());
        assertEquals("per_page=20", server.requests(API + "/releases").get(0).query());
        assertTrue(server.requests(API + "/releases/latest").isEmpty());
    }

    @Test
    void prereleasesFromGlobalSettings() throws IOException {
        server.json(API + "/releases", arr(release("v2.2.0-rc1", "", true, asset("Plugin.jar", null))).toString());
        GitHubSource s = new GitHubSource(TestHttp.service(),
                new GitHubSource.Settings(TestHttp.SETTINGS, null, GitHubSource.SignatureMode.OFF, true), server.base());

        assertEquals("v2.2.0-rc1", s.findLatest(request(Map.of())).orElseThrow().version());
        // The entry can switch it off again.
        latest(release("v2.1.0", "", false, asset("Plugin.jar", null)));
        assertEquals("v2.1.0", s.findLatest(request(Map.of("include_prereleases", false))).orElseThrow().version());
    }

    @Test
    void onlyDraftsGiveEmpty() throws IOException {
        JsonObject draft = release("v3.0.0", "", false, asset("Plugin-3.0.0.jar", null));
        draft.addProperty("draft", true);
        server.json(API + "/releases", arr(draft).toString());

        assertEquals(Optional.empty(), source().findLatest(request(Map.of("include_prereleases", true))));
    }

    // --- checksums ---------------------------------------------------------------------------

    @Test
    void digestWinsOverSidecarAndList() throws IOException {
        String digest = sha256("digest");
        latest(release("v2.1.0", "SHA-256: " + sha256("notes"), false,
                asset("Plugin.jar", "sha256:" + digest), asset("Plugin.jar.sha256", 100, null), asset("checksums.txt", 100, null)));
        file("Plugin.jar.sha256", sha256("sidecar") + "  Plugin.jar\n");
        file("checksums.txt", sha256("list") + "  Plugin.jar\n");

        UpdateCandidate c = find(Map.of());

        assertEquals(digest, c.checksum().hex());
        assertTrue(server.requests("/dl/Plugin.jar.sha256").isEmpty());
        assertTrue(server.requests("/dl/checksums.txt").isEmpty());
    }

    @Test
    void sidecarAssetIsUsedWithoutDigest() throws IOException {
        String sidecar = sha512("sidecar");
        latest(release("v2.1.0", "SHA-256: " + sha256("notes"), false,
                asset("Plugin.jar", null), asset("Plugin.jar.sha512", 200, null), asset("checksums.txt", 100, null)));
        file("Plugin.jar.sha512", sidecar + "  Plugin.jar\n");
        file("checksums.txt", sha256("list") + "  Plugin.jar\n");

        UpdateCandidate c = find(Map.of());

        assertEquals(new Checksum(Checksum.Algorithm.SHA512, sidecar), c.checksum());
        assertTrue(server.requests("/dl/checksums.txt").isEmpty());
    }

    @Test
    void checksumListIsUsedWithoutDigestAndSidecar() throws IOException {
        String expected = sha256("plugin");
        latest(release("v2.1.0", "SHA-256: " + sha256("notes"), false,
                asset("Plugin.jar", null), asset("SHA256SUMS", 300, null)));
        file("SHA256SUMS", sha256("other") + "  Other.jar\n" + expected + " *Plugin.jar\n");

        assertEquals(expected, find(Map.of()).checksum().hex());
    }

    @Test
    void checksumListWithoutTheAssetFallsBackToNotes() throws IOException {
        String notes = sha256("notes");
        latest(release("v2.1.0", "Checksum: " + notes, false, asset("Plugin.jar", null), asset("checksums.txt", 300, null)));
        file("checksums.txt", sha256("other") + "  Other.jar\n");

        assertEquals(notes, find(Map.of()).checksum().hex());
    }

    @Test
    void releaseNotesLineWithAssetNameWins() throws IOException {
        String mine = sha256("mine");
        String body = String.join("\n",
                "## Downloads",
                "| File | SHA-256 |",
                "|---|---|",
                "| Plugin-Velocity.jar | " + sha256("velocity") + " |",
                "| Plugin-Paper.jar | `" + mine + "` |");
        latest(release("v2.1.0", body, false, asset("Plugin-Paper.jar", null), asset("Plugin-Velocity.jar", null)));

        UpdateCandidate c = find(Map.of("asset_pattern", "Plugin-Paper.jar"));

        assertEquals(mine, c.checksum().hex());
        assertTrue(c.notes().isEmpty(), c.notes().toString());
    }

    @Test
    void releaseNotesHashOnTheLineAfterTheAssetName() throws IOException {
        String mine = sha512("mine");
        latest(release("v2.1.0", "Plugin.jar:\n" + mine + "\n", false, asset("Plugin.jar", null)));

        assertEquals(new Checksum(Checksum.Algorithm.SHA512, mine), find(Map.of()).checksum());
    }

    @Test
    void singleLabelledHashInNotesIsUsed() throws IOException {
        String hash = sha256("only");
        latest(release("v2.1.0", "Changes:\n- faster\n\nSHA-256: " + hash + "\n", false, asset("Plugin.jar", null)));

        assertEquals(hash, find(Map.of()).checksum().hex());
    }

    @Test
    void ambiguousNotesGiveNoChecksumAndANote() throws IOException {
        String body = "SHA-256 (paper): " + sha256("a") + "\nSHA-256 (velocity): " + sha256("b") + "\n";
        latest(release("v2.1.0", body, false, asset("Plugin.jar", null)));

        UpdateCandidate c = find(Map.of());

        assertNull(c.checksum());
        assertEquals(1, c.notes().size());
        assertTrue(c.notes().get(0).contains("no checksum") && c.notes().get(0).contains("Plugin.jar"), c.notes().toString());
    }

    @Test
    void unlabelledHashInNotesIsNotUsed() throws IOException {
        latest(release("v2.1.0", "Commit " + sha256("commit") + " fixed things", false, asset("Plugin.jar", null)));

        assertNull(find(Map.of()).checksum());
    }

    @Test
    void largeChecksumAssetsAreNotDownloaded() throws IOException {
        latest(release("v2.1.0", "", false, asset("Plugin.jar", null), asset("Plugin.jar.sha256", 10 * 1024 * 1024, null)));
        file("Plugin.jar.sha256", sha256("x"));

        assertNull(find(Map.of()).checksum());
        assertTrue(server.requests("/dl/Plugin.jar.sha256").isEmpty());
    }

    @Test
    void checksumHelpersParseTheirFormats() {
        String a = sha256("a");
        assertEquals(a, GitHubSource.checksumFromList("# comment\n" + a + "  Plugin.jar\r\n", "plugin.JAR").hex());
        assertNull(GitHubSource.checksumFromList(a + "  Other.jar", "Plugin.jar"));
        assertNull(GitHubSource.checksumFromList("not-a-hash  Plugin.jar", "Plugin.jar"));
        assertNull(GitHubSource.checksumFromReleaseNotes(null, "Plugin.jar"));
        assertNull(GitHubSource.checksumFromReleaseNotes("  ", "Plugin.jar"));
    }

    // --- signatures --------------------------------------------------------------------------

    private void ref(String type, String sha) {
        server.json(API + "/git/ref/tags/v2.1.0", obj("ref", "refs/tags/v2.1.0", "object", obj("type", type, "sha", sha)).toString());
    }

    private void tagObject(String sha, boolean verified, String reason, String targetSha) {
        server.json(API + "/git/tags/" + sha, obj("sha", sha, "object", obj("type", "commit", "sha", targetSha),
                "verification", obj("verified", verified, "reason", reason)).toString());
    }

    private void commit(String sha, boolean verified, String reason) {
        server.json(API + "/commits/" + sha, obj("sha", sha, "commit", obj("verification", obj("verified", verified, "reason", reason))).toString());
    }

    private UpdateCandidate findSigned(GitHubSource.SignatureMode mode) throws IOException {
        latest(release("v2.1.0", "", false, asset("Plugin.jar", "sha256:" + sha256("x"))));
        return source(null, mode).findLatest(request(Map.of())).orElseThrow();
    }

    @Test
    void signaturesOffMakesNoTagRequests() throws IOException {
        UpdateCandidate c = findSigned(GitHubSource.SignatureMode.OFF);

        assertTrue(c.notes().isEmpty());
        assertTrue(server.requests(API + "/git/ref/tags/v2.1.0").isEmpty());
    }

    @Test
    void verifiedAnnotatedTagPasses() throws IOException {
        ref("tag", "tagsha");
        tagObject("tagsha", true, "valid", "commitsha");

        UpdateCandidate c = findSigned(GitHubSource.SignatureMode.REQUIRE);

        assertTrue(c.notes().isEmpty(), c.notes().toString());
        assertTrue(server.requests(API + "/commits/commitsha").isEmpty());
    }

    @Test
    void unverifiedTagWithVerifiedCommitPasses() throws IOException {
        ref("tag", "tagsha");
        tagObject("tagsha", false, "unsigned", "commitsha");
        commit("commitsha", true, "valid");

        UpdateCandidate c = findSigned(GitHubSource.SignatureMode.REQUIRE);

        assertTrue(c.notes().isEmpty(), c.notes().toString());
        assertEquals(1, server.requests(API + "/commits/commitsha").size());
    }

    @Test
    void lightweightTagChecksTheCommit() throws IOException {
        ref("commit", "commitsha");
        commit("commitsha", true, "valid");

        assertTrue(findSigned(GitHubSource.SignatureMode.REQUIRE).notes().isEmpty());
        assertTrue(server.requests().stream().noneMatch(r -> r.path().contains("/git/tags/")));
    }

    @Test
    void unsignedReleaseWithWarnAddsANote() throws IOException {
        ref("commit", "commitsha");
        commit("commitsha", false, "unsigned");

        UpdateCandidate c = findSigned(GitHubSource.SignatureMode.WARN);

        assertEquals(1, c.notes().size());
        assertTrue(c.notes().get(0).contains("not signed") && c.notes().get(0).contains("unsigned"), c.notes().toString());
    }

    @Test
    void unsignedReleaseWithRequireIsRefused() {
        ref("tag", "tagsha");
        tagObject("tagsha", false, "unsigned", "commitsha");
        commit("commitsha", false, "unknown_key");

        IOException e = assertThrows(IOException.class, () -> findSigned(GitHubSource.SignatureMode.REQUIRE));
        assertTrue(e.getMessage().contains("tag: unsigned") && e.getMessage().contains("commit: unknown_key"), e.getMessage());
    }

    @Test
    void entrySignatureModeOverridesGlobalSetting() throws IOException {
        ref("commit", "commitsha");
        commit("commitsha", false, "unsigned");
        latest(release("v2.1.0", "", false, asset("Plugin.jar", "sha256:" + sha256("x"))));
        GitHubSource globalOff = source(null, GitHubSource.SignatureMode.OFF);

        assertThrows(IOException.class, () -> globalOff.findLatest(request(Map.of("signatures", "require"))));
        GitHubSource globalRequire = source(null, GitHubSource.SignatureMode.REQUIRE);
        assertTrue(globalRequire.findLatest(request(Map.of("signatures", "off"))).isPresent());
    }

    @Test
    void signatureModeParsing() {
        assertEquals(GitHubSource.SignatureMode.OFF, GitHubSource.SignatureMode.parse(null));
        assertEquals(GitHubSource.SignatureMode.OFF, GitHubSource.SignatureMode.parse("false"));
        assertEquals(GitHubSource.SignatureMode.WARN, GitHubSource.SignatureMode.parse(" Warn "));
        assertEquals(GitHubSource.SignatureMode.REQUIRE, GitHubSource.SignatureMode.parse("required"));
        assertThrows(IllegalArgumentException.class, () -> GitHubSource.SignatureMode.parse("maybe"));
    }

    // --- token and download ------------------------------------------------------------------

    @Test
    void withTokenSidecarChecksumsAreReadThroughTheApi() throws IOException {
        // Private repositories: browser URLs answer 404 without a session; the API asset URL works with the token.
        String sidecar = sha512("private");
        JsonObject jar = asset("Plugin.jar", null);
        JsonObject sum = asset("Plugin.jar.sha512", 200, null);
        latest(release("v2.1.0", "", false, jar, sum));
        String apiPath = java.net.URI.create(sum.get("url").getAsString()).getPath();
        server.route(apiPath, r -> "Bearer ghp_secret".equals(r.header("Authorization"))
                && "application/octet-stream".equals(r.header("Accept"))
                ? FakeHttpServer.Reply.text(200, sidecar + "  Plugin.jar\n") : FakeHttpServer.Reply.status(404));

        UpdateCandidate c = source("ghp_secret", GitHubSource.SignatureMode.OFF).findLatest(request(Map.of())).orElseThrow();

        assertEquals(new Checksum(Checksum.Algorithm.SHA512, sidecar), c.checksum());
        assertTrue(server.requests("/dl/Plugin.jar.sha512").isEmpty());
    }

    @Test
    void withTokenTheApiAssetUrlIsDownloadedWithAuthorization() throws IOException {
        JsonObject jar = asset("Plugin.jar", "sha256:" + sha256("x"));
        latest(release("v2.1.0", "", false, jar));

        UpdateCandidate c = source("ghp_secret", GitHubSource.SignatureMode.OFF).findLatest(request(Map.of())).orElseThrow();

        assertEquals(URI.create(jar.get("url").getAsString()), c.downloadUrl());
        assertEquals("application/octet-stream", c.downloadHeaders().get("Accept"));
        assertEquals("Bearer ghp_secret", c.downloadHeaders().get("Authorization"));
        assertEquals("Bearer ghp_secret", server.requests(API + "/releases/latest").get(0).header("Authorization"));
    }

    @Test
    void withoutTokenTheBrowserUrlIsUsedWithoutAuthorization() throws IOException {
        latest(release("v2.1.0", "", false, asset("Plugin.jar", "sha256:" + sha256("x"))));

        UpdateCandidate c = find(Map.of());

        assertEquals(URI.create(server.base() + "/dl/Plugin.jar"), c.downloadUrl());
        assertTrue(c.downloadHeaders().isEmpty());
        assertNull(server.requests(API + "/releases/latest").get(0).header("Authorization"));
    }

    // --- validation --------------------------------------------------------------------------

    @Test
    void validateReportsConfigMistakes() {
        GitHubSource s = source();
        assertTrue(s.validate(new PluginEntry("P", "github", Map.of("repo", "Owner/Repo"))).isEmpty());
        assertTrue(s.validate(new PluginEntry("P", "github", Map.of("repo", "Owner/Repo.js", "asset_pattern", "Plugin-*",
                "signatures", "warn"))).isEmpty());
        assertTrue(s.validate(new PluginEntry("P", "github", Map.of("repo", "Owner/Repo", "asset_pattern", "*.JAR"))).isEmpty());

        assertTrue(s.validate(new PluginEntry("P", "github", Map.of())).get(0).contains("missing 'repo'"));
        assertTrue(s.validate(new PluginEntry("P", "github", Map.of("repo", "https://github.com/Owner/Repo"))).get(0)
                .contains("owner/repo"));
        assertTrue(s.validate(new PluginEntry("P", "github", Map.of("repo", "OnlyOwner"))).get(0).contains("owner/repo"));
        assertTrue(s.validate(new PluginEntry("P", "github", Map.of("repo", "Owner/Repo", "asset_pattern", "Plugin.zip"))).get(0)
                .contains("asset_pattern"));
        assertTrue(s.validate(new PluginEntry("P", "github", Map.of("repo", "Owner/Repo", "signatures", "maybe"))).get(0)
                .contains("signatures"));
        assertEquals(3, s.validate(new PluginEntry("P", "github", Map.of("repo", "bad", "asset_pattern", "x.zip",
                "signatures", "maybe"))).size());
    }

    @Test
    void invalidRepoIsRefusedBeforeAnyRequest() {
        IOException e = assertThrows(IOException.class, () -> source().findLatest(request(Map.of("repo", "../../etc"))));
        assertTrue(e.getMessage().contains("repo"), e.getMessage());
        assertTrue(server.requests().isEmpty());
        assertFalse(source().id().isEmpty());
    }
}
