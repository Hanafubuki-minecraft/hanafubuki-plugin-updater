package com.hanafubuki.updater;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.hanafubuki.updater.command.UpdateCommand;
import com.hanafubuki.updater.config.ConfigManager;
import com.hanafubuki.updater.config.UpdaterSettings;
import com.hanafubuki.updater.console.Messages;
import com.hanafubuki.updater.console.ResultReporter;
import com.hanafubuki.updater.discovery.PluginDiscovery;
import com.hanafubuki.updater.discovery.PluginRegistry;
import com.hanafubuki.updater.gui.MenuListener;
import com.hanafubuki.updater.health.HealthServer;
import com.hanafubuki.updater.http.HttpCache;
import com.hanafubuki.updater.http.HttpService;
import com.hanafubuki.updater.http.RetryPolicy;
import com.hanafubuki.updater.manager.BackupManager;
import com.hanafubuki.updater.manager.Installer;
import com.hanafubuki.updater.manager.Metrics;
import com.hanafubuki.updater.manager.PluginResult;
import com.hanafubuki.updater.manager.RunOptions;
import com.hanafubuki.updater.manager.StateStore;
import com.hanafubuki.updater.manager.UpdateListener;
import com.hanafubuki.updater.manager.UpdateManager;
import com.hanafubuki.updater.manager.UpdateReport;
import com.hanafubuki.updater.notify.NotificationService;
import com.hanafubuki.updater.source.CustomHttpSource;
import com.hanafubuki.updater.source.GitHubAdvisories;
import com.hanafubuki.updater.source.GitHubSource;
import com.hanafubuki.updater.source.HangarSource;
import com.hanafubuki.updater.source.LocalSource;
import com.hanafubuki.updater.source.ModrinthSource;
import com.hanafubuki.updater.source.UpdateSource;

/**
 * Hanafubuki Plugin Updater: keeps the server's plugins up to date from Modrinth, GitHub,
 * Hangar, SpigotMC, local folders and custom endpoints.
 *
 * <p>All network and file work runs off the main thread. Updates are downloaded into the
 * Bukkit update folder and applied by Paper on the next restart; running jars are never touched.
 */
public final class PluginUpdater extends JavaPlugin {

    private ConfigManager config;
    private volatile Messages messages;
    private HttpService http;
    private StateStore state;
    private BackupManager backups;
    private volatile UpdateManager manager;
    private volatile NotificationService notifications;
    private volatile ModrinthSource modrinth;
    private HealthServer health;
    private UpdateScheduler scheduler;
    private ExecutorService background;
    /** Plugin detection has its own thread so slow lookups never delay backups, rollback or unstage. */
    private ExecutorService detector;
    private UpdateCommand command;
    private ResultReporter reporter;
    private PluginRegistry registry;
    private volatile UpdateReport lastReport;
    private final AtomicBoolean detecting = new AtomicBoolean();
    /** A detection was asked for while one was running; run once more afterwards (config may have changed). */
    private final AtomicBoolean detectAgain = new AtomicBoolean();
    /** config.yml settings merged with confirmed plugins.json entries, rebuilt when either changes. */
    private final Object effectiveLock = new Object();
    private UpdaterSettings effective;
    private UpdaterSettings effectiveBase;
    private long effectiveRevision = -1;

    @Override
    public void onEnable() {
        config = new ConfigManager(this);
        config.load();
        messages = new Messages(config.settings().style());

        background = com.hanafubuki.updater.util.Threads.lazySingle("HanafubukiUpdater-background", 30);
        detector = com.hanafubuki.updater.util.Threads.lazySingle("HanafubukiUpdater-detect", 30);
        detecting.set(false);
        detectAgain.set(false);
        http = new HttpService(HttpService.newClient(Duration.ofSeconds(10)),
                "ByteMe6/hanafubuki-plugin-updater/" + getPluginMeta().getVersion(),
                RetryPolicy.DEFAULT, Thread::sleep, HttpCache.inMemory(512), getLogger());
        state = new StateStore(getDataFolder().toPath().resolve("state.json"), getLogger());
        registry = new PluginRegistry(getDataFolder().toPath().resolve("plugins.json"), getLogger());
        backups = new BackupManager(getDataFolder().toPath().resolve("backups"));
        reporter = new ResultReporter(this, config, () -> messages);
        scheduler = new UpdateScheduler(this);

        buildServices();

        PluginCommand command = getCommand("updateplugins");
        if (command == null) {
            getLogger().severe("Command 'updateplugins' is missing from plugin.yml; disabling");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.command = new UpdateCommand(this);
        command.setExecutor(this.command);
        command.setTabCompleter(this.command);
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);

        startHealthServer();
        // After a restart Paper has applied the staged jars; forget them so nobody is told to restart again.
        Bukkit.getScheduler().runTask(this, this::reconcileStaged);
        scheduler.start(config.settings().schedule());
        if (config.settings().general().autoDetect()) {
            // Wait one tick so every plugin has finished enabling.
            Bukkit.getScheduler().runTask(this, () -> detect(null, false));
        }
        getLogger().info("Tracking " + settings().plugins().size() + " plugins (" + config.settings().plugins().size()
                + " from config.yml); schedule: " + config.settings().schedule().describe());
    }

    @Override
    public void onDisable() {
        if (scheduler != null) {
            scheduler.stop();
        }
        if (manager != null) {
            manager.shutdown(5000);
        }
        stopHealthServer();
        if (notifications != null) {
            notifications.shutdown();
        }
        if (detector != null) {
            detector.shutdownNow();
        }
        if (http != null) {
            http.shutdown();
        }
        if (background != null) {
            background.shutdownNow();
            try {
                background.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Clears staged-update records whose file is no longer in the update folder. Takes the plugin
     * snapshot on the main thread; the file checks and the state write run in the background.
     */
    private void reconcileStaged() {
        Map<String, com.hanafubuki.updater.source.InstalledPlugin> installed =
                new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        installed.putAll(ServerPlugins.snapshot());
        Path updateFolder = ServerPlugins.updateFolder();
        runBackground(() -> {
            List<String> applied = state.reconcileStaged(name -> {
                com.hanafubuki.updater.source.InstalledPlugin p = installed.get(name);
                return p != null && java.nio.file.Files.exists(Installer.stagedPath(p, updateFolder));
            });
            if (!applied.isEmpty()) {
                getLogger().info("Applied by the restart: " + String.join(", ", applied));
            }
        });
    }

    /** (Re)creates sources and the update manager from the current settings. */
    private void buildServices() {
        UpdaterSettings s = config.settings();
        ModrinthSource modrinthSource = new ModrinthSource(http, s.modrinth());
        GitHubSource github = new GitHubSource(http, s.github());
        Map<String, UpdateSource> sources = new LinkedHashMap<>();
        sources.put("modrinth", modrinthSource);
        sources.put("github", github);
        sources.put("hangar", new HangarSource(http, s.hangar()));
        sources.put("spigotmc", new com.hanafubuki.updater.source.SpigotSource(http, s.spigotmc()));
        sources.put("local", new LocalSource());
        sources.put("custom", new CustomHttpSource(http, s.custom()));
        GitHubAdvisories advisories = new GitHubAdvisories(http.withRetry(s.github().source().retry()),
                GitHubSource.DEFAULT_BASE_URL, githubHeaders(s), s.github().source().apiTimeout());

        NotificationService oldNotifications = notifications;
        NotificationService newNotifications = new NotificationService(s.notifications(), http.withRetry(RetryPolicy.DEFAULT), getLogger());
        UpdateListener listener = new UpdateListener() {
            @Override
            public void onRunStarted(String trigger, boolean dryRun, int plugins) {
                reporter.onRunStarted(trigger, dryRun, plugins);
            }

            @Override
            public void onPluginResult(PluginResult result) {
                reporter.onPluginResult(result);
            }

            @Override
            public void onRunFinished(UpdateReport report) {
                lastReport = report;
                reporter.onRunFinished(report);
                newNotifications.onRunFinished(report);
            }
        };
        manager = new UpdateManager(sources, new Installer(http), advisories, backups, state, listener, getLogger(),
                Clock.systemUTC(), manager == null ? new Metrics() : manager.metrics());
        modrinth = modrinthSource;
        notifications = newNotifications;
        if (oldNotifications != null) {
            oldNotifications.shutdown(0);
        }
    }

    private static Map<String, String> githubHeaders(UpdaterSettings s) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/vnd.github+json");
        h.put("X-GitHub-Api-Version", "2022-11-28");
        if (s.github().token() != null && !s.github().token().isBlank()) {
            h.put("Authorization", "Bearer " + s.github().token());
        }
        return h;
    }

    private void startHealthServer() {
        UpdaterSettings.Health h = config.settings().health();
        if (!h.enabled()) {
            return;
        }
        try {
            health = HealthServer.start(h.bindAddress(), h.port(), this::healthStatus,
                    () -> manager.metrics().prometheus(settings().plugins().size()));
            getLogger().info("Health endpoint listening on http://" + h.bindAddress() + ":" + h.port() + "/health");
        } catch (IOException | RuntimeException e) {
            getLogger().severe("Could not start the health endpoint on " + h.bindAddress() + ":" + h.port() + ": " + e.getMessage());
        }
    }

    private void stopHealthServer() {
        if (health != null) {
            health.stop();
            health = null;
        }
    }

    private Map<String, Object> healthStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("version", getPluginMeta().getVersion());
        status.put("running", manager.isRunning());
        status.put("plugins_tracked", settings().plugins().size());
        status.put("plugins_pending", registry.pendingNames().size());
        status.put("staged_updates", state.allStaged().size());
        state.lastRun().ifPresent(r -> {
            Map<String, Object> last = new LinkedHashMap<>();
            last.put("trigger", r.trigger());
            last.put("dry_run", r.dryRun());
            last.put("started", r.started() == null ? null : r.started().toString());
            last.put("finished", r.finished() == null ? null : r.finished().toString());
            last.put("cancelled", r.cancelled());
            last.put("counts", r.counts());
            status.put("last_run", last);
        });
        return status;
    }

    // --- operations used by commands, GUI and scheduler ---------------------------------------

    /**
     * Starts a run. Must be called on the main thread (reads the plugin list).
     *
     * @param requester gets the summary when the run ends; may be null
     * @return false if a run is already active
     */
    public boolean startRun(RunOptions options, CommandSender requester) {
        Optional<UpdateManager.RunHandle> handle = manager.start(settings(), ServerPlugins.snapshot(),
                ServerPlugins.updateFolder(), ServerPlugins.minecraftVersion(), options);
        if (handle.isEmpty()) {
            return false;
        }
        handle.get().result().whenComplete((report, error) -> runSync(() -> {
            if (requester == null || requester == Bukkit.getConsoleSender()) {
                return;
            }
            if (requester instanceof Player p && !p.isOnline()) {
                return;
            }
            if (error != null) {
                messages.send(requester, "<error>The update run failed: <message>", Messages.text("message", error.getMessage()));
            } else {
                requester.sendMessage(messages.prefix().append(reporter.summary(report)));
                for (PluginResult r : report.results()) {
                    if (r.status() != PluginResult.Status.UP_TO_DATE) {
                        requester.sendMessage(reporter.line(r));
                    }
                }
            }
        }));
        return true;
    }

    /**
     * Reloads config.yml and rebuilds services. Refused while a run is active.
     *
     * @return problems found in the config (empty list = clean); empty optional if refused
     */
    public Optional<List<String>> reload() {
        if (manager.isRunning()) {
            return Optional.empty();
        }
        boolean ok = config.load();
        messages = new Messages(config.settings().style());
        buildServices();
        stopHealthServer();
        startHealthServer();
        scheduler.start(config.settings().schedule());
        if (ok && config.settings().general().autoDetect()) {
            // Plugins removed from config.yml become candidates again; new entries settle pending ones.
            detect(null, false);
        }
        List<String> problems = config.problems();
        if (!ok) {
            return Optional.of(List.of("config.yml is not valid YAML; the previous settings stay active"));
        }
        return Optional.of(problems);
    }

    /**
     * Looks for installed plugins that are neither in config.yml nor in modes.exclude_list and
     * records them in plugins.json as pending (or unidentified). Nothing found is updated until an
     * op confirms it. Must be called on the main thread; lookups run in the background.
     *
     * @param requester gets a full report (manual /updateplugins discover); null for automatic runs
     * @param force     look up unidentified plugins again
     * @return false if a detection is already running
     */
    public boolean detect(CommandSender requester, boolean force) {
        if (!detecting.compareAndSet(false, true)) {
            detectAgain.set(true);
            return false;
        }
        UpdaterSettings s = config.settings();
        List<PluginDiscovery.Candidate> installed = ServerPlugins.candidates(getName());
        java.util.Set<String> skip = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        skip.addAll(s.configuredNames());
        skip.addAll(s.modes().excludeList());
        ModrinthSource lookup = modrinth;
        try {
            detector.execute(() -> {
                PluginRegistry.Changes changes = null;
                Throwable failure = null;
                try {
                    changes = registry.detect(installed, skip, force, new PluginDiscovery(lookup::projectIdOf),
                            java.time.Instant.now());
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    detecting.set(false);
                }
                PluginRegistry.Changes done = changes;
                Throwable error = failure;
                if (error != null && !(error instanceof java.io.InterruptedIOException)) {
                    getLogger().warning("Plugin detection failed: " + error);
                }
                runSync(() -> {
                    if (done != null) {
                        reporter.onDetection(done, registry, requester);
                    } else if (requester != null) {
                        messages.send(requester, "<error>Plugin detection failed: <e>", Messages.text("e", String.valueOf(error)));
                    }
                    if (detectAgain.getAndSet(false) && config.settings().general().autoDetect()) {
                        detect(null, false);
                    }
                });
                if (error instanceof Error e) {
                    throw e;
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            detecting.set(false);
            return false;
        }
        return true;
    }

    /** Runs a task on the main thread, if the plugin is still enabled. */
    public void runSync(Runnable task) {
        if (!isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(this, task);
        }
    }

    /** Runs blocking work (file I/O, network) off the main thread. */
    public void runBackground(Runnable task) {
        background.execute(task);
    }

    /**
     * Settings with every tracked plugin: config.yml entries plus plugins confirmed in plugins.json
     * (config.yml wins on the same name).
     */
    public UpdaterSettings settings() {
        UpdaterSettings base = config.settings();
        synchronized (effectiveLock) {
            long revision = registry.revision();
            if (effective == null || base != effectiveBase || revision != effectiveRevision) {
                effective = base.withDetected(registry.confirmed());
                effectiveBase = base;
                effectiveRevision = revision;
            }
            return effective;
        }
    }

    /** Settings from config.yml alone. */
    public UpdaterSettings configSettings() {
        return config.settings();
    }

    /** True if config.yml has an entry for this plugin, valid or not (case-insensitive). */
    public boolean inConfig(String plugin) {
        return config.settings().inConfig(plugin);
    }

    public UpdateCommand commands() {
        return command;
    }

    public PluginRegistry registry() {
        return registry;
    }

    public List<String> configProblems() {
        return config.problems();
    }

    public Messages messages() {
        return messages;
    }

    public UpdateManager manager() {
        return manager;
    }

    public StateStore state() {
        return state;
    }

    public BackupManager backups() {
        return backups;
    }

    public NotificationService notifications() {
        return notifications;
    }

    public UpdateScheduler scheduler() {
        return scheduler;
    }

    public Optional<UpdateReport> lastReport() {
        return Optional.ofNullable(lastReport);
    }

    public ResultReporter reporter() {
        return reporter;
    }

    public Path updateFolder() {
        return ServerPlugins.updateFolder();
    }
}
