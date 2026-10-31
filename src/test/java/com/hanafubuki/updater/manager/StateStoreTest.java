package com.hanafubuki.updater.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-01T04:00:00Z");

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("data").resolve("state.json");
    }

    private StateStore open() {
        return new StateStore(file(), null);
    }

    private static PluginResult result(String plugin, PluginResult.Status status, String candidate) {
        return new PluginResult(plugin, "modrinth", status, "1.0", candidate, null, List.of(), List.of(), null, 5);
    }

    @Test
    void stateSurvivesARestart() throws IOException {
        StateStore first = open();
        first.recordResult(result("LuckPerms", PluginResult.Status.UPDATED, "5.5.71"), T0);
        first.recordRun(new UpdateReport("schedule", false, T0, T0.plusSeconds(3), false,
                List.of(result("LuckPerms", PluginResult.Status.UPDATED, "5.5.71"), result("Vault", PluginResult.Status.UP_TO_DATE, "1.7.3"))));
        assertTrue(Files.isRegularFile(file()));
        assertFalse(Files.exists(file().resolveSibling("state.json.tmp")));

        StateStore second = open();

        assertEquals(Optional.of("5.5.71"), second.stagedVersion("LuckPerms"));
        StateStore.PluginStats stats = second.stats("LuckPerms").orElseThrow();
        assertEquals(1, stats.checks());
        assertEquals(1, stats.updates());
        assertEquals(0, stats.failures());
        assertEquals(T0, stats.lastCheck());
        assertEquals(T0, stats.lastUpdate());
        assertEquals("5.5.71", stats.lastSeenVersion());
        assertEquals("5.5.71", stats.stagedVersion());
        assertEquals("UPDATED", stats.lastResult());
        assertNull(stats.averageUpdateInterval());

        StateStore.LastRun run = second.lastRun().orElseThrow();
        assertEquals("schedule", run.trigger());
        assertFalse(run.dryRun());
        assertEquals(T0, run.started());
        assertEquals(T0.plusSeconds(3), run.finished());
        assertFalse(run.cancelled());
        assertEquals(Map.of("UPDATED", 1, "UP_TO_DATE", 1), run.counts());
    }

    @Test
    void emptyStateWithoutFile() {
        StateStore s = open();

        assertEquals(Optional.empty(), s.lastRun());
        assertEquals(Optional.empty(), s.stats("X"));
        assertEquals(Optional.empty(), s.stagedVersion("X"));
        assertTrue(s.allStats().isEmpty());
        assertTrue(s.allStaged().isEmpty());
        assertFalse(Files.exists(file()), "reading must not create the file");
    }

    @Test
    void corruptedFileIsMovedAsideAndEmptyStateIsUsed() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "{\"plugins\": {\"A\": [1, 2");

        StateStore s = open();

        assertTrue(s.allStats().isEmpty());
        Path broken = file().resolveSibling("state.json.broken");
        assertEquals("{\"plugins\": {\"A\": [1, 2", Files.readString(broken));
        assertFalse(Files.exists(file()));

        s.recordResult(result("A", PluginResult.Status.UP_TO_DATE, "1.0"), T0);
        assertEquals(1, open().stats("A").orElseThrow().checks());
    }

    @Test
    void emptyOrNullJsonGivesEmptyState() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "");
        assertTrue(open().allStats().isEmpty());

        Files.writeString(file(), "{\"version\":1,\"plugins\":{\"A\":null,\"B\":{\"checks\":2}}}");
        StateStore s = open();
        assertEquals(List.of("B"), s.allStats().stream().map(StateStore.PluginStats::plugin).toList());
        assertNull(s.stats("B").orElseThrow().lastUpdate());
    }

    @Test
    void stagedVersionCanBeSetAndCleared() {
        StateStore s = open();
        s.setStaged("Vault", "1.7.3", T0);
        s.setStaged("LuckPerms", "5.5.71", T0);

        assertEquals(Map.of("LuckPerms", "5.5.71", "Vault", "1.7.3"), s.allStaged());

        s.clearStaged("vault");
        assertEquals(Optional.empty(), s.stagedVersion("Vault"));
        assertEquals(Map.of("LuckPerms", "5.5.71"), open().allStaged());

        s.setStaged("LuckPerms", null, T0);
        assertTrue(open().allStaged().isEmpty());
        s.clearStaged("NeverSeen");
    }

    @Test
    void averageIntervalNeedsTwoUpdates() {
        StateStore s = open();
        s.recordResult(result("A", PluginResult.Status.UPDATED, "1.1"), T0);
        assertNull(s.stats("A").orElseThrow().averageUpdateInterval());

        s.recordResult(result("A", PluginResult.Status.UPDATED, "1.2"), T0.plus(Duration.ofHours(2)));
        s.recordResult(result("A", PluginResult.Status.UP_TO_DATE, "1.2"), T0.plus(Duration.ofHours(3)));
        s.recordResult(result("A", PluginResult.Status.UPDATED, "1.3"), T0.plus(Duration.ofHours(6)));

        StateStore.PluginStats stats = s.stats("A").orElseThrow();
        assertEquals(Duration.ofHours(3), stats.averageUpdateInterval());
        assertEquals(T0.plus(Duration.ofHours(6)), stats.lastUpdate());
        assertEquals(T0.plus(Duration.ofHours(6)), stats.lastCheck());
        assertEquals(4, stats.checks());
        assertEquals(3, stats.updates());
    }

    @Test
    void historyIsCappedButCountersAreNot() {
        StateStore s = open();
        for (int i = 0; i < 60; i++) {
            s.recordResult(result("A", PluginResult.Status.UPDATED, "1." + i), T0.plus(Duration.ofDays(i)));
        }

        StateStore.PluginStats stats = s.stats("A").orElseThrow();
        assertEquals(60, stats.updates());
        // Average over the last 50 updates, one day apart.
        assertEquals(Duration.ofDays(1), stats.averageUpdateInterval());
        assertEquals(T0.plus(Duration.ofDays(59)), stats.lastUpdate());
    }

    @Test
    void failuresAndLastSeenVersion() {
        StateStore s = open();
        s.recordResult(result("A", PluginResult.Status.UP_TO_DATE, "2.0"), T0);
        s.recordResult(result("A", PluginResult.Status.FAILED, null), T0.plusSeconds(60));
        s.recordResult(result("A", PluginResult.Status.FAILED, null), T0.plusSeconds(120));

        StateStore.PluginStats stats = s.stats("A").orElseThrow();
        assertEquals(3, stats.checks());
        assertEquals(2, stats.failures());
        assertEquals(0, stats.updates());
        assertEquals("2.0", stats.lastSeenVersion(), "a failed check keeps the last version seen");
        assertEquals("FAILED", stats.lastResult());
        assertNull(stats.stagedVersion());
    }

    @Test
    void namesAreCaseInsensitive() {
        StateStore s = open();
        s.recordResult(result("LuckPerms", PluginResult.Status.UPDATED, "5.5.71"), T0);
        s.recordResult(result("luckperms", PluginResult.Status.UP_TO_DATE, "5.5.71"), T0.plusSeconds(1));

        assertEquals(1, s.allStats().size());
        StateStore.PluginStats stats = s.stats("LUCKPERMS").orElseThrow();
        assertEquals("LuckPerms", stats.plugin());
        assertEquals(2, stats.checks());
        assertEquals(Optional.of("5.5.71"), open().stagedVersion("LUCKPERMS"));
    }
}
