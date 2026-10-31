package com.hanafubuki.updater.manager;

import java.util.Set;
import java.util.TreeSet;

/**
 * Options for one update run.
 *
 * @param dryRun  check only, download nothing
 * @param only    when not null, only these plugins (case-insensitive) are processed; include_only
 *                is ignored for them, exclude_list still applies
 * @param trigger who started the run, for logs and reports
 */
public record RunOptions(boolean dryRun, Set<String> only, String trigger) {

    public RunOptions {
        if (only != null) {
            TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            set.addAll(only);
            only = java.util.Collections.unmodifiableSet(set);
        }
        if (trigger == null || trigger.isBlank()) {
            trigger = "unknown";
        }
    }

    public static RunOptions all(boolean dryRun, String trigger) {
        return new RunOptions(dryRun, null, trigger);
    }
}
