package com.hanafubuki.updater.manager;

import java.util.List;

import com.hanafubuki.updater.source.GitHubAdvisories;

/**
 * Outcome of checking one plugin.
 *
 * @param plugin           plugin name
 * @param source           source id
 * @param status           what happened
 * @param installedVersion version running now, may be null if not installed
 * @param candidateVersion newest version found, may be null
 * @param message          short explanation (error text, skip reason), may be null
 * @param notes            warnings, e.g. "no checksum published"
 * @param alerts           security advisories affecting the installed version
 * @param pageUrl          page of the new version, may be null
 * @param durationMs       time spent on this plugin
 */
public record PluginResult(String plugin, String source, Status status, String installedVersion, String candidateVersion,
                           String message, List<String> notes, List<GitHubAdvisories.Alert> alerts, String pageUrl,
                           long durationMs) {

    public PluginResult {
        notes = notes == null ? List.of() : List.copyOf(notes);
        alerts = alerts == null ? List.of() : List.copyOf(alerts);
    }

    /** Result states. */
    public enum Status {
        /** Downloaded, verified and staged in the update folder; applied on restart. */
        UPDATED,
        /** Dry run: an update exists but nothing was downloaded. */
        AVAILABLE,
        /** This version is already staged and waits for a restart. */
        ALREADY_STAGED,
        /** The installed version is the newest. */
        UP_TO_DATE,
        /** The source has no compatible version. */
        NO_VERSION,
        /** Not checked: not installed, outside version limits, excluded. */
        SKIPPED,
        /** An update exists but dependencies of the new version are not met. */
        BLOCKED,
        /** Error while checking or downloading. */
        FAILED,
        /** The run was cancelled before this plugin finished. */
        CANCELLED
    }

    public boolean isUpdate() {
        return status == Status.UPDATED || status == Status.AVAILABLE;
    }
}
