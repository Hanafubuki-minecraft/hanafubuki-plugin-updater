package com.hanafubuki.updater.manager;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Result of one update run.
 *
 * @param trigger   who started it ("command:Steve", "schedule", "startup", ...)
 * @param dryRun    true if nothing was downloaded
 * @param started   start time
 * @param finished  end time
 * @param cancelled true if the run was stopped early
 * @param results   one entry per checked plugin, in config order
 */
public record UpdateReport(String trigger, boolean dryRun, Instant started, Instant finished, boolean cancelled,
                           List<PluginResult> results) {

    public UpdateReport {
        results = List.copyOf(results);
    }

    public Map<PluginResult.Status, Integer> counts() {
        Map<PluginResult.Status, Integer> counts = new EnumMap<>(PluginResult.Status.class);
        for (PluginResult r : results) {
            counts.merge(r.status(), 1, Integer::sum);
        }
        return counts;
    }

    public int count(PluginResult.Status status) {
        return counts().getOrDefault(status, 0);
    }

    public Duration duration() {
        return Duration.between(started, finished);
    }

    public List<PluginResult> updates() {
        return results.stream().filter(PluginResult::isUpdate).toList();
    }

    public List<PluginResult> failures() {
        return results.stream().filter(r -> r.status() == PluginResult.Status.FAILED
                || r.status() == PluginResult.Status.BLOCKED).toList();
    }

    public List<PluginResult> withAlerts() {
        return results.stream().filter(r -> !r.alerts().isEmpty()).toList();
    }
}
