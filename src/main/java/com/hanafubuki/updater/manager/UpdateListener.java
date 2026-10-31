package com.hanafubuki.updater.manager;

/**
 * Receives progress of update runs. Methods are called from worker threads, never from the
 * server main thread; implementations must hand Bukkit work over to the scheduler.
 */
public interface UpdateListener {

    default void onRunStarted(String trigger, boolean dryRun, int plugins) {
    }

    /** Called once per plugin as soon as it is done. */
    default void onPluginResult(PluginResult result) {
    }

    /** Called after the whole run, also when it was cancelled. */
    default void onRunFinished(UpdateReport report) {
    }

    UpdateListener NONE = new UpdateListener() {
    };
}
