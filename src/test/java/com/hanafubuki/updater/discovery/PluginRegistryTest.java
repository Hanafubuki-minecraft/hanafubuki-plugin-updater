package com.hanafubuki.updater.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanafubuki.updater.config.PluginEntry;
import com.hanafubuki.updater.discovery.PluginDiscovery.Candidate;
import com.hanafubuki.updater.discovery.PluginRegistry.Changes;
import com.hanafubuki.updater.discovery.PluginRegistry.Outcome;
import com.hanafubuki.updater.discovery.PluginRegistry.Status;

class PluginRegistryTest {

    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-02T00:00:00Z");

    @TempDir
    Path dir;

    /** Modrinth lookup double: jar file name to project id; counts lookups. */
    private final Map<String, String> onModrinth = new HashMap<>();
    private final List<String> lookedUp = new ArrayList<>();
    private IOException failWith;

    private PluginDiscovery discovery() {
        return new PluginDiscovery(jar -> {
            lookedUp.add(jar.getFileName().toString());
            if (failWith != null) {
                throw failWith;
            }
            return Optional.ofNullable(onModrinth.get(jar.getFileName().toString()));
        });
    }

    private PluginRegistry registry() {
        return new PluginRegistry(dir.resolve("plugins.json"), null);
    }

    private static Candidate plugin(String name, String version, String website) {
        return new Candidate(name, version, Path.of("plugins", name + ".jar"), website);
    }

    private Changes detect(PluginRegistry r, List<Candidate> installed, Set<String> skip, boolean force, Instant now)
            throws InterruptedIOException {
        return r.detect(installed, skip, force, discovery(), now);
    }

    // --- detection -----------------------------------------------------------------------------

    @Test
    void newPluginsArePendingOrUnidentifiedAndNothingIsConfirmed() throws Exception {
        onModrinth.put("GSit.jar", "gsit-id");
        PluginRegistry r = registry();
        List<Candidate> installed = List.of(
                plugin("GSit", "3.0", null),
                plugin("Tool", "1.0", "https://github.com/acme/tool"),
                plugin("Mystery", "0.1", "https://example.com"));

        Changes c = detect(r, installed, Set.of(), false, T1);

        assertEquals(List.of("GSit", "Tool"), c.pending());
        assertEquals(List.of("Mystery"), c.unidentified());
        assertEquals(List.of(), c.removed());
        assertEquals(Map.of(), r.confirmed());
        assertEquals(List.of("GSit", "Tool"), r.pendingNames());

        PluginRegistry.Entry gsit = r.get("gsit").orElseThrow();
        assertEquals(Status.PENDING, gsit.status());
        assertEquals("modrinth", gsit.source());
        assertEquals(Map.of("project_id", "gsit-id"), gsit.options());
        assertEquals("the installed jar is published on Modrinth", gsit.reason());
        assertEquals("3.0", gsit.version());
        assertEquals(T1, gsit.discovered());

        PluginRegistry.Entry mystery = r.get("Mystery").orElseThrow();
        assertEquals(Status.UNIDENTIFIED, mystery.status());
        assertEquals(null, mystery.source());
        assertFalse(mystery.retry());
    }

    @Test
    void configuredAndExcludedPluginsAreSkipped() throws Exception {
        PluginRegistry r = registry();
        Changes c = detect(r, List.of(plugin("GSit", "1", "https://modrinth.com/plugin/gsit"),
                plugin("Geyser-Spigot", "2", "https://geysermc.org")), Set.of("gsit", "GEYSER-SPIGOT"), false, T1);
        assertTrue(c.isEmpty());
        assertEquals(List.of(), r.entries());
        assertEquals(List.of(), lookedUp);
        assertFalse(Files.exists(dir.resolve("plugins.json")), "nothing to save");
    }

    @Test
    void knownPluginsAreNotLookedUpAgain() throws Exception {
        onModrinth.put("A.jar", "a");
        PluginRegistry r = registry();
        List<Candidate> installed = List.of(plugin("A", "1", null), plugin("B", "1", "https://example.com"));
        detect(r, installed, Set.of(), false, T1);
        assertEquals(List.of("A.jar", "B.jar"), lookedUp);

        lookedUp.clear();
        Changes again = detect(r, installed, Set.of(), false, T2);
        assertTrue(again.isEmpty());
        assertEquals(List.of(), lookedUp, "pending and unidentified-with-same-version are not looked up");
    }

    @Test
    void unidentifiedIsRetriedWhenVersionChangesOrForced() throws Exception {
        PluginRegistry r = registry();
        detect(r, List.of(plugin("B", "1", null)), Set.of(), false, T1);
        lookedUp.clear();

        detect(r, List.of(plugin("B", "1", null)), Set.of(), true, T2);
        assertEquals(List.of("B.jar"), lookedUp, "forced");

        lookedUp.clear();
        onModrinth.put("B.jar", "b-id");
        Changes c = detect(r, List.of(plugin("B", "2", null)), Set.of(), false, T2);
        assertEquals(List.of("B.jar"), lookedUp, "new version");
        assertEquals(List.of("B"), c.pending());
        assertEquals(List.of(), c.unidentified(), "not reported as new again");
        PluginRegistry.Entry b = r.get("B").orElseThrow();
        assertEquals(Status.PENDING, b.status());
        assertEquals("2", b.version());
        assertEquals(T1, b.discovered(), "discovery time is kept");
    }

    @Test
    void failedLookupIsRetriedNextTime() throws Exception {
        PluginRegistry r = registry();
        failWith = new IOException("timeout");
        Changes c = detect(r, List.of(plugin("A", "1", null)), Set.of(), false, T1);
        assertEquals(List.of("A"), c.unidentified());
        PluginRegistry.Entry a = r.get("A").orElseThrow();
        assertTrue(a.retry());
        assertTrue(a.reason().contains("timeout"), a.reason());

        failWith = null;
        onModrinth.put("A.jar", "a-id");
        lookedUp.clear();
        c = detect(r, List.of(plugin("A", "1", null)), Set.of(), false, T2);
        assertEquals(List.of("A.jar"), lookedUp);
        assertEquals(List.of("A"), c.pending());
        assertFalse(r.get("A").orElseThrow().retry());
    }

    @Test
    void uninstalledOrConfiguredPluginsAreForgottenButRejectionsKept() throws Exception {
        onModrinth.put("A.jar", "a");
        onModrinth.put("B.jar", "b");
        onModrinth.put("C.jar", "c");
        PluginRegistry r = registry();
        detect(r, List.of(plugin("A", "1", null), plugin("B", "1", null), plugin("C", "1", null),
                plugin("D", "1", null), plugin("E", "1", null)), Set.of(), false, T1);
        r.confirm("B", "op", T1);
        r.reject("C", "op", T1);

        // A uninstalled (pending), B uninstalled (confirmed), C uninstalled (rejected), D moved to config.yml,
        // E still unidentified.
        Changes c = detect(r, List.of(plugin("D", "1", null), plugin("E", "1", null)), Set.of("d"), false, T2);
        assertEquals(List.of("A", "B", "D"), c.removed());
        assertEquals(List.of("C", "E"), r.entries().stream().map(PluginRegistry.Entry::name).toList());
        assertEquals(Map.of(), r.confirmed());

        // B comes back (maybe a different jar with the same name): it must be reviewed again.
        c = detect(r, List.of(plugin("B", "2", null), plugin("D", "1", null), plugin("E", "1", null)), Set.of("d"), false, T2);
        assertEquals(List.of("B"), c.pending());
        assertEquals(Status.PENDING, r.get("B").orElseThrow().status());
    }

    @Test
    void configuredOrExcludedTakesOverConfirmedAndRejectedEntries() throws Exception {
        onModrinth.put("A.jar", "a");
        onModrinth.put("B.jar", "b");
        PluginRegistry r = registry();
        List<Candidate> installed = List.of(plugin("A", "1", null), plugin("B", "1", null));
        detect(r, installed, Set.of(), false, T1);
        r.confirm("A", "op", T1);
        r.reject("B", "op", T1);
        Changes c = detect(r, installed, Set.of("a", "b"), false, T2);
        assertEquals(List.of("A", "B"), c.removed());
        assertEquals(List.of(), r.entries());
    }

    @Test
    void websiteGuessAfterModrinthOutageIsReplacedByTheExactMatchLater() throws Exception {
        PluginRegistry r = registry();
        List<Candidate> installed = List.of(plugin("Foo", "1", "https://github.com/a/foo"));
        failWith = new IOException("Modrinth down");
        Changes c = detect(r, installed, Set.of(), false, T1);
        assertEquals(List.of("Foo"), c.pending());
        PluginRegistry.Entry guessed = r.get("Foo").orElseThrow();
        assertEquals("github", guessed.source());
        assertTrue(guessed.retry());

        // Still down: the source found before is kept.
        lookedUp.clear();
        c = detect(r, installed, Set.of(), false, T2);
        assertEquals(List.of("Foo.jar"), lookedUp);
        assertTrue(c.isEmpty(), c.toString());
        assertEquals("github", r.get("Foo").orElseThrow().source());

        // Back up: the exact hash match replaces the guess and ops are told again.
        failWith = null;
        onModrinth.put("Foo.jar", "foo-id");
        c = detect(r, installed, Set.of(), false, T2);
        assertEquals(List.of("Foo"), c.pending());
        PluginRegistry.Entry exact = r.get("Foo").orElseThrow();
        assertEquals("modrinth", exact.source());
        assertEquals(Map.of("project_id", "foo-id"), exact.options());
        assertFalse(exact.retry());
        assertEquals(T1, exact.discovered());

        // Nothing to retry any more.
        lookedUp.clear();
        detect(r, installed, Set.of(), false, T2);
        assertEquals(List.of(), lookedUp);
    }

    @Test
    void pendingSourceIsNotLostWhenALaterLookupFindsNothing() throws Exception {
        onModrinth.put("A.jar", "a");
        PluginRegistry r = registry();
        detect(r, List.of(plugin("A", "1", null)), Set.of(), false, T1);
        onModrinth.clear();
        Changes c = detect(r, List.of(plugin("A", "1", null)), Set.of(), true, T2);
        assertTrue(c.isEmpty());
        assertEquals("modrinth", r.get("A").orElseThrow().source());
        assertEquals(Status.PENDING, r.get("A").orElseThrow().status());
    }

    @Test
    void forcedRecheckOfUnchangedPendingIsNotReportedAsNew() throws Exception {
        onModrinth.put("A.jar", "a");
        PluginRegistry r = registry();
        detect(r, List.of(plugin("A", "1", null)), Set.of(), false, T1);
        lookedUp.clear();
        Changes c = detect(r, List.of(plugin("A", "1", null)), Set.of(), true, T2);
        assertEquals(List.of("A.jar"), lookedUp);
        assertTrue(c.isEmpty(), c.toString());
    }

    @Test
    void decisionsAreNotOverwrittenByDetection() throws Exception {
        onModrinth.put("A.jar", "a");
        PluginRegistry r = registry();
        List<Candidate> installed = List.of(plugin("A", "1", null));
        List<Candidate> lookups = r.toLookUp(installed, Set.of(), false);
        assertEquals(1, lookups.size());
        List<PluginDiscovery.Suggestion> suggestions = discovery().discover(lookups);
        // An op decides while the lookup runs; the late result must not reset the decision.
        r.apply(installed, Set.of(), suggestions, T1);
        r.reject("A", "op", T2);
        r.apply(installed, Set.of(), suggestions, T2);
        assertEquals(Status.REJECTED, r.get("A").orElseThrow().status());
    }

    // --- decisions -----------------------------------------------------------------------------

    @Test
    void confirmRejectAndConfirmAgain() throws Exception {
        onModrinth.put("A.jar", "a-id");
        PluginRegistry r = registry();
        detect(r, List.of(plugin("A", "1", null), plugin("U", "1", null)), Set.of(), false, T1);

        assertEquals(Outcome.CHANGED, r.confirm("a", "Steve", T2));
        assertEquals(Outcome.UNCHANGED, r.confirm("A", "Steve", T2));
        PluginRegistry.Entry a = r.get("A").orElseThrow();
        assertEquals(Status.CONFIRMED, a.status());
        assertEquals("Steve", a.decidedBy());
        assertEquals(T2, a.decided());
        assertEquals(Map.of("A", new PluginEntry("A", "modrinth", Map.of("project_id", "a-id"))), r.confirmed());

        assertEquals(Outcome.CHANGED, r.reject("A", "Alex", T2));
        assertEquals(Outcome.UNCHANGED, r.reject("A", "Alex", T2));
        assertEquals(Map.of(), r.confirmed());
        assertEquals(Outcome.CHANGED, r.confirm("A", "Alex", T2), "rejected plugins can be confirmed later");
        assertEquals(1, r.confirmed().size());

        assertEquals(Outcome.NO_SOURCE, r.confirm("U", "Steve", T2));
        assertEquals(Status.UNIDENTIFIED, r.get("U").orElseThrow().status());
        assertEquals(Outcome.CHANGED, r.reject("U", "Steve", T2), "unidentified plugins can be dismissed");
        assertEquals(Outcome.NO_SOURCE, r.confirm("U", "Steve", T2));
        assertEquals(Outcome.NOT_FOUND, r.confirm("Nope", "Steve", T2));
        assertEquals(Outcome.NOT_FOUND, r.reject("Nope", "Steve", T2));
    }

    @Test
    void rejectedUnidentifiedIsNotLookedUpAgain() throws Exception {
        PluginRegistry r = registry();
        detect(r, List.of(plugin("U", "1", null)), Set.of(), false, T1);
        r.reject("U", "op", T1);
        lookedUp.clear();
        detect(r, List.of(plugin("U", "2", null)), Set.of(), false, T2);
        assertEquals(List.of(), lookedUp);
        assertEquals(Status.REJECTED, r.get("U").orElseThrow().status());
    }

    @Test
    void confirmAllPendingSkipsOthers() throws Exception {
        onModrinth.put("A.jar", "a");
        onModrinth.put("B.jar", "b");
        onModrinth.put("C.jar", "c");
        PluginRegistry r = registry();
        detect(r, List.of(plugin("A", "1", null), plugin("B", "1", null), plugin("C", "1", null),
                plugin("U", "1", null)), Set.of(), false, T1);
        r.reject("C", "op", T1);

        assertEquals(List.of("A", "B"), r.confirmAllPending("op", T2));
        assertEquals(List.of(), r.confirmAllPending("op", T2));
        assertEquals(Set.of("A", "B"), r.confirmed().keySet());
        assertEquals(Status.REJECTED, r.get("C").orElseThrow().status());
        assertEquals(Status.UNIDENTIFIED, r.get("U").orElseThrow().status());
    }

    // --- persistence ---------------------------------------------------------------------------

    @Test
    void survivesRestart() throws Exception {
        onModrinth.put("A.jar", "a-id");
        PluginRegistry r = registry();
        detect(r, List.of(plugin("A", "1", null), plugin("S", "1", "https://www.spigotmc.org/resources/x.28140/"),
                plugin("U", "1", null)), Set.of(), false, T1);
        r.confirm("A", "Steve", T2);
        long revision = r.revision();
        assertTrue(revision > 0);

        PluginRegistry loaded = registry();
        assertEquals(r.entries(), loaded.entries());
        assertEquals("28140", loaded.get("S").orElseThrow().options().get("resource_id"), "numbers stay exact strings");
        assertEquals(r.confirmed(), loaded.confirmed());

        JsonObject json = JsonParser.parseString(Files.readString(dir.resolve("plugins.json"))).getAsJsonObject();
        assertTrue(json.get("_comment").getAsString().contains("Do not edit"));
        assertEquals("confirmed", json.getAsJsonObject("plugins").getAsJsonObject("A").get("status").getAsString());
        assertFalse(Files.exists(dir.resolve("plugins.json.tmp")));
    }

    @Test
    void brokenFileIsKeptAsideAndRegistryStartsEmpty() throws Exception {
        Files.writeString(dir.resolve("plugins.json"), "{ not json", StandardCharsets.UTF_8);
        PluginRegistry r = registry();
        assertEquals(List.of(), r.entries());
        assertTrue(Files.exists(dir.resolve("plugins.json.broken")));
    }

    @Test
    void handEditedInvalidEntriesAreRepairedOnLoad() throws Exception {
        Files.writeString(dir.resolve("plugins.json"), """
                {
                  "plugins": {
                    "BadSource": {"status": "confirmed", "source": "ftp", "options": {"x": "y"}},
                    "NoSource": {"status": "pending"},
                    "BadStatus": {"status": "maybe", "source": "modrinth"},
                    "Good": {"status": "CONFIRMED", "source": " Modrinth ", "options": {"project_id": "g"}},
                    "Rejected": {"status": "rejected"},
                    "Null": null
                  }
                }
                """, StandardCharsets.UTF_8);
        PluginRegistry r = registry();
        assertEquals(Status.UNIDENTIFIED, r.get("BadSource").orElseThrow().status());
        assertEquals(Map.of(), r.get("BadSource").orElseThrow().options());
        assertEquals(Status.UNIDENTIFIED, r.get("NoSource").orElseThrow().status());
        assertTrue(r.get("BadStatus").isEmpty(), "unknown status is dropped and looked at again");
        assertEquals(Status.REJECTED, r.get("Rejected").orElseThrow().status());
        assertEquals(Map.of("Good", new PluginEntry("Good", "modrinth", Map.of("project_id", "g"))), r.confirmed());
        assertEquals(Outcome.NO_SOURCE, r.confirm("BadSource", "op", T1));
    }

    @Test
    void oneBadEntryDoesNotLoseTheOtherDecisions() throws Exception {
        Files.writeString(dir.resolve("plugins.json"), """
                {
                  "plugins": {
                    "Good": {"status": "confirmed", "source": "github",
                             "options": {"repo": "a/b", "asset_pattern": ["x", "y"], "resource_id": 12345}},
                    "Weird": "not an object",
                    "Other": {"status": "rejected", "source": "modrinth", "options": {"project_id": "o"}, "retry": "yes"}
                  },
                  "extra": [1, 2, 3]
                }
                """, StandardCharsets.UTF_8);
        PluginRegistry r = registry();
        assertFalse(Files.exists(dir.resolve("plugins.json.broken")));
        PluginRegistry.Entry good = r.get("Good").orElseThrow();
        assertEquals(Status.CONFIRMED, good.status());
        assertEquals(Map.of("repo", "a/b", "resource_id", "12345"), good.options(), "non-scalar option values are dropped");
        assertTrue(r.get("Weird").isEmpty());
        assertEquals(Status.REJECTED, r.get("Other").orElseThrow().status());
        assertFalse(r.get("Other").orElseThrow().retry());
    }

    @Test
    void emptyOrMissingFile() throws Exception {
        assertEquals(List.of(), registry().entries());
        Files.writeString(dir.resolve("plugins.json"), "", StandardCharsets.UTF_8);
        assertEquals(List.of(), registry().entries());
        Files.writeString(dir.resolve("plugins.json"), "{}", StandardCharsets.UTF_8);
        assertEquals(List.of(), registry().entries());
    }
}
