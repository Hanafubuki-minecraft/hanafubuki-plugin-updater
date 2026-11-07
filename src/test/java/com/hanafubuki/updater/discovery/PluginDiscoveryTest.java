package com.hanafubuki.updater.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.hanafubuki.updater.discovery.PluginDiscovery.Candidate;
import com.hanafubuki.updater.discovery.PluginDiscovery.Suggestion;

class PluginDiscoveryTest {

    private static final Path JAR = Path.of("plugins", "Some.jar");

    private static Suggestion web(String website) {
        return PluginDiscovery.fromWebsite("Some", "1.0", website);
    }

    // --- fromWebsite ---------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "https://modrinth.com/plugin/luckperms, luckperms",
            "https://www.modrinth.com/plugin/luckperms/versions, luckperms",
            "https://modrinth.com/mod/fabric-api, fabric-api",
            "https://modrinth.com/project/AANobbMI, AANobbMI",
            "modrinth.com/plugin/no-scheme, no-scheme",
            "HTTPS://MODRINTH.COM/plugin/upper, upper",
    })
    void modrinthPages(String website, String projectId) {
        Suggestion s = web(website);
        assertEquals("modrinth", s.source());
        assertEquals(Map.of("project_id", projectId), s.options());
        assertEquals("website is a Modrinth page", s.reason());
        assertTrue(s.isUsable());
        assertEquals("Some", s.name());
        assertEquals("1.0", s.version());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "https://modrinth.com/plugins",
            "https://modrinth.com/user/someone",
            "https://modrinth.com/plugin",
            "https://docs.modrinth.com/plugin/x",
    })
    void otherModrinthPagesAreNotProjects(String website) {
        assertFalse(web(website).isUsable());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "https://hangar.papermc.io/ViaVersion/ViaVersion, ViaVersion",
            "https://hangar.papermc.io/HelpChat/PlaceholderAPI/versions, PlaceholderAPI",
            "hangar.papermc.io/Owner/Slug, Slug",
    })
    void hangarPages(String website, String slug) {
        Suggestion s = web(website);
        assertEquals("hangar", s.source());
        assertEquals(Map.of("project_slug", slug), s.options());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "https://hangar.papermc.io/api/v1",
            "https://hangar.papermc.io/paper/plugins",
            "https://hangar.papermc.io/Paper/plugins",
            "https://hangar.papermc.io/auth/login",
            "https://hangar.papermc.io/admin/stats",
            "https://hangar.papermc.io/linkout/x",
            "https://hangar.papermc.io/OnlyOwner",
    })
    void reservedHangarPathsAreNotProjects(String website) {
        assertFalse(web(website).isUsable(), website);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "https://www.spigotmc.org/resources/luckperms.28140/, 28140",
            "https://spigotmc.org/resources/luckperms.28140, 28140",
            "https://www.spigotmc.org/resources/28140/, 28140",
            "https://www.spigotmc.org/resources/essentialsx.9089/updates, 9089",
            "spigotmc.org/resources/name.12345/, 12345",
    })
    void spigotResources(String website, int resourceId) {
        Suggestion s = web(website);
        assertEquals("spigotmc", s.source());
        assertEquals(Map.of("resource_id", String.valueOf(resourceId)), s.options());
        assertEquals("website is a SpigotMC resource page", s.reason());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "https://www.spigotmc.org/",
            "https://www.spigotmc.org/members/someone.123/",
            "https://www.spigotmc.org/resources/",
            "https://www.spigotmc.org/resources/no-id/",
    })
    void otherSpigotPagesAreNotResources(String website) {
        assertFalse(web(website).isUsable(), website);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "https://github.com/LuckPerms/LuckPerms, LuckPerms/LuckPerms",
            "https://github.com/EssentialsX/Essentials.git, EssentialsX/Essentials",
            "https://www.github.com/owner/repo/releases/latest, owner/repo",
            "github.com/owner/my.repo_name-2, owner/my.repo_name-2",
            "http://github.com/owner/repo/, owner/repo",
    })
    void githubRepositories(String website, String repo) {
        Suggestion s = web(website);
        assertEquals("github", s.source());
        assertEquals(Map.of("repo", repo), s.options());
        assertTrue(s.reason().contains("check that releases contain the plugin jar"), s.reason());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "https://github.com/sponsors/someone",
            "https://github.com/Sponsors/someone",
            "https://github.com/orgs/PaperMC/repositories",
            "https://github.com/topics/minecraft",
            "https://github.com/marketplace/actions",
            "https://github.com/someone",
            "https://github.com/",
            "https://github.com/-bad/repo",
    })
    void nonRepositoryGithubPagesAreNotUsed(String website) {
        Suggestion s = web(website);
        assertFalse(s.isUsable(), website);
        assertNull(s.source());
        assertEquals(Map.of(), s.options());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "https://essentialsx.net",
            "https://www.example.com/plugin/x",
            "https://gitlab.com/owner/repo",
            "https://dev.bukkit.org/projects/worldedit",
    })
    void unknownHostsAreNotUsed(String website) {
        Suggestion s = web(website);
        assertFalse(s.isUsable());
        assertTrue(s.reason().startsWith("website "), s.reason());
        assertTrue(s.reason().endsWith(" is not a known plugin source"), s.reason());
    }

    @Test
    void unknownHostReasonNamesHostWithoutWww() {
        assertEquals("website example.com is not a known plugin source", web("https://www.example.com/x").reason());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void blankWebsiteHasNoSource(String website) {
        Suggestion s = web(website);
        assertFalse(s.isUsable());
        assertEquals("not on Modrinth and no website in plugin.yml", s.reason());
    }

    @Test
    void invalidUrlHasNoSource() {
        Suggestion s = web("https://exa mple.com/<bad>");
        assertFalse(s.isUsable());
        assertEquals("website is not a valid URL", s.reason());
    }

    @Test
    void websiteIsTrimmed() {
        assertEquals("github", web("  https://github.com/owner/repo  ").source());
    }

    @Test
    void foundOptionsAreMutableForEditingBeforeSaving() {
        Suggestion s = web("https://github.com/owner/repo");
        s.options().put("asset_pattern", "*.jar");
        assertEquals("*.jar", s.options().get("asset_pattern"));
    }

    // --- suggest -------------------------------------------------------------------------------

    @Test
    void modrinthHashLookupWinsOverWebsite() throws InterruptedIOException {
        List<Path> asked = new ArrayList<>();
        PluginDiscovery discovery = new PluginDiscovery(jar -> {
            asked.add(jar);
            return Optional.of("Vebnzrzj");
        });
        Suggestion s = discovery.suggest(new Candidate("LuckPerms", "5.4", JAR, "https://github.com/LuckPerms/LuckPerms"));
        assertEquals("modrinth", s.source());
        assertEquals(Map.of("project_id", "Vebnzrzj"), s.options());
        assertEquals("the installed jar is published on Modrinth", s.reason());
        assertEquals("LuckPerms", s.name());
        assertEquals("5.4", s.version());
        assertEquals(List.of(JAR), asked);
    }

    @Test
    void emptyLookupFallsBackToWebsite() throws InterruptedIOException {
        PluginDiscovery discovery = new PluginDiscovery(jar -> Optional.empty());
        Suggestion s = discovery.suggest(new Candidate("Via", "5.0", JAR, "https://hangar.papermc.io/ViaVersion/ViaVersion"));
        assertEquals("hangar", s.source());
        assertEquals(Map.of("project_slug", "ViaVersion"), s.options());
    }

    @Test
    void emptyLookupAndNoWebsiteHasNoSource() throws InterruptedIOException {
        Suggestion s = new PluginDiscovery(jar -> Optional.empty()).suggest(new Candidate("X", "1", JAR, null));
        assertFalse(s.isUsable());
        assertEquals("not on Modrinth and no website in plugin.yml", s.reason());
    }

    @Test
    void lookupErrorFallsBackToUsableWebsite() throws InterruptedIOException {
        PluginDiscovery discovery = new PluginDiscovery(jar -> {
            throw new IOException("HTTP 503");
        });
        Suggestion s = discovery.suggest(new Candidate("X", "1", JAR, "https://modrinth.com/plugin/x"));
        assertEquals("modrinth", s.source());
        assertEquals(Map.of("project_id", "x"), s.options());
        assertTrue(s.lookupFailed(), "an exact hash match may still exist; look again later");
        assertTrue(s.reason().contains("Modrinth lookup failed: HTTP 503"), s.reason());
    }

    @Test
    void afterTheFirstModrinthErrorTheRestIsNotSentToModrinth() throws InterruptedIOException {
        List<String> calls = new java.util.ArrayList<>();
        PluginDiscovery discovery = new PluginDiscovery(jar -> {
            calls.add(jar.toString());
            throw new IOException("timeout");
        });
        List<Suggestion> out = discovery.discover(List.of(new Candidate("A", "1", Path.of("a.jar"), null),
                new Candidate("B", "1", Path.of("b.jar"), "https://github.com/o/b"),
                new Candidate("C", "1", Path.of("c.jar"), null)));
        assertEquals(List.of("a.jar"), calls);
        assertTrue(out.stream().allMatch(Suggestion::lookupFailed));
        assertEquals("github", out.get(1).source());
        assertFalse(out.get(2).isUsable());
    }

    @Test
    void successfulLookupIsNotMarkedFailed() throws InterruptedIOException {
        Suggestion s = new PluginDiscovery(jar -> Optional.empty())
                .suggest(new Candidate("X", "1", JAR, "https://github.com/o/x"));
        assertFalse(s.lookupFailed());
    }

    @Test
    void lookupErrorIsReportedWhenWebsiteGivesNothing() throws InterruptedIOException {
        PluginDiscovery discovery = new PluginDiscovery(jar -> {
            throw new IOException("Modrinth answered HTTP 503");
        });
        Suggestion s = discovery.suggest(new Candidate("X", "1", JAR, "https://example.com"));
        assertFalse(s.isUsable());
        assertEquals("Modrinth lookup failed: Modrinth answered HTTP 503", s.reason());
        assertEquals(Map.of(), s.options());
        assertTrue(s.lookupFailed());
    }

    @Test
    void interruptedLookupIsRethrown() {
        InterruptedIOException cancelled = new InterruptedIOException("cancelled");
        PluginDiscovery discovery = new PluginDiscovery(jar -> {
            throw cancelled;
        });
        InterruptedIOException e = assertThrows(InterruptedIOException.class,
                () -> discovery.suggest(new Candidate("X", "1", JAR, "https://modrinth.com/plugin/x")));
        assertSame(cancelled, e);
        assertThrows(InterruptedIOException.class,
                () -> discovery.discover(List.of(new Candidate("X", "1", JAR, null))));
    }

    @Test
    void noLookupOrNoJarUsesWebsiteOnly() throws InterruptedIOException {
        Suggestion withoutLookup = new PluginDiscovery(null)
                .suggest(new Candidate("X", "1", JAR, "https://github.com/owner/repo"));
        assertEquals("github", withoutLookup.source());

        PluginDiscovery failing = new PluginDiscovery(jar -> {
            throw new AssertionError("must not be called without a jar");
        });
        assertEquals("github", failing.suggest(new Candidate("X", "1", null, "https://github.com/owner/repo")).source());
    }

    @Test
    void discoverKeepsCandidateOrder() throws InterruptedIOException {
        PluginDiscovery discovery = new PluginDiscovery(jar -> jar.getFileName().toString().equals("A.jar")
                ? Optional.of("aaaa") : Optional.empty());
        List<Suggestion> out = discovery.discover(List.of(
                new Candidate("A", "1", Path.of("A.jar"), null),
                new Candidate("B", "1", Path.of("B.jar"), "https://github.com/b/b"),
                new Candidate("C", "1", Path.of("C.jar"), null)));
        assertEquals(List.of("A", "B", "C"), out.stream().map(Suggestion::name).toList());
        assertEquals("modrinth", out.get(0).source());
        assertEquals("github", out.get(1).source());
        assertNull(out.get(2).source());
        assertEquals(List.of(), discovery.discover(List.of()));
    }
}
