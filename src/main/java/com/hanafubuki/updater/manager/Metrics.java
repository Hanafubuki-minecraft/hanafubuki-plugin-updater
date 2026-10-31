package com.hanafubuki.updater.manager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters since the plugin was enabled.
 */
public final class Metrics {

    final AtomicLong runs = new AtomicLong();
    final AtomicLong checks = new AtomicLong();
    final AtomicLong updatesFound = new AtomicLong();
    final AtomicLong downloadsSuccess = new AtomicLong();
    final AtomicLong downloadsFailed = new AtomicLong();
    final AtomicLong errors = new AtomicLong();
    final AtomicLong securityAlerts = new AtomicLong();
    final AtomicLong lastRunEpochMs = new AtomicLong();

    /** Counter name to value, in a stable order. */
    public Map<String, Long> snapshot() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("runs_total", runs.get());
        m.put("checks_total", checks.get());
        m.put("updates_found_total", updatesFound.get());
        m.put("downloads_success_total", downloadsSuccess.get());
        m.put("downloads_failed_total", downloadsFailed.get());
        m.put("errors_total", errors.get());
        m.put("security_alerts_total", securityAlerts.get());
        m.put("last_run_timestamp_ms", lastRunEpochMs.get());
        return m;
    }

    /** Prometheus text format. */
    public String prometheus(int pluginsTracked) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : snapshot().entrySet()) {
            String name = "hanafubuki_updater_" + e.getKey();
            sb.append("# TYPE ").append(name).append(e.getKey().endsWith("_total") ? " counter" : " gauge").append('\n');
            sb.append(name).append(' ').append(e.getValue()).append('\n');
        }
        sb.append("# TYPE hanafubuki_updater_plugins_tracked gauge\n");
        sb.append("hanafubuki_updater_plugins_tracked ").append(pluginsTracked).append('\n');
        return sb.toString();
    }
}
