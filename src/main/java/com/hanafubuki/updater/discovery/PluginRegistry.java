package com.hanafubuki.updater.discovery;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.hanafubuki.updater.config.PluginEntry;
import com.hanafubuki.updater.config.SettingsParser;
import com.hanafubuki.updater.util.AtomicFiles;

/**
 * Plugins found automatically, stored in {@code plugins.json}. Only the updater writes this file;
 * the user decides through commands or the menu, and entries in config.yml always win.
 *
 * <p>A found plugin starts as {@link Status#PENDING} (a source was identified) or
 * {@link Status#UNIDENTIFIED} (no reliable source). Only {@link Status#CONFIRMED} plugins are
 * updated. {@link Status#REJECTED} plugins stay listed so they can be confirmed later.
 *
 * <p>Thread-safe. Network lookups never run while the lock is held: {@link #detect} asks which
 * plugins need a lookup, runs the lookups, then applies the results.
 */
public final class PluginRegistry {

    /** Decision state of a found plugin. */
    public enum Status {
        /** Source found, waiting for an op to confirm. Not updated. */
        PENDING,
        /** Confirmed by an op. Updated like a config.yml entry. */
        CONFIRMED,
        /** Rejected by an op. Not updated, can be confirmed later. */
        REJECTED,
        /** No reliable source found. Needs a config.yml entry. */
        UNIDENTIFIED;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One found plugin.
     *
     * @param source     source id, or null if unidentified
     * @param options    source options (project_id, repo, ...)
     * @param reason     how the source was found, or why not
     * @param version    installed version when it was last looked at
     * @param decidedBy  who confirmed or rejected it, may be null
     * @param retry      the last lookup failed for a temporary reason; the next detection tries again
     */
    public record Entry(String name, Status status, String source, Map<String, String> options, String reason,
                        String version, Instant discovered, Instant decided, String decidedBy, boolean retry) {

        public Entry {
            options = options == null ? Map.of() : Map.copyOf(options);
        }

        public boolean isUsable() {
            return source != null;
        }

        /** The entry as the update engine sees it. */
        public PluginEntry toPluginEntry() {
            return new PluginEntry(name, source, new LinkedHashMap<>(new TreeMap<>(options)));
        }
    }

    /**
     * What a detection changed.
     *
     * @param pending      plugins that now wait for confirmation (new, or identified on a retry)
     * @param unidentified new plugins without a source
     * @param removed      undecided plugins that were uninstalled or are now in config.yml
     */
    public record Changes(List<String> pending, List<String> unidentified, List<String> removed) {
        public boolean isEmpty() {
            return pending.isEmpty() && unidentified.isEmpty() && removed.isEmpty();
        }
    }

    /** Result of confirm or reject. */
    public enum Outcome {
        /** The status was changed. */
        CHANGED,
        /** The plugin already had this status. */
        UNCHANGED,
        /** The plugin is not in plugins.json. */
        NOT_FOUND,
        /** The plugin has no source and cannot be confirmed. */
        NO_SOURCE
    }

    /** Stored form of an entry. Field names are the JSON keys. */
    static final class Stored {
        String status;
        String source;
        Map<String, String> options;
        String reason;
        String version;
        String discovered_at;
        String decided_at;
        String decided_by;
        Boolean retry;
    }

    static final class Data {
        String _comment = "Written by the plugin updater. Do not edit: decide with /updateplugins pending, "
                + "confirm <plugin> or reject <plugin>, or add the plugin to config.yml (config.yml always wins).";
        int version = 1;
        Map<String, Stored> plugins = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    private final Path file;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final TreeMap<String, Entry> entries = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private long revision;

    public PluginRegistry(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
        load();
    }

    // --- persistence -----------------------------------------------------------------------

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Data data;
        try {
            data = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
        } catch (IOException | JsonParseException e) {
            warn("Could not read " + file.getFileName() + " (" + e.getMessage() + "); starting empty, "
                    + "the old file is kept as " + file.getFileName() + ".broken");
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // The broken file is overwritten on the next save.
            }
            return;
        }
        if (data == null || data.plugins == null) {
            return;
        }
        data.plugins.forEach((name, s) -> {
            if (name == null || name.isBlank() || s == null) {
                return;
            }
            Entry e = fromStored(name.trim(), s);
            if (e != null) {
                entries.put(e.name(), e);
            }
        });
    }

    private Entry fromStored(String name, Stored s) {
        Status status;
        try {
            status = Status.valueOf(String.valueOf(s.status).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warn(file.getFileName() + ": " + name + " has unknown status '" + s.status + "'; it is looked at again");
            return null;
        }
        String source = s.source == null ? null : s.source.trim().toLowerCase(Locale.ROOT);
        String reason = s.reason;
        if (source != null && !SettingsParser.SOURCES.contains(source)) {
            warn(file.getFileName() + ": " + name + " has unknown source '" + s.source + "'");
            source = null;
            reason = "unknown source '" + s.source + "' in plugins.json";
        }
        Map<String, String> options = new LinkedHashMap<>();
        if (s.options != null) {
            s.options.forEach((k, v) -> {
                if (k != null && v != null) {
                    options.put(k, v);
                }
            });
        }
        if (source == null && status != Status.REJECTED) {
            // Without a source nothing can be updated or confirmed.
            status = Status.UNIDENTIFIED;
        }
        return new Entry(name, status, source, source == null ? Map.of() : options, reason, s.version,
                parse(s.discovered_at), parse(s.decided_at), s.decided_by, Boolean.TRUE.equals(s.retry));
    }

    private void save() {
        revision++;
        Data data = new Data();
        for (Entry e : entries.values()) {
            Stored s = new Stored();
            s.status = e.status().key();
            s.source = e.source();
            s.options = e.options().isEmpty() ? null : new TreeMap<>(e.options());
            s.reason = e.reason();
            s.version = e.version();
            s.discovered_at = e.discovered() == null ? null : e.discovered().toString();
            s.decided_at = e.decided() == null ? null : e.decided().toString();
            s.decided_by = e.decidedBy();
            s.retry = e.retry() ? Boolean.TRUE : null;
            data.plugins.put(e.name(), s);
        }
        try {
            AtomicFiles.writeString(file, gson.toJson(data));
        } catch (IOException e) {
            warn("Could not save " + file.getFileName() + ": " + e.getMessage());
        }
    }

    // --- detection -------------------------------------------------------------------------

    /**
     * Finds new plugins and refreshes undecided ones.
     *
     * @param installed loaded plugins (the updater itself excluded)
     * @param skip      names handled elsewhere: config.yml entries and modes.exclude_list
     * @param force     look up unidentified plugins again even if nothing changed
     * @param discovery performs the lookups (network)
     */
    public Changes detect(List<PluginDiscovery.Candidate> installed, Collection<String> skip, boolean force,
                          PluginDiscovery discovery, Instant now) throws InterruptedIOException {
        List<PluginDiscovery.Candidate> lookups = toLookUp(installed, skip, force);
        List<PluginDiscovery.Suggestion> suggestions = lookups.isEmpty() ? List.of() : discovery.discover(lookups);
        return apply(installed, skip, suggestions, now);
    }

    /** Installed plugins that need a lookup: new ones, and unidentified ones on a retry. */
    public synchronized List<PluginDiscovery.Candidate> toLookUp(List<PluginDiscovery.Candidate> installed,
                                                                 Collection<String> skip, boolean force) {
        Set<String> skipped = caseInsensitive(skip);
        List<PluginDiscovery.Candidate> out = new ArrayList<>();
        for (PluginDiscovery.Candidate c : installed) {
            if (skipped.contains(c.name())) {
                continue;
            }
            Entry e = entries.get(c.name());
            if (e == null) {
                out.add(c);
            } else if (e.status() == Status.UNIDENTIFIED
                    && (force || e.retry() || !Objects.equals(e.version(), c.version()))) {
                out.add(c);
            }
        }
        return out;
    }

    /** Stores lookup results and forgets undecided plugins that are gone or now configured. */
    public synchronized Changes apply(List<PluginDiscovery.Candidate> installed, Collection<String> skip,
                                      List<PluginDiscovery.Suggestion> suggestions, Instant now) {
        Set<String> skipped = caseInsensitive(skip);
        List<String> pending = new ArrayList<>();
        List<String> unidentified = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        boolean changed = false;

        for (PluginDiscovery.Suggestion s : suggestions) {
            if (skipped.contains(s.name())) {
                continue;
            }
            Entry old = entries.get(s.name());
            if (old != null && old.status() != Status.UNIDENTIFIED) {
                // Decided (or identified) meanwhile; keep the decision.
                continue;
            }
            Map<String, String> options = new LinkedHashMap<>();
            s.options().forEach((k, v) -> {
                if (v != null) {
                    options.put(k, String.valueOf(v));
                }
            });
            Instant discovered = old == null ? now : old.discovered();
            Status status = s.isUsable() ? Status.PENDING : Status.UNIDENTIFIED;
            Entry fresh = new Entry(s.name(), status, s.source(), options, s.reason(), s.version(), discovered,
                    null, null, !s.isUsable() && s.lookupFailed());
            if (fresh.equals(old)) {
                continue;
            }
            entries.put(fresh.name(), fresh);
            changed = true;
            if (status == Status.PENDING) {
                pending.add(fresh.name());
            } else if (old == null) {
                unidentified.add(fresh.name());
            }
        }

        Set<String> present = caseInsensitive(installed.stream().map(PluginDiscovery.Candidate::name).toList());
        var it = entries.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            boolean undecided = e.status() == Status.PENDING || e.status() == Status.UNIDENTIFIED;
            if (undecided && (!present.contains(e.name()) || skipped.contains(e.name()))) {
                it.remove();
                removed.add(e.name());
                changed = true;
            }
        }
        if (changed) {
            save();
        }
        return new Changes(List.copyOf(pending), List.copyOf(unidentified), List.copyOf(removed));
    }

    // --- decisions -------------------------------------------------------------------------

    /** Confirms a pending or rejected plugin, so it is updated from now on. */
    public synchronized Outcome confirm(String name, String by, Instant now) {
        Entry e = entries.get(name);
        if (e == null) {
            return Outcome.NOT_FOUND;
        }
        if (!e.isUsable()) {
            return Outcome.NO_SOURCE;
        }
        if (e.status() == Status.CONFIRMED) {
            return Outcome.UNCHANGED;
        }
        put(e, Status.CONFIRMED, by, now);
        return Outcome.CHANGED;
    }

    /**
     * Confirms every pending plugin.
     *
     * @return names of the confirmed plugins
     */
    public synchronized List<String> confirmAllPending(String by, Instant now) {
        List<String> out = new ArrayList<>();
        for (Entry e : List.copyOf(entries.values())) {
            if (e.status() == Status.PENDING && e.isUsable()) {
                entries.put(e.name(), with(e, Status.CONFIRMED, by, now));
                out.add(e.name());
            }
        }
        if (!out.isEmpty()) {
            save();
        }
        return out;
    }

    /** Rejects a plugin: it is not updated and not reported as new again. */
    public synchronized Outcome reject(String name, String by, Instant now) {
        Entry e = entries.get(name);
        if (e == null) {
            return Outcome.NOT_FOUND;
        }
        if (e.status() == Status.REJECTED) {
            return Outcome.UNCHANGED;
        }
        put(e, Status.REJECTED, by, now);
        return Outcome.CHANGED;
    }

    private void put(Entry e, Status status, String by, Instant now) {
        entries.put(e.name(), with(e, status, by, now));
        save();
    }

    private static Entry with(Entry e, Status status, String by, Instant now) {
        return new Entry(e.name(), status, e.source(), e.options(), e.reason(), e.version(), e.discovered(), now, by,
                false);
    }

    // --- queries ---------------------------------------------------------------------------

    public synchronized Optional<Entry> get(String name) {
        return Optional.ofNullable(entries.get(name));
    }

    /** All entries sorted by name. */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public synchronized List<Entry> entries(Status status) {
        return entries.values().stream().filter(e -> e.status() == status).toList();
    }

    public synchronized List<String> pendingNames() {
        return entries.values().stream().filter(e -> e.status() == Status.PENDING).map(Entry::name).toList();
    }

    /** Confirmed plugins as update entries, sorted by name. */
    public synchronized Map<String, PluginEntry> confirmed() {
        Map<String, PluginEntry> out = new LinkedHashMap<>();
        for (Entry e : entries.values()) {
            if (e.status() == Status.CONFIRMED && e.isUsable()) {
                out.put(e.name(), e.toPluginEntry());
            }
        }
        return out;
    }

    /** Increases on every change; used to cache derived data. */
    public synchronized long revision() {
        return revision;
    }

    // --- helpers ---------------------------------------------------------------------------

    private static Set<String> caseInsensitive(Collection<String> names) {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (names != null) {
            names.stream().filter(Objects::nonNull).forEach(out::add);
        }
        return out;
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

    private void warn(String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }
}
