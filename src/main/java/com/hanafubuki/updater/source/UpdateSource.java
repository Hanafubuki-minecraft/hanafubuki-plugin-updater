package com.hanafubuki.updater.source;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import com.hanafubuki.updater.config.PluginEntry;

/**
 * A place plugin updates come from (Modrinth, GitHub, Hangar, ...).
 *
 * <p>Implementations must be thread-safe: several plugins are checked in parallel.
 * They do network I/O and must never be called on the server main thread.
 */
public interface UpdateSource {

    /** Source id as written in config.yml, e.g. "modrinth". */
    String id();

    /**
     * Checks a plugin entry for configuration mistakes.
     *
     * @return human readable problems; empty if the entry is usable
     */
    List<String> validate(PluginEntry entry);

    /**
     * Finds the newest compatible version for the plugin.
     *
     * @return the candidate, or empty if the source has no compatible version at all
     * @throws IOException on network or API errors, or when the source cannot identify the plugin
     */
    Optional<UpdateCandidate> findLatest(UpdateRequest request) throws IOException;
}
