package com.hanafubuki.updater.manager;

import static com.hanafubuki.updater.testing.TestJson.arr;
import static com.hanafubuki.updater.testing.TestJson.obj;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import com.hanafubuki.updater.config.PluginEntry;
import com.hanafubuki.updater.config.SettingsParser;
import com.hanafubuki.updater.config.UpdaterSettings;
import com.hanafubuki.updater.source.GitHubAdvisories;
import com.hanafubuki.updater.source.InstalledPlugin;
import com.hanafubuki.updater.source.UpdateCandidate;
import com.hanafubuki.updater.source.UpdateCandidate.Freshness;
import com.hanafubuki.updater.source.UpdateRequest;
import com.hanafubuki.updater.source.UpdateSource;
import com.hanafubuki.updater.testing.FakeHttpServer;
import com.hanafubuki.updater.testing.TestHttp;
import com.hanafubuki.updater.testing.TestJars;
import com.hanafubuki.updater.util.Checksum;

class UpdateManagerTest {

    private static final String MC = "1.21.9";
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(15);

    private static final String CHUNKY = """
            plugins:
              Chunky:
                source: custom
            """;

    @TempDir
    Path dir;

    private Path plugins;
    private Path updateFolder;
    private Path repo;
    private FakeSource source;
    private StateStore state;
    private BackupManager backups;
    private RecordingListener listener;
    private UpdateManager manager;
    private final Map<String, InstalledPlugin> installed = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        plugins = Files.createDirectories(dir.resolve("plugins"));
        updateFolder = plugins.resolve("update");
        repo = Files.createDirectories(dir.resolve("repo"));
        source = new FakeSource("custom");
        state = new StateStore(dir.resolve("data").resolve("state.json"), null);
        backups = new BackupManager(dir.resolve("data").resolve("backups"));
        listener = new RecordingListener();
        manager = newManager(null);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown(2000);
    }

    // --- fixtures ----------------------------------------------------------------------------

    /** A fake source: answers per plugin name, records calls. */
    static final class FakeSource implements UpdateSource {

        @FunctionalInterface
        interface Lookup {
            Optional<UpdateCandidate> find(UpdateRequest request) throws IOException;
        }

        private final String id;
        private final Map<String, Lookup> lookups = new ConcurrentHashMap<>();
        private final Map<String, List<String>> problems = new ConcurrentHashMap<>();
        final List<String> calls = new CopyOnWriteArrayList<>();

        FakeSource(String id) {
            this.id = id;
        }

        void answer(String plugin, Lookup lookup) {
            lookups.put(plugin.toLowerCase(Locale.ROOT), lookup);
        }

        void answer(String plugin, UpdateCandidate candidate) {
            answer(plugin, r -> Optional.of(candidate));
        }

        void problems(String plugin, String... texts) {
            problems.put(plugin.toLowerCase(Locale.ROOT), List.of(texts));
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public List<String> validate(PluginEntry entry) {
            return problems.getOrDefault(entry.name().toLowerCase(Locale.ROOT), List.of());
        }

        @Override
        public Optional<UpdateCandidate> findLatest(UpdateRequest request) throws IOException {
            calls.add(request.pluginName());
            Lookup lookup = lookups.get(request.pluginName().toLowerCase(Locale.ROOT));
            return lookup == null ? Optional.empty() : lookup.find(request);
        }
    }

    /** Records listener calls. */
    static final class RecordingListener implements UpdateListener {
        final List<String> started = new CopyOnWriteArrayList<>();
        final List<PluginResult> results = new CopyOnWriteArrayList<>();
        final List<UpdateReport> finished = new CopyOnWriteArrayList<>();

        @Override
        public void onRunStarted(String trigger, boolean dryRun, int count) {
            started.add(trigger + "|" + dryRun + "|" + count);
        }

        @Override
        public void onPluginResult(PluginResult result) {
            results.add(result);
        }

        @Override
        public void onRunFinished(UpdateReport report) {
            finished.add(report);
        }
    }

    private UpdateManager newManager(GitHubAdvisories advisories) {
        return new UpdateManager(Map.of("custom", source), new Installer(TestHttp.service()), advisories, backups, state, listener,
                Logger.getLogger("UpdateManagerTest"), Clock.systemUTC());
    }

    private static UpdaterSettings settings(String... sections) {
        Map<String, Object> root = new Yaml().load(String.join("", sections));
        SettingsParser.Result r = SettingsParser.parse(root, name -> null);
        assertEquals(List.of(), r.problems(), "test config must be valid");
        return r.settings();
    }

    /** Installs a plugin: jar in plugins/ with a file name that is not just the plugin name. */
    private InstalledPlugin install(String name, String version) throws IOException {
        Path jar = TestJars.write(plugins, name + "-Bukkit.jar", TestJars.plugin(name, version));
        InstalledPlugin p = new InstalledPlugin(name, version, jar);
        installed.put(name, p);
        return p;
    }

    /** A new version published in the fake repository, with a file: URL and a SHA-256 checksum. */
    private UpdateCandidate published(String name, String version, Freshness freshness) throws IOException {
        return published(name, version, freshness, List.of(), null);
    }

    private UpdateCandidate published(String name, String version, Freshness freshness, List<String> depend, String apiVersion)
            throws IOException {
        Path jar = TestJars.write(repo, name + "-" + version + ".jar", TestJars.plugin(name, version, depend, apiVersion));
        return new UpdateCandidate(version, jar.toUri(), new Checksum(Checksum.Algorithm.SHA256, Checksum.hash(jar, Checksum.Algorithm.SHA256)),
                Map.of(), freshness, "https://example.org/" + name + "/" + version, List.of("note from source"));
    }

    private static UpdateCandidate withoutChecksum(UpdateCandidate c) {
        return new UpdateCandidate(c.version(), c.downloadUrl(), null, c.downloadHeaders(), c.freshness(), c.pageUrl(), c.notes());
    }

    private UpdateReport run(UpdaterSettings settings, RunOptions options) {
        return assertTimeoutPreemptively(RUN_TIMEOUT,
                () -> manager.start(settings, installed, updateFolder, MC, options).orElseThrow().result().get());
    }

    private UpdateReport run(UpdaterSettings settings) {
        return run(settings, RunOptions.all(false, "test"));
    }

    private static PluginResult only(UpdateReport report) {
        assertEquals(1, report.results().size(), report.results().toString());
        return report.results().get(0);
    }

    private static Map<String, PluginResult> byName(UpdateReport report) {
        return report.results().stream().collect(Collectors.toMap(PluginResult::plugin, r -> r));
    }

    private List<String> updateFolderFiles() throws IOException {
        if (!Files.isDirectory(updateFolder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(updateFolder)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private Path staged(String plugin) {
        return Installer.stagedPath(installed.get(plugin), updateFolder);
    }

    // --- dry run and staging -----------------------------------------------------------------

    @Test
    void dryRunReportsAvailableAndStagesNothing() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));

        UpdateReport report = run(settings(CHUNKY), RunOptions.all(true, "test"));

        PluginResult r = only(report);
        assertEquals(PluginResult.Status.AVAILABLE, r.status());
        assertEquals("1.0", r.installedVersion());
        assertEquals("2.0", r.candidateVersion());
        assertTrue(report.dryRun());
        assertEquals(List.of(), updateFolderFiles(), "a dry run must never download anything");
        assertEquals(List.of(), backups.list("Chunky"), "a dry run must not make backups");
        assertEquals(Optional.empty(), state.stagedVersion("Chunky"));
        assertEquals(1, manager.metrics().snapshot().get("updates_found_total"));
        assertEquals(0, manager.metrics().snapshot().get("downloads_success_total"));
    }

    @Test
    void realRunStagesUnderTheInstalledJarNameAndMakesABackup() throws IOException {
        InstalledPlugin chunky = install("Chunky", "1.0");
        byte[] runningJar = Files.readAllBytes(chunky.jar());
        UpdateCandidate candidate = published("Chunky", "2.0", Freshness.UNKNOWN);
        source.answer("Chunky", candidate);

        PluginResult r = only(run(settings(CHUNKY)));

        assertEquals(PluginResult.Status.UPDATED, r.status());
        assertEquals("2.0", r.candidateVersion());
        assertEquals("custom", r.source());
        assertEquals("https://example.org/Chunky/2.0", r.pageUrl());
        assertTrue(r.notes().contains("note from source"), r.notes().toString());
        assertEquals(List.of("Chunky-Bukkit.jar"), updateFolderFiles());
        assertArrayEquals(Files.readAllBytes(Path.of(candidate.downloadUrl())), Files.readAllBytes(staged("Chunky")));
        assertArrayEquals(runningJar, Files.readAllBytes(chunky.jar()), "the running jar is never touched");

        List<BackupManager.Backup> list = backups.list("Chunky");
        assertEquals(1, list.size());
        assertEquals("1.0", list.get(0).version());
        assertArrayEquals(runningJar, Files.readAllBytes(list.get(0).file()));

        assertEquals(Optional.of("2.0"), state.stagedVersion("Chunky"));
        assertEquals(1, manager.metrics().snapshot().get("downloads_success_total"));
    }

    @Test
    void downloadOverHttpIsStaged() throws IOException {
        install("Chunky", "1.0");
        byte[] jar = TestJars.plugin("Chunky", "2.0");
        try (FakeHttpServer server = FakeHttpServer.start()) {
            server.route("/Chunky-2.0.jar", req -> FakeHttpServer.Reply.bytes(200, jar));
            source.answer("Chunky", new UpdateCandidate("2.0", server.uri("/Chunky-2.0.jar"), null, Map.of(), Freshness.UNKNOWN, null,
                    List.of()));

            assertEquals(PluginResult.Status.UPDATED, only(run(settings(CHUNKY))).status());
            assertArrayEquals(jar, Files.readAllBytes(staged("Chunky")));
        }
    }

    @Test
    void backupsCanBeDisabled() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));

        PluginResult r = only(run(settings("backups:\n  enabled: false\n", CHUNKY)));

        assertEquals(PluginResult.Status.UPDATED, r.status());
        assertEquals(List.of(), backups.list("Chunky"));
    }

    @Test
    void secondRunReportsAlreadyStaged() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));
        UpdaterSettings settings = settings(CHUNKY);
        run(settings);

        PluginResult second = only(run(settings));
        PluginResult dry = only(run(settings, RunOptions.all(true, "test")));

        assertEquals(PluginResult.Status.ALREADY_STAGED, second.status());
        assertEquals(PluginResult.Status.ALREADY_STAGED, dry.status());
        assertEquals(1, backups.list("Chunky").size());
        assertEquals(1, manager.metrics().snapshot().get("downloads_success_total"));
    }

    @Test
    void alreadyStagedWithoutChecksumUsesTheStagedVersion() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", withoutChecksum(published("Chunky", "2.0", Freshness.UNKNOWN)));
        UpdaterSettings settings = settings(CHUNKY);

        assertEquals(PluginResult.Status.UPDATED, only(run(settings)).status());
        assertEquals(PluginResult.Status.ALREADY_STAGED, only(run(settings)).status());
    }

    @Test
    void newerReleaseReplacesTheStagedOne() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));
        UpdaterSettings settings = settings(CHUNKY);
        run(settings);

        UpdateCandidate v3 = published("Chunky", "3.0", Freshness.UNKNOWN);
        source.answer("Chunky", v3);
        PluginResult r = only(run(settings));

        assertEquals(PluginResult.Status.UPDATED, r.status());
        assertArrayEquals(Files.readAllBytes(Path.of(v3.downloadUrl())), Files.readAllBytes(staged("Chunky")));
        assertEquals(Optional.of("3.0"), state.stagedVersion("Chunky"));
    }

    @Test
    void stagedStateIsClearedAfterTheRestartAppliedIt() throws IOException {
        InstalledPlugin old = install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));
        UpdaterSettings settings = settings(CHUNKY);
        run(settings);

        // What Paper does on restart: the staged jar replaces the running one.
        Files.move(staged("Chunky"), old.jar(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        installed.put("Chunky", new InstalledPlugin("Chunky", "2.0", old.jar()));

        PluginResult r = only(run(settings));

        assertEquals(PluginResult.Status.UP_TO_DATE, r.status());
        assertEquals(Optional.empty(), state.stagedVersion("Chunky"));
    }

    @Test
    void stagedStateIsClearedWhenTheInstalledVersionEqualsIt() throws IOException {
        InstalledPlugin old = install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "v2.0", Freshness.UNKNOWN));
        UpdaterSettings settings = settings(CHUNKY);
        run(settings);
        assertEquals(Optional.of("v2.0"), state.stagedVersion("Chunky"));

        // The update folder still has a file (e.g. copied back by hand), but 2.0 is what runs now.
        installed.put("Chunky", new InstalledPlugin("Chunky", "2.0", old.jar()));
        PluginResult r = only(run(settings));

        assertEquals(PluginResult.Status.UP_TO_DATE, r.status());
        assertEquals(Optional.empty(), state.stagedVersion("Chunky"));
    }

    @Test
    void stagedFileRemovedByHandIsStagedAgain() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));
        UpdaterSettings settings = settings(CHUNKY);
        run(settings);
        Files.delete(staged("Chunky"));

        assertEquals(PluginResult.Status.UPDATED, only(run(settings)).status());
        assertTrue(Files.exists(staged("Chunky")));
    }

    // --- one run at a time and cancellation --------------------------------------------------

    /** A lookup that blocks until released or interrupted. */
    private static FakeSource.Lookup blocking(CountDownLatch entered, CountDownLatch release, UpdateCandidate candidate) {
        return r -> {
            entered.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IOException("test lookup was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted");
            }
            return Optional.of(candidate);
        };
    }

    @Test
    void onlyOneRunAtATime() throws Exception {
        install("Chunky", "1.0");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        source.answer("Chunky", blocking(entered, release, published("Chunky", "2.0", Freshness.UNKNOWN)));
        UpdaterSettings settings = settings(CHUNKY);

        UpdateManager.RunHandle first = manager.start(settings, installed, updateFolder, MC, RunOptions.all(false, "first")).orElseThrow();
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        assertTrue(manager.isRunning());
        assertEquals(Optional.empty(), manager.start(settings, installed, updateFolder, MC, RunOptions.all(true, "second")));

        release.countDown();
        UpdateReport report = assertTimeoutPreemptively(RUN_TIMEOUT, () -> first.result().get());
        assertEquals(PluginResult.Status.UPDATED, only(report).status());
        assertEquals("first", report.trigger());

        assertFalse(manager.isRunning());
        assertEquals(PluginResult.Status.ALREADY_STAGED, only(run(settings)).status());
    }

    @Test
    void aRunCanBeStartedWhenThePreviousOneCompletes() throws Exception {
        install("Chunky", "1.0");
        UpdaterSettings settings = settings(CHUNKY);
        AtomicReference<Optional<UpdateManager.RunHandle>> chained = new AtomicReference<>();
        CountDownLatch gate = new CountDownLatch(1);
        source.answer("Chunky", blocking(new CountDownLatch(1), gate, published("Chunky", "2.0", Freshness.UNKNOWN)));

        UpdateManager.RunHandle first = manager.start(settings, installed, updateFolder, MC, RunOptions.all(true, "first")).orElseThrow();
        CountDownLatch callbackDone = new CountDownLatch(1);
        // Registered while the run is still busy, so it runs in the coordinator thread when the run completes.
        first.result().thenRun(() -> {
            chained.set(manager.start(settings, installed, updateFolder, MC, RunOptions.all(true, "chained")));
            callbackDone.countDown();
        });
        gate.countDown();

        assertTimeoutPreemptively(RUN_TIMEOUT, () -> first.result().get());
        assertTrue(callbackDone.await(10, TimeUnit.SECONDS));
        assertTrue(chained.get().isPresent(), "a finished run must not block the next one");
        UpdateReport second = assertTimeoutPreemptively(RUN_TIMEOUT, () -> chained.get().orElseThrow().result().get());
        assertEquals("chained", second.trigger());
    }

    /**
     * Waits until the run's coordinator thread has submitted every plugin and waits for the first result,
     * so a cancel hits a run with queued work.
     */
    private static void awaitCoordinatorWaitingForResults() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            while (true) {
                for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                    if (e.getKey().getName().equals("HanafubukiUpdater-run") && Stream.of(e.getValue()).anyMatch(
                            f -> f.getClassName().equals("java.util.concurrent.FutureTask") && f.getMethodName().equals("get"))) {
                        return;
                    }
                }
                Thread.sleep(5);
            }
        });
    }

    @Test
    void cancelStopsTheRunAndReportsCancelled() throws Exception {
        install("Chunky", "1.0");
        install("Vault", "1.7");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        source.answer("Chunky", blocking(entered, never, published("Chunky", "2.0", Freshness.UNKNOWN)));
        source.answer("Vault", blocking(entered, never, published("Vault", "2.0", Freshness.UNKNOWN)));
        // One worker: the second plugin is still queued when the run is cancelled.
        UpdaterSettings settings = settings("global:\n  parallelism: 1\n", CHUNKY, "  Vault:\n    source: custom\n");

        UpdateManager.RunHandle handle = manager.start(settings, installed, updateFolder, MC, RunOptions.all(false, "test")).orElseThrow();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        awaitCoordinatorWaitingForResults();
        assertTrue(manager.cancel());

        UpdateReport report = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> handle.result().get());

        assertTrue(report.cancelled());
        assertEquals(2, report.results().size());
        assertTrue(report.results().stream().allMatch(r -> r.status() == PluginResult.Status.CANCELLED), report.results().toString());
        assertEquals(List.of(), updateFolderFiles());
        assertEquals(1, listener.finished.size());
        assertTrue(state.lastRun().orElseThrow().cancelled());
        assertEquals(Optional.empty(), state.stats("Chunky"), "cancelled checks are not recorded as checks");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (manager.isRunning()) {
                Thread.sleep(10);
            }
        });
        assertFalse(manager.cancel(), "nothing left to cancel");
    }

    @Test
    void shutdownCancelsAndWaits() throws Exception {
        install("Chunky", "1.0");
        CountDownLatch entered = new CountDownLatch(1);
        source.answer("Chunky", blocking(entered, new CountDownLatch(1), published("Chunky", "2.0", Freshness.UNKNOWN)));

        UpdateManager.RunHandle handle = manager.start(settings(CHUNKY), installed, updateFolder, MC, RunOptions.all(false, "test"))
                .orElseThrow();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> manager.shutdown(4000));

        assertTrue(handle.result().isDone());
        assertEquals(PluginResult.Status.CANCELLED, only(handle.result().get()).status());
        assertFalse(manager.isRunning());
    }

    @Test
    void cancelWithoutRunReturnsFalse() {
        assertFalse(manager.cancel());
        manager.shutdown(100);
    }

    // --- selection ---------------------------------------------------------------------------

    private static final String THREE_PLUGINS = """
            plugins:
              Chunky:
                source: custom
              Vault:
                source: custom
              LuckPerms:
                source: custom
            """;

    private void installThree() throws IOException {
        for (String name : List.of("Chunky", "Vault", "LuckPerms")) {
            install(name, "1.0");
            source.answer(name, published(name, "2.0", Freshness.UNKNOWN));
        }
    }

    @Test
    void includeOnlyModeProcessesOnlyTheIncludeList() throws IOException {
        installThree();

        UpdateReport report = run(settings("modes:\n  include_only: true\n  include_list: [chunky, LuckPerms]\n", THREE_PLUGINS),
                RunOptions.all(true, "test"));

        assertEquals(List.of("Chunky", "LuckPerms"), report.results().stream().map(PluginResult::plugin).toList());
        assertFalse(source.calls.contains("Vault"));
    }

    @Test
    void excludeListWorksInBothModes() throws IOException {
        installThree();

        UpdateReport all = run(settings("modes:\n  exclude_list: [vault]\n", THREE_PLUGINS), RunOptions.all(true, "test"));
        assertEquals(List.of("Chunky", "LuckPerms"), all.results().stream().map(PluginResult::plugin).toList());

        UpdateReport included = run(settings("modes:\n  include_only: true\n  include_list: [Chunky, Vault]\n  exclude_list: [Vault]\n",
                THREE_PLUGINS), RunOptions.all(true, "test"));
        assertEquals(List.of("Chunky"), included.results().stream().map(PluginResult::plugin).toList());
        assertFalse(source.calls.contains("Vault"));
    }

    @Test
    void explicitPluginListReportsUnknownAndExcludedNames() throws IOException {
        installThree();
        UpdaterSettings settings = settings("modes:\n  include_only: true\n  include_list: []\n  exclude_list: [Vault]\n", THREE_PLUGINS);

        UpdateReport report = run(settings, new RunOptions(true, Set.of("chunky", "Vault", "NoSuchPlugin"), "command:Steve"));

        Map<String, PluginResult> results = byName(report);
        assertEquals(Set.of("Chunky", "Vault", "NoSuchPlugin"), results.keySet());
        assertEquals(PluginResult.Status.AVAILABLE, results.get("Chunky").status(), "only ignores include_only");
        assertEquals(PluginResult.Status.SKIPPED, results.get("Vault").status());
        assertTrue(results.get("Vault").message().contains("exclude_list"));
        assertEquals(PluginResult.Status.SKIPPED, results.get("NoSuchPlugin").status());
        assertTrue(results.get("NoSuchPlugin").message().contains("not configured"));
        assertEquals(List.of("Chunky"), source.calls);
        assertEquals(List.of("command:Steve|true|1"), listener.started);
    }

    // --- version limits ----------------------------------------------------------------------

    @Test
    void globalMinVersionSkipsOlderInstalls() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));

        PluginResult r = only(run(settings("global:\n  min_version: '1.5'\n", CHUNKY)));

        assertEquals(PluginResult.Status.SKIPPED, r.status());
        assertTrue(r.message().contains("min_version 1.5"), r.message());
        assertEquals(List.of(), source.calls);
    }

    @Test
    void pluginMinVersionOverridesGlobal() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));

        PluginResult r = only(run(settings("global:\n  min_version: '1.5'\n",
                "plugins:\n  Chunky:\n    source: custom\n    min_version: '0.5'\n")));

        assertEquals(PluginResult.Status.UPDATED, r.status());
    }

    @Test
    void globalMaxVersionSkipsNewerCandidates() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));

        PluginResult r = only(run(settings("global:\n  max_version: '1.9'\n", CHUNKY)));

        assertEquals(PluginResult.Status.SKIPPED, r.status());
        assertEquals("2.0", r.candidateVersion());
        assertTrue(r.message().contains("max_version 1.9"), r.message());
        assertEquals(List.of(), updateFolderFiles());
    }

    @Test
    void pluginMaxVersionOverridesGlobal() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));

        PluginResult r = only(run(settings("global:\n  max_version: '1.9'\n",
                "plugins:\n  Chunky:\n    source: custom\n    max_version: '3.0'\n")));

        assertEquals(PluginResult.Status.UPDATED, r.status());
    }

    @Test
    void unboundedLimitsAreIgnored() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));

        PluginResult r = only(run(settings("global:\n  min_version: none\n  max_version: latest\n", CHUNKY)));

        assertEquals(PluginResult.Status.UPDATED, r.status());
    }

    // --- per plugin outcomes -----------------------------------------------------------------

    @Test
    void pluginThatIsNotInstalledIsSkipped() {
        PluginResult r = only(run(settings(CHUNKY)));

        assertEquals(PluginResult.Status.SKIPPED, r.status());
        assertNull(r.installedVersion());
        assertTrue(r.message().contains("not installed"));
        assertEquals(List.of(), source.calls);
    }

    @Test
    void installedPluginNamesMatchCaseInsensitively() throws IOException {
        Path jar = TestJars.write(plugins, "chunky.jar", TestJars.plugin("Chunky", "1.0"));
        installed.put("chunky", new InstalledPlugin("Chunky", "1.0", jar));
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));

        assertEquals(PluginResult.Status.UPDATED, only(run(settings(CHUNKY))).status());
    }

    @Test
    void invalidConfigFails() throws IOException {
        install("Chunky", "1.0");
        source.problems("Chunky", "missing 'url'", "bad auth");

        PluginResult r = only(run(settings(CHUNKY)));

        assertEquals(PluginResult.Status.FAILED, r.status());
        assertEquals("invalid config: missing 'url'; bad auth", r.message());
        assertEquals(List.of(), source.calls);
    }

    @Test
    void sourceWithoutHandlerFails() throws IOException {
        install("Chunky", "1.0");

        PluginResult r = only(run(settings("plugins:\n  Chunky:\n    source: modrinth\n")));

        assertEquals(PluginResult.Status.FAILED, r.status());
        assertTrue(r.message().contains("modrinth"), r.message());
    }

    @Test
    void sourceErrorFailsAndIsCounted() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", req -> {
            throw new IOException("Modrinth answered HTTP 503");
        });

        PluginResult r = only(run(settings(CHUNKY)));

        assertEquals(PluginResult.Status.FAILED, r.status());
        assertEquals("Modrinth answered HTTP 503", r.message());
        assertEquals(1, manager.metrics().snapshot().get("errors_total"));
        assertEquals(0, manager.metrics().snapshot().get("downloads_failed_total"));
        assertEquals(1, state.stats("Chunky").orElseThrow().failures());
    }

    @Test
    void unexpectedRuntimeExceptionFailsOnlyThatPlugin() throws IOException {
        install("Chunky", "1.0");
        install("Vault", "1.0");
        source.answer("Chunky", req -> {
            throw new IllegalStateException("bug in a source");
        });
        source.answer("Vault", published("Vault", "2.0", Freshness.UNKNOWN));

        Map<String, PluginResult> results = byName(run(settings(CHUNKY, "  Vault:\n    source: custom\n")));

        assertEquals(PluginResult.Status.FAILED, results.get("Chunky").status());
        assertEquals("bug in a source", results.get("Chunky").message());
        assertEquals(PluginResult.Status.UPDATED, results.get("Vault").status());
    }

    @Test
    void downloadFailureFailsAndStagesNothing() throws IOException {
        install("Chunky", "1.0");
        UpdateCandidate good = published("Chunky", "2.0", Freshness.UNKNOWN);
        source.answer("Chunky", new UpdateCandidate("2.0", good.downloadUrl(),
                new Checksum(Checksum.Algorithm.SHA256, "0".repeat(64)), Map.of(), Freshness.UNKNOWN, null, List.of()));

        PluginResult r = only(run(settings(CHUNKY)));

        assertEquals(PluginResult.Status.FAILED, r.status());
        assertEquals("2.0", r.candidateVersion());
        assertTrue(r.message().contains("Checksum mismatch"), r.message());
        assertEquals(List.of(), updateFolderFiles());
        assertEquals(Optional.empty(), state.stagedVersion("Chunky"));
        assertEquals(1, manager.metrics().snapshot().get("downloads_failed_total"));
    }

    @Test
    void noCompatibleVersion() throws IOException {
        install("Chunky", "1.0");

        PluginResult r = only(run(settings(CHUNKY)));

        assertEquals(PluginResult.Status.NO_VERSION, r.status());
        assertTrue(r.message().contains(MC));
    }

    @Test
    void freshnessSameIsUpToDateWhateverTheVersionString() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "9.9", Freshness.SAME));

        PluginResult r = only(run(settings(CHUNKY)));

        assertEquals(PluginResult.Status.UP_TO_DATE, r.status());
        assertEquals("9.9", r.candidateVersion());
        assertEquals(List.of(), updateFolderFiles());
    }

    @Test
    void freshnessNewerUpdatesWhateverTheVersionString() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "0.9-hotfix", Freshness.NEWER));

        assertEquals(PluginResult.Status.UPDATED, only(run(settings(CHUNKY))).status());
    }

    @Test
    void freshnessUnknownComparesVersions() throws IOException {
        install("Chunky", "1.0");
        UpdaterSettings settings = settings(CHUNKY);

        source.answer("Chunky", published("Chunky", "1.0", Freshness.UNKNOWN));
        assertEquals(PluginResult.Status.UP_TO_DATE, only(run(settings, RunOptions.all(true, "t"))).status());

        source.answer("Chunky", published("Chunky", "0.9", Freshness.UNKNOWN));
        assertEquals(PluginResult.Status.UP_TO_DATE, only(run(settings, RunOptions.all(true, "t"))).status());

        source.answer("Chunky", published("Chunky", "v1.0.1", Freshness.UNKNOWN));
        assertEquals(PluginResult.Status.AVAILABLE, only(run(settings, RunOptions.all(true, "t"))).status());
    }

    // --- dependencies ------------------------------------------------------------------------

    @Test
    void missingDependencyBlocksAndStagesNothing() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN, List.of("Vault"), null));

        UpdateReport report = run(settings("dependencies:\n  check: block\n", CHUNKY));
        PluginResult r = only(report);

        assertEquals(PluginResult.Status.BLOCKED, r.status());
        assertEquals("2.0", r.candidateVersion());
        assertTrue(r.message().contains("Vault"), r.message());
        assertEquals(List.of(), updateFolderFiles());
        assertEquals(Optional.empty(), state.stagedVersion("Chunky"));
        assertEquals(List.of(r), report.failures());
    }

    @Test
    void tooNewApiVersionBlocks() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN, List.of(), "1.22"));

        PluginResult r = only(run(settings(CHUNKY)));

        assertEquals(PluginResult.Status.BLOCKED, r.status());
        assertTrue(r.message().contains("api-version 1.22"), r.message());
    }

    @Test
    void installedDependencyDoesNotBlock() throws IOException {
        install("Chunky", "1.0");
        install("Vault", "1.7");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN, List.of("vault"), "1.21"));

        Map<String, PluginResult> results = byName(run(settings(CHUNKY)));

        assertEquals(PluginResult.Status.UPDATED, results.get("Chunky").status());
    }

    @Test
    void dependencyWarnModeUpdatesWithANote() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN, List.of("Vault"), null));

        PluginResult r = only(run(settings("dependencies:\n  check: warn\n", CHUNKY)));

        assertEquals(PluginResult.Status.UPDATED, r.status());
        assertTrue(r.notes().contains("needs plugins that are not installed: Vault"), r.notes().toString());
        assertTrue(Files.exists(staged("Chunky")));
    }

    @Test
    void dependencyCheckOff() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN, List.of("Vault"), "1.22"));

        // Unquoted "off" is read as boolean false by YAML 1.1 (SnakeYAML, also Bukkit's config loader).
        UpdaterSettings settings = settings("dependencies:\n  check: off\nschedule:\n  mode: off\n", CHUNKY);
        assertEquals(UpdaterSettings.DependencyMode.OFF, settings.dependencies());

        PluginResult r = only(run(settings));

        assertEquals(PluginResult.Status.UPDATED, r.status());
        assertEquals(List.of("note from source"), r.notes());
    }

    // --- security advisories -----------------------------------------------------------------

    private static final String ADVISORIES = "/repos/Owner/Chunky/security-advisories";

    private static String advisory(String range) {
        return arr(obj("ghsa_id", "GHSA-xxxx-yyyy-zzzz", "cve_id", null, "html_url", "https://github.com/Owner/Chunky/security/advisories/1",
                "summary", "Path traversal", "severity", "high", "withdrawn_at", null,
                "vulnerabilities", arr(obj("package", obj("ecosystem", "maven", "name", "chunky"),
                        "vulnerable_version_range", range, "patched_versions", "2.0")))).toString();
    }

    private UpdateManager managerWithAdvisories(FakeHttpServer server) {
        return newManager(new GitHubAdvisories(TestHttp.service(), server.base(), Map.of(), Duration.ofSeconds(5)));
    }

    @Test
    void advisoryAlertIsAttachedWhenTheEntryHasARepo() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));
        try (FakeHttpServer server = FakeHttpServer.start()) {
            server.json(ADVISORIES, advisory("< 2.0"));
            manager = managerWithAdvisories(server);

            UpdateReport report = run(settings("plugins:\n  Chunky:\n    source: custom\n    repo: Owner/Chunky\n"));

            PluginResult r = only(report);
            assertEquals(PluginResult.Status.UPDATED, r.status());
            assertEquals(1, r.alerts().size());
            assertEquals("GHSA-xxxx-yyyy-zzzz", r.alerts().get(0).id());
            assertEquals(List.of(r), report.withAlerts());
            assertEquals(1, manager.metrics().snapshot().get("security_alerts_total"));
        }
    }

    @Test
    void noAdvisoryRequestWithoutRepoOrWhenDisabled() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));
        try (FakeHttpServer server = FakeHttpServer.start()) {
            server.json(ADVISORIES, advisory("< 2.0"));
            manager = managerWithAdvisories(server);

            assertTrue(only(run(settings(CHUNKY), RunOptions.all(true, "t"))).alerts().isEmpty());
            assertTrue(only(run(settings("github:\n  advisories: false\n",
                    "plugins:\n  Chunky:\n    source: custom\n    repo: Owner/Chunky\n"), RunOptions.all(true, "t"))).alerts().isEmpty());
            assertTrue(server.requests().isEmpty());
        }
    }

    @Test
    void advisoryErrorAddsANoteAndTheUpdateContinues() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));
        try (FakeHttpServer server = FakeHttpServer.start()) {
            server.route(ADVISORIES, req -> FakeHttpServer.Reply.json(401, "{\"message\":\"Bad credentials\"}"));
            manager = managerWithAdvisories(server);

            PluginResult r = only(run(settings("plugins:\n  Chunky:\n    source: custom\n    repo: Owner/Chunky\n")));

            assertEquals(PluginResult.Status.UPDATED, r.status());
            assertTrue(r.alerts().isEmpty());
            assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("Security advisories could not be checked")), r.notes().toString());
        }
    }

    // --- listener and state ------------------------------------------------------------------

    @Test
    void listenerGetsStartResultsAndFinish() throws IOException {
        install("Chunky", "1.0");
        install("Vault", "1.7");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));
        source.answer("Vault", published("Vault", "1.7", Freshness.UNKNOWN));

        UpdateReport report = run(settings("plugins:\n  Vault:\n    source: custom\n  Chunky:\n    source: custom\n"),
                RunOptions.all(false, "schedule"));

        assertEquals(List.of("schedule|false|2"), listener.started);
        assertEquals(Set.of("Vault", "Chunky"), listener.results.stream().map(PluginResult::plugin).collect(Collectors.toSet()));
        assertEquals(2, listener.results.size());
        assertEquals(List.of(report), listener.finished);
        assertEquals(List.of("Vault", "Chunky"), report.results().stream().map(PluginResult::plugin).toList(), "config order");
        assertEquals(Map.of(PluginResult.Status.UPDATED, 1, PluginResult.Status.UP_TO_DATE, 1), report.counts());
        assertFalse(report.cancelled());
        assertFalse(report.finished().isBefore(report.started()));
    }

    @Test
    void stateFileRecordsTheRun() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));
        run(settings(CHUNKY), RunOptions.all(false, "command:Steve"));

        StateStore reloaded = new StateStore(dir.resolve("data").resolve("state.json"), null);

        StateStore.PluginStats stats = reloaded.stats("Chunky").orElseThrow();
        assertEquals(1, stats.checks());
        assertEquals(1, stats.updates());
        assertEquals("2.0", stats.stagedVersion());
        assertEquals("2.0", stats.lastSeenVersion());
        assertEquals("UPDATED", stats.lastResult());
        StateStore.LastRun last = reloaded.lastRun().orElseThrow();
        assertEquals("command:Steve", last.trigger());
        assertFalse(last.dryRun());
        assertEquals(Map.of("UPDATED", 1), last.counts());
    }

    @Test
    void dryRunIsRecordedWithoutStaging() throws IOException {
        install("Chunky", "1.0");
        source.answer("Chunky", published("Chunky", "2.0", Freshness.UNKNOWN));
        run(settings(CHUNKY), RunOptions.all(true, "test"));

        StateStore reloaded = new StateStore(dir.resolve("data").resolve("state.json"), null);
        assertTrue(reloaded.lastRun().orElseThrow().dryRun());
        assertEquals(Optional.empty(), reloaded.stagedVersion("Chunky"));
        assertEquals(0, reloaded.stats("Chunky").orElseThrow().updates());
        assertEquals("AVAILABLE", reloaded.stats("Chunky").orElseThrow().lastResult());
    }

    @Test
    void manyPluginsInParallel() throws IOException {
        StringBuilder yaml = new StringBuilder("global:\n  parallelism: 4\nplugins:\n");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String name = "Plugin" + i;
            names.add(name);
            install(name, "1.0");
            source.answer(name, published(name, "1." + (i + 1), Freshness.UNKNOWN));
            yaml.append("  ").append(name).append(":\n    source: custom\n");
        }

        UpdateReport report = run(settings(yaml.toString()));

        assertEquals(names, report.results().stream().map(PluginResult::plugin).toList());
        assertEquals(12, report.count(PluginResult.Status.UPDATED));
        assertEquals(12, updateFolderFiles().size());
        assertEquals(12, listener.results.size());
    }
}
