package com.hanafubuki.updater;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import com.hanafubuki.updater.manager.RunOptions;
import com.hanafubuki.updater.manager.Schedule;

/**
 * Starts automatic runs: once after startup (optional) and then by interval, daily or weekly.
 * Timing uses wall-clock times from {@link Schedule#next}, re-planned after every run, so a
 * long server lag does not shift later runs. Tasks run on the main thread only to take the
 * plugin snapshot; the run itself is asynchronous.
 */
public final class UpdateScheduler {

    private final PluginUpdater plugin;
    private BukkitTask startupTask;
    private BukkitTask nextTask;
    private volatile ZonedDateTime nextRun;
    private Schedule schedule = Schedule.DISABLED;
    private boolean startupDone;

    public UpdateScheduler(PluginUpdater plugin) {
        this.plugin = plugin;
    }

    /** Applies a schedule, cancelling anything planned before. */
    public void start(Schedule schedule) {
        stop();
        this.schedule = schedule;
        if (schedule.checkOnStartup() && !startupDone) {
            startupTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                startupDone = true;
                fire("startup");
            }, ticks(schedule.startupDelay()));
        }
        planNext();
    }

    public void stop() {
        if (startupTask != null) {
            startupTask.cancel();
            startupTask = null;
        }
        if (nextTask != null) {
            nextTask.cancel();
            nextTask = null;
        }
        nextRun = null;
    }

    /** Next planned automatic run, if any. */
    public Optional<ZonedDateTime> nextRun() {
        return Optional.ofNullable(nextRun);
    }

    public Schedule schedule() {
        return schedule;
    }

    private void planNext() {
        Optional<ZonedDateTime> next = schedule.next(ZonedDateTime.now(schedule.zone()));
        if (next.isEmpty()) {
            nextRun = null;
            return;
        }
        nextRun = next.get();
        Duration wait = Duration.between(ZonedDateTime.now(schedule.zone()), nextRun);
        nextTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            nextTask = null;
            fire("schedule");
            planNext();
        }, ticks(wait));
    }

    private void fire(String trigger) {
        if (plugin.manager().isRunning()) {
            plugin.getLogger().info("Skipping the " + trigger + " check: another run is still active");
            return;
        }
        plugin.startRun(RunOptions.all(schedule.dryRun(), trigger), null);
    }

    private static long ticks(Duration d) {
        return Math.max(1, d.toMillis() / 50);
    }
}
