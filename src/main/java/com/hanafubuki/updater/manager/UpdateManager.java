package com.hanafubuki.updater.manager;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.hanafubuki.updater.config.PluginEntry;
import com.hanafubuki.updater.config.UpdaterSettings;
import com.hanafubuki.updater.source.GitHubAdvisories;
import com.hanafubuki.updater.source.GitHubSource;
import com.hanafubuki.updater.source.InstalledPlugin;
import com.hanafubuki.updater.source.UpdateCandidate;
import com.hanafubuki.updater.source.UpdateRequest;
import com.hanafubuki.updater.source.UpdateSource;
import com.hanafubuki.updater.util.VersionUtils;

/**
 * Runs update checks.
 *
 * <ul>
 *   <li>Only one run at a time; {@link #start} returns empty while a run is active.</li>
 *   <li>Dry-run is an option of the run, not shared state, so runs never affect each other.</li>
 *   <li>Plugins are checked in parallel on a pool sized by {@code global.parallelism}.</li>
 *   <li>{@link #cancel()} interrupts all network calls of the active run.</li>
 *   <li>Nothing here touches Bukkit; callers pass a snapshot of the installed plugins.</li>
 * </ul>
 */
public final class UpdateManager {

    private final Map<String, UpdateSource> sources;
    private final Installer installer;
    private final GitHubAdvisories advisories;
    private final BackupManager backups;
    private final StateStore state;
    private final UpdateListener listener;
    private final Logger logger;
    private final Clock clock;
    private final Metrics metrics;
    private final AtomicReference<ActiveRun> active = new AtomicReference<>();

    /** Handle of a started run. */
    public record RunHandle(CompletableFuture<UpdateReport> result) {
    }

    private static final class ActiveRun {
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile ExecutorService workers;
        volatile Thread coordinator;
    }

    public UpdateManager(Map<String, UpdateSource> sources, Installer installer, GitHubAdvisories advisories,
                         BackupManager backups, StateStore state, UpdateListener listener, Logger logger, Clock clock) {
        this(sources, installer, advisories, backups, state, listener, logger, clock, new Metrics());
    }

    /** Same, but continues counting in existing metrics (used when config.yml is reloaded). */
    public UpdateManager(Map<String, UpdateSource> sources, Installer installer, GitHubAdvisories advisories,
                         BackupManager backups, StateStore state, UpdateListener listener, Logger logger, Clock clock,
                         Metrics metrics) {
        this.metrics = metrics;
        this.sources = Map.copyOf(sources);
        this.installer = installer;
        this.advisories = advisories;
        this.backups = backups;
        this.state = state;
        this.listener = listener == null ? UpdateListener.NONE : listener;
        this.logger = logger;
        this.clock = clock;
    }

    public Metrics metrics() {
        return metrics;
    }

    public StateStore state() {
        return state;
    }

    public boolean isRunning() {
        return active.get() != null;
    }

    /**
     * Requests cancellation of the active run.
     *
     * @return false if no run was active
     */
    public boolean cancel() {
        ActiveRun run = active.get();
        if (run == null) {
            return false;
        }
        run.cancelled.set(true);
        ExecutorService workers = run.workers;
        if (workers != null) {
            workers.shutdownNow();
        }
        return true;
    }

    /** Cancels the active run and waits for it to stop (used on plugin disable). */
    public void shutdown(long timeoutMs) {
        ActiveRun run = active.get();
        if (run == null) {
            return;
        }
        cancel();
        Thread t = run.coordinator;
        if (t != null) {
            try {
                t.join(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Starts a run in the background.
     *
     * @param installed     snapshot of loaded plugins, keyed by name
     * @param updateFolder  Bukkit update folder
     * @param mcVersion     server Minecraft version
     * @return the run, or empty if another run is active
     */
    public Optional<RunHandle> start(UpdaterSettings settings, Map<String, InstalledPlugin> installed, Path updateFolder,
                                     String mcVersion, RunOptions options) {
        ActiveRun run = new ActiveRun();
        if (!active.compareAndSet(null, run)) {
            return Optional.empty();
        }
        CompletableFuture<UpdateReport> result = new CompletableFuture<>();
        Thread coordinator = new Thread(() -> {
            try {
                result.complete(execute(run, settings, caseInsensitive(installed), updateFolder, mcVersion, options));
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Update run failed", t);
                result.completeExceptionally(t);
            } finally {
                ExecutorService w = run.workers;
                if (w != null) {
                    w.shutdownNow();
                }
                active.compareAndSet(run, null);
            }
        }, "HanafubukiUpdater-run");
        coordinator.setDaemon(true);
        run.coordinator = coordinator;
        coordinator.start();
        return Optional.of(new RunHandle(result));
    }

    private UpdateReport execute(ActiveRun run, UpdaterSettings settings, Map<String, InstalledPlugin> installed,
                                 Path updateFolder, String mcVersion, RunOptions options) {
        Instant started = clock.instant();
        metrics.runs.incrementAndGet();

        List<PluginResult> preResults = new ArrayList<>();
        List<PluginEntry> selected = select(settings, options, preResults);
        listener.onRunStarted(options.trigger(), options.dryRun(), selected.size());

        run.workers = Executors.newFixedThreadPool(Math.max(1, settings.general().parallelism()), workerThreads());
        List<Future<PluginResult>> futures = new ArrayList<>();
        for (PluginEntry entry : selected) {
            if (run.cancelled.get()) {
                break;
            }
            try {
                futures.add(run.workers.submit(() -> {
                    PluginResult r = checkSafely(settings, entry, installed, updateFolder, mcVersion, options.dryRun(), run);
                    if (r.status() != PluginResult.Status.CANCELLED) {
                        state.recordResult(r, clock.instant());
                    }
                    listener.onPluginResult(r);
                    return r;
                }));
            } catch (java.util.concurrent.RejectedExecutionException e) {
                break;
            }
        }

        List<PluginResult> results = new ArrayList<>(preResults);
        for (int i = 0; i < selected.size(); i++) {
            PluginEntry entry = selected.get(i);
            if (i >= futures.size()) {
                results.add(cancelled(entry, installed));
                continue;
            }
            try {
                results.add(futures.get(i).get());
            } catch (CancellationException | InterruptedException e) {
                results.add(cancelled(entry, installed));
            } catch (ExecutionException e) {
                results.add(new PluginResult(entry.name(), entry.source(), PluginResult.Status.FAILED, null, null,
                        String.valueOf(e.getCause()), List.of(), List.of(), null, 0));
            }
        }
        run.workers.shutdown();

        UpdateReport report = new UpdateReport(options.trigger(), options.dryRun(), started, clock.instant(),
                run.cancelled.get(), results);
        metrics.lastRunEpochMs.set(report.finished().toEpochMilli());
        state.recordRun(report);
        listener.onRunFinished(report);
        return report;
    }

    private static PluginResult cancelled(PluginEntry entry, Map<String, InstalledPlugin> installed) {
        InstalledPlugin p = installed.get(entry.name());
        return new PluginResult(entry.name(), entry.source(), PluginResult.Status.CANCELLED,
                p == null ? null : p.version(), null, "cancelled", List.of(), List.of(), null, 0);
    }

    /**
     * Applies include/exclude modes and the optional explicit plugin list.
     * Explicitly requested plugins that cannot run are reported in {@code preResults}.
     */
    static List<PluginEntry> select(UpdaterSettings settings, RunOptions options, List<PluginResult> preResults) {
        List<PluginEntry> out = new ArrayList<>();
        if (options.only() != null) {
            TreeMap<String, PluginEntry> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            byName.putAll(settings.plugins());
            for (String name : options.only()) {
                PluginEntry e = byName.get(name);
                if (e == null) {
                    preResults.add(new PluginResult(name, null, PluginResult.Status.SKIPPED, null, null,
                            "not configured in config.yml", List.of(), List.of(), null, 0));
                } else if (settings.modes().isExcluded(e.name())) {
                    preResults.add(new PluginResult(e.name(), e.source(), PluginResult.Status.SKIPPED, null, null,
                            "in modes.exclude_list", List.of(), List.of(), null, 0));
                } else {
                    out.add(e);
                }
            }
            return out;
        }
        for (PluginEntry e : settings.plugins().values()) {
            if (settings.modes().isExcluded(e.name())) {
                continue;
            }
            if (settings.modes().includeOnly() && !settings.modes().isIncluded(e.name())) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    private PluginResult checkSafely(UpdaterSettings settings, PluginEntry entry, Map<String, InstalledPlugin> installed,
                                     Path updateFolder, String mcVersion, boolean dryRun, ActiveRun run) {
        long t0 = System.nanoTime();
        InstalledPlugin plugin = installed.get(entry.name());
        Context ctx = new Context(entry, plugin, t0);
        try {
            metrics.checks.incrementAndGet();
            return check(settings, ctx, installed, updateFolder, mcVersion, dryRun);
        } catch (InterruptedIOException | ClosedByInterruptException e) {
            return ctx.result(PluginResult.Status.CANCELLED, null, "cancelled");
        } catch (BlockedException e) {
            return ctx.result(PluginResult.Status.BLOCKED, e.candidate, e.getMessage());
        } catch (IOException | RuntimeException e) {
            if (run.cancelled.get() || Thread.currentThread().isInterrupted()) {
                return ctx.result(PluginResult.Status.CANCELLED, null, "cancelled");
            }
            metrics.errors.incrementAndGet();
            if (ctx.downloading) {
                metrics.downloadsFailed.incrementAndGet();
            }
            if (e instanceof RuntimeException) {
                logger.log(Level.WARNING, "Unexpected error while checking " + entry.name(), e);
            }
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ctx.result(PluginResult.Status.FAILED, ctx.candidateVersion, message);
        }
    }

    /** Mutable per-plugin bookkeeping while a check runs. */
    private static final class Context {
        final PluginEntry entry;
        final InstalledPlugin plugin;
        final long t0;
        final List<String> notes = new ArrayList<>();
        List<GitHubAdvisories.Alert> alerts = List.of();
        String candidateVersion;
        String pageUrl;
        boolean downloading;

        Context(PluginEntry entry, InstalledPlugin plugin, long t0) {
            this.entry = entry;
            this.plugin = plugin;
            this.t0 = t0;
        }

        PluginResult result(PluginResult.Status status, String candidate, String message) {
            return new PluginResult(entry.name(), entry.source(), status, plugin == null ? null : plugin.version(),
                    candidate, message, notes, alerts, pageUrl, (System.nanoTime() - t0) / 1_000_000);
        }
    }

    private static final class BlockedException extends IOException {
        final String candidate;

        BlockedException(String message, String candidate) {
            super(message);
            this.candidate = candidate;
        }
    }

    private PluginResult check(UpdaterSettings settings, Context ctx, Map<String, InstalledPlugin> installed,
                               Path updateFolder, String mcVersion, boolean dryRun) throws IOException {
        PluginEntry entry = ctx.entry;
        InstalledPlugin plugin = ctx.plugin;
        if (plugin == null) {
            return ctx.result(PluginResult.Status.SKIPPED, null, "not installed on this server");
        }
        UpdateSource source = sources.get(entry.source());
        if (source == null) {
            return ctx.result(PluginResult.Status.FAILED, null, "unknown source '" + entry.source() + "'");
        }
        List<String> problems = source.validate(entry);
        if (!problems.isEmpty()) {
            return ctx.result(PluginResult.Status.FAILED, null, "invalid config: " + String.join("; ", problems));
        }

        Optional<String> held = state.heldVersion(plugin.name());
        if (held.isPresent()) {
            return ctx.result(PluginResult.Status.SKIPPED, null, "rolled back to " + held.get()
                    + "; automatic updates are paused until /updateplugins unstage " + plugin.name());
        }

        Path staged = Installer.stagedPath(plugin, updateFolder);
        Optional<String> stagedVersion = state.stagedVersion(plugin.name());
        if (stagedVersion.isPresent() && (!Files.exists(staged)
                || VersionUtils.compare(VersionUtils.normalize(plugin.version()), VersionUtils.normalize(stagedVersion.get())) == 0)) {
            // Applied by a restart, or removed by hand.
            state.clearStaged(plugin.name());
            stagedVersion = Optional.empty();
        }

        String min = entry.minVersion() != null ? entry.minVersion() : settings.general().minVersion();
        if (!VersionUtils.isUnbounded(min) && VersionUtils.compare(plugin.version(), min) < 0) {
            return ctx.result(PluginResult.Status.SKIPPED, null, "installed " + plugin.version() + " is below min_version " + min);
        }

        String repo = entry.string("repo");
        if (advisories != null && settings.githubAdvisories() && repo != null && GitHubSource.REPO.matcher(repo).matches()) {
            try {
                ctx.alerts = advisories.check(repo, plugin.version());
                if (!ctx.alerts.isEmpty()) {
                    metrics.securityAlerts.addAndGet(ctx.alerts.size());
                }
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                ctx.notes.add("Security advisories could not be checked: " + e.getMessage());
            }
        }

        Optional<UpdateCandidate> found = source.findLatest(new UpdateRequest(entry, plugin, mcVersion));
        if (found.isEmpty()) {
            return ctx.result(PluginResult.Status.NO_VERSION, null, "no compatible version for Minecraft " + mcVersion);
        }
        UpdateCandidate candidate = found.get();
        ctx.candidateVersion = candidate.version();
        ctx.pageUrl = candidate.pageUrl();

        boolean newer = switch (candidate.freshness()) {
            case NEWER -> true;
            case SAME -> false;
            case UNKNOWN -> VersionUtils.isNewer(candidate.version(), plugin.version());
        };
        if (!newer) {
            return ctx.result(PluginResult.Status.UP_TO_DATE, candidate.version(), null);
        }
        ctx.notes.addAll(candidate.notes());

        String max = entry.maxVersion() != null ? entry.maxVersion() : settings.general().maxVersion();
        if (!VersionUtils.isUnbounded(max) && VersionUtils.compare(candidate.version(), max) > 0) {
            return ctx.result(PluginResult.Status.SKIPPED, candidate.version(), candidate.version() + " is above max_version " + max);
        }
        if (Files.exists(staged) && isAlreadyStaged(staged, candidate, stagedVersion)) {
            return ctx.result(PluginResult.Status.ALREADY_STAGED, candidate.version(), "waiting for a restart");
        }
        metrics.updatesFound.incrementAndGet();
        if (dryRun) {
            return ctx.result(PluginResult.Status.AVAILABLE, candidate.version(), null);
        }

        if (settings.backups().enabled()) {
            try {
                backups.backup(plugin, settings.backups().keep());
            } catch (IOException e) {
                if (e instanceof InterruptedIOException) {
                    throw e;
                }
                throw new IOException("Backup of the current jar failed, update not installed: " + e.getMessage(), e);
            }
        }

        ctx.downloading = true;
        Installer.Verifier verifier = (descriptor, file) -> {
            if (settings.dependencies() == UpdaterSettings.DependencyMode.OFF) {
                return;
            }
            List<String> depProblems = DependencyChecker.check(descriptor, installed.keySet(), mcVersion);
            if (depProblems.isEmpty()) {
                return;
            }
            if (settings.dependencies() == UpdaterSettings.DependencyMode.BLOCK) {
                throw new BlockedException("Version " + candidate.version() + " " + String.join("; ", depProblems), candidate.version());
            }
            ctx.notes.addAll(depProblems);
        };
        installer.stage(plugin, candidate, updateFolder, settings.downloadTimeout(entry.source()),
                settings.general().maxDownloadBytes(), verifier);
        metrics.downloadsSuccess.incrementAndGet();
        return ctx.result(PluginResult.Status.UPDATED, candidate.version(), "applied on next restart");
    }

    private static boolean isAlreadyStaged(Path staged, UpdateCandidate candidate, Optional<String> stagedVersion) throws IOException {
        if (candidate.checksum() != null) {
            return candidate.checksum().matches(staged);
        }
        return stagedVersion.isPresent() && stagedVersion.get().equals(candidate.version());
    }

    private static Map<String, InstalledPlugin> caseInsensitive(Map<String, InstalledPlugin> installed) {
        TreeMap<String, InstalledPlugin> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        map.putAll(installed);
        return map;
    }

    private static ThreadFactory workerThreads() {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "HanafubukiUpdater-worker-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
