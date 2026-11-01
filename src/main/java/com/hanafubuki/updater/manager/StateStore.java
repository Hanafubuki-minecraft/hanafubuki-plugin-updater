package com.hanafubuki.updater.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * Persistent updater state in {@code state.json}: last run, and per plugin the last check,
 * last seen version, staged update and update statistics.
 *
 * <p>Thread-safe. Every change is written to disk at once through a temporary file and an
 * atomic rename, so a crash never leaves a half-written file.
 */
public final class StateStore {

    private static final int MAX_HISTORY = 50;

    /** Stored data of one plugin. Field names are the JSON keys. */
    static final class PluginState {
        String last_check;
        String last_seen_version;
        String last_result;
        String staged_version;
        String staged_at;
        String held_version;
        String held_at;
        long checks;
        long updates;
        long failures;
        List<String> update_history = new ArrayList<>();
    }

    /** Stored data of the last run. */
    static final class RunState {
        String trigger;
        boolean dry_run;
        String started;
        String finished;
        boolean cancelled;
        Map<String, Integer> counts = new TreeMap<>();
    }

    static final class Data {
        int version = 1;
        RunState last_run;
        Map<String, PluginState> plugins = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    /**
     * Statistics of one plugin for /updateplugins stats.
     *
     * @param averageUpdateInterval mean time between updates, null with fewer than two updates
     */
    public record PluginStats(String plugin, long checks, long updates, long failures, Instant lastCheck,
                              Instant lastUpdate, Duration averageUpdateInterval, String lastSeenVersion,
                              String stagedVersion, String lastResult) {
    }

    /** Summary of the last run. */
    public record LastRun(String trigger, boolean dryRun, Instant started, Instant finished, boolean cancelled,
                          Map<String, Integer> counts) {
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Logger logger;
    private Data data;

    public StateStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
        this.data = load();
    }

    private Data load() {
        if (!Files.isRegularFile(file)) {
            return new Data();
        }
        try {
            Data d = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            if (d == null) {
                return new Data();
            }
            TreeMap<String, PluginState> plugins = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (d.plugins != null) {
                d.plugins.forEach((k, v) -> {
                    if (v != null) {
                        if (v.update_history == null) {
                            v.update_history = new ArrayList<>();
                        }
                        plugins.put(k, v);
                    }
                });
            }
            d.plugins = plugins;
            return d;
        } catch (IOException | JsonParseException e) {
            if (logger != null) {
                logger.warning("Could not read " + file.getFileName() + " (" + e.getMessage() + "); starting with empty state");
            }
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // Keep going with an empty state; the broken file is overwritten on next save.
            }
            return new Data();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (logger != null) {
                logger.warning("Could not save " + file.getFileName() + ": " + e.getMessage());
            }
        }
    }

    private PluginState plugin(String name) {
        return data.plugins.computeIfAbsent(name, k -> new PluginState());
    }

    /** Records the outcome of a check. */
    public synchronized void recordResult(PluginResult result, Instant when) {
        PluginState s = plugin(result.plugin());
        s.last_check = when.toString();
        s.checks++;
        s.last_result = result.status().name();
        if (result.candidateVersion() != null) {
            s.last_seen_version = result.candidateVersion();
        }
        if (result.status() == PluginResult.Status.FAILED) {
            s.failures++;
        }
        if (result.status() == PluginResult.Status.UPDATED) {
            s.updates++;
            s.staged_version = result.candidateVersion();
            s.staged_at = when.toString();
            s.update_history.add(when.toString());
            while (s.update_history.size() > MAX_HISTORY) {
                s.update_history.remove(0);
            }
        }
        save();
    }

    public synchronized void recordRun(UpdateReport report) {
        RunState r = new RunState();
        r.trigger = report.trigger();
        r.dry_run = report.dryRun();
        r.started = report.started().toString();
        r.finished = report.finished().toString();
        r.cancelled = report.cancelled();
        report.counts().forEach((k, v) -> r.counts.put(k.name(), v));
        data.last_run = r;
        save();
    }

    /** Version waiting in the update folder for this plugin, if any. */
    public synchronized Optional<String> stagedVersion(String plugin) {
        PluginState s = data.plugins.get(plugin);
        return Optional.ofNullable(s == null ? null : s.staged_version);
    }

    public synchronized void setStaged(String plugin, String version, Instant when) {
        PluginState s = plugin(plugin);
        s.staged_version = version;
        s.staged_at = version == null ? null : when.toString();
        save();
    }

    public synchronized void clearStaged(String plugin) {
        PluginState s = data.plugins.get(plugin);
        if (s != null && s.staged_version != null) {
            s.staged_version = null;
            s.staged_at = null;
            save();
        }
    }

    /** All plugins with a staged update, name to version. */
    public synchronized Map<String, String> allStaged() {
        Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        data.plugins.forEach((k, v) -> {
            if (v.staged_version != null) {
                out.put(k, v.staged_version);
            }
        });
        return out;
    }

    /**
     * Pauses automatic updates of a plugin after a rollback. The staged update record is cleared
     * because the rollback file replaced it.
     */
    public synchronized void hold(String plugin, String version, Instant when) {
        PluginState s = plugin(plugin);
        s.held_version = version;
        s.held_at = when.toString();
        s.staged_version = null;
        s.staged_at = null;
        save();
    }

    /**
     * Resumes automatic updates.
     *
     * @return true if the plugin was held
     */
    public synchronized boolean release(String plugin) {
        PluginState s = data.plugins.get(plugin);
        if (s == null || s.held_version == null) {
            return false;
        }
        s.held_version = null;
        s.held_at = null;
        save();
        return true;
    }

    /** Version a plugin was rolled back to, while automatic updates are paused. */
    public synchronized Optional<String> heldVersion(String plugin) {
        PluginState s = data.plugins.get(plugin);
        return Optional.ofNullable(s == null ? null : s.held_version);
    }

    public synchronized Optional<LastRun> lastRun() {
        RunState r = data.last_run;
        if (r == null) {
            return Optional.empty();
        }
        return Optional.of(new LastRun(r.trigger, r.dry_run, parse(r.started), parse(r.finished), r.cancelled,
                Map.copyOf(r.counts)));
    }

    public synchronized Optional<PluginStats> stats(String plugin) {
        PluginState s = data.plugins.get(plugin);
        if (s == null) {
            return Optional.empty();
        }
        String name = data.plugins.keySet().stream().filter(k -> k.equalsIgnoreCase(plugin)).findFirst().orElse(plugin);
        return Optional.of(toStats(name, s));
    }

    public synchronized List<PluginStats> allStats() {
        List<PluginStats> out = new ArrayList<>();
        data.plugins.forEach((k, v) -> out.add(toStats(k, v)));
        return out;
    }

    private static PluginStats toStats(String name, PluginState s) {
        Duration average = null;
        List<Instant> history = s.update_history.stream().map(StateStore::parse).filter(java.util.Objects::nonNull).toList();
        if (history.size() >= 2) {
            Duration total = Duration.between(history.get(0), history.get(history.size() - 1));
            average = total.dividedBy(history.size() - 1);
        }
        Instant lastUpdate = history.isEmpty() ? null : history.get(history.size() - 1);
        return new PluginStats(name, s.checks, s.updates, s.failures, parse(s.last_check), lastUpdate, average,
                s.last_seen_version, s.staged_version, s.last_result);
    }

    private static Instant parse(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
