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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import com.hanafubuki.updater.gui.MenuListener;
import com.hanafubuki.updater.health.HealthServer;
import com.hanafubuki.updater.http.HttpCache;
import com.hanafubuki.updater.http.HttpService;
import com.hanafubuki.updater.http.RetryPolicy;
import com.hanafubuki.updater.manager.BackupManager;
import com.hanafubuki.updater.manager.Installer;
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
    private ResultReporter reporter;
    private volatile UpdateReport lastReport;

    @Override
    public void onEnable() {
        config = new ConfigManager(this);
        config.load();
        messages = new Messages(config.settings().style());

        background = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HanafubukiUpdater-background");
            t.setDaemon(true);
            return t;
        });
        http = new HttpService(HttpService.newClient(Duration.ofSeconds(10)),
                "ByteMe6/hanafubuki-plugin-updater/" + getPluginMeta().getVersion(),
                RetryPolicy.DEFAULT, Thread::sleep, HttpCache.inMemory(512), getLogger());
        state = new StateStore(getDataFolder().toPath().resolve("state.json"), getLogger());
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
        UpdateCommand executor = new UpdateCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);

        startHealthServer();
        scheduler.start(config.settings().schedule());
        if (config.settings().general().discoverOnStartup()) {
            // Wait one tick so every plugin has finished enabling.
            Bukkit.getScheduler().runTask(this, () -> discover(Bukkit.getConsoleSender(), true, null));
        }
        getLogger().info("Tracking " + config.settings().plugins().size() + " plugins; schedule: "
                + config.settings().schedule().describe());
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
        if (background != null) {
            background.shutdownNow();
            try {
                background.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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
                Clock.systemUTC());
        modrinth = modrinthSource;
        notifications = newNotifications;
        if (oldNotifications != null) {
            oldNotifications.shutdown();
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
                    () -> manager.metrics().prometheus(config.settings().plugins().size()));
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
        status.put("plugins_tracked", config.settings().plugins().size());
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
        Optional<UpdateManager.RunHandle> handle = manager.start(config.settings(), ServerPlugins.snapshot(),
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
        List<String> problems = config.problems();
        if (!ok) {
            return Optional.of(List.of("config.yml is not valid YAML; the previous settings stay active"));
        }
        return Optional.of(problems);
    }

    /**
     * Finds installed plugins missing from config.yml. Network lookups run in the background.
     *
     * @param add   add usable suggestions to config.yml
     * @param only  restrict to one plugin name, or null
     */
    public void discover(CommandSender sender, boolean add, String only) {
        List<PluginDiscovery.Candidate> candidates = ServerPlugins.unconfigured(config.settings().plugins().keySet(), getName())
                .stream().filter(c -> only == null || c.name().equalsIgnoreCase(only)).toList();
        if (candidates.isEmpty()) {
            messages.send(sender, only == null
                    ? "<success>Every installed plugin is already in config.yml."
                    : "<warning><name> is not installed or already in config.yml.", Messages.text("name", only));
            return;
        }
        messages.send(sender, "<primary>Looking up <accent><count></accent> plugins…", Messages.text("count", candidates.size()));
        ModrinthSource lookup = modrinth;
        background.execute(() -> {
            List<PluginDiscovery.Suggestion> suggestions;
            try {
                suggestions = new PluginDiscovery(lookup::projectIdOf).discover(candidates);
            } catch (IOException e) {
                runSync(() -> messages.send(sender, "<error>Discovery was interrupted."));
                return;
            }
            int added = 0;
            if (add) {
                for (PluginDiscovery.Suggestion s : suggestions) {
                    if (config.addPlugin(s)) {
                        added++;
                    }
                }
            }
            int addedCount = added;
            runSync(() -> {
                for (PluginDiscovery.Suggestion s : suggestions) {
                    if (s.isUsable()) {
                        messages.sendLine(sender, "<success>  + <name> <muted><version></muted> → <accent><source></accent> <muted><options> (<reason>)",
                                Messages.text("name", s.name()), Messages.text("version", s.version()),
                                Messages.text("source", s.source()), Messages.text("options", s.options()),
                                Messages.text("reason", s.reason()));
                    } else {
                        messages.sendLine(sender, "<warning>  ? <name> <muted><version>: <reason> — add it by hand",
                                Messages.text("name", s.name()), Messages.text("version", s.version()),
                                Messages.text("reason", s.reason()));
                    }
                }
                if (add) {
                    messages.send(sender, "<primary>Added <accent><count></accent> plugins to config.yml.",
                            Messages.text("count", addedCount));
                    if (addedCount > 0) {
                        buildServices();
                    }
                } else {
                    messages.send(sender, "<muted>Run <accent>/updateplugins discover add</accent> to add the usable ones.");
                }
            });
        });
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

    public UpdaterSettings settings() {
        return config.settings();
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
