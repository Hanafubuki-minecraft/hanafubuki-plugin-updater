package com.hanafubuki.updater.manager;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Optional;

/**
 * When automatic checks run.
 *
 * @param mode            off, interval, daily or weekly
 * @param interval        period for interval mode
 * @param time            local time for daily/weekly mode
 * @param day             weekday for weekly mode
 * @param zone            time zone for daily/weekly mode
 * @param checkOnStartup  run once after the server started
 * @param startupDelay    delay before the startup check
 * @param dryRun          scheduled runs only report updates and do not download
 */
public record Schedule(Mode mode, Duration interval, LocalTime time, DayOfWeek day, ZoneId zone,
                       boolean checkOnStartup, Duration startupDelay, boolean dryRun) {

    public enum Mode {
        OFF, INTERVAL, DAILY, WEEKLY
    }

    public static final Schedule DISABLED = new Schedule(Mode.OFF, Duration.ofHours(6), LocalTime.of(4, 0),
            DayOfWeek.MONDAY, ZoneId.systemDefault(), false, Duration.ofSeconds(60), false);

    /**
     * Next run strictly after {@code now}, or empty when scheduling is off.
     */
    public Optional<ZonedDateTime> next(ZonedDateTime now) {
        ZonedDateTime local = now.withZoneSameInstant(zone);
        return switch (mode) {
            case OFF -> Optional.empty();
            case INTERVAL -> Optional.of(now.plus(interval));
            case DAILY -> {
                ZonedDateTime candidate = local.with(time).withSecond(0).withNano(0);
                if (!candidate.isAfter(local)) {
                    candidate = candidate.plusDays(1).with(time);
                }
                yield Optional.of(candidate);
            }
            case WEEKLY -> {
                ZonedDateTime candidate = local.with(TemporalAdjusters.nextOrSame(day)).with(time).withSecond(0).withNano(0);
                if (!candidate.isAfter(local)) {
                    candidate = candidate.plusWeeks(1).with(time);
                }
                yield Optional.of(candidate);
            }
        };
    }

    /** Human readable description for /updateplugins status. */
    public String describe() {
        return switch (mode) {
            case OFF -> "off";
            case INTERVAL -> "every " + interval.toMinutes() + " min";
            case DAILY -> "daily at " + time + " " + zone.getId();
            case WEEKLY -> "weekly on " + day.toString().toLowerCase(Locale.ROOT) + " at " + time + " " + zone.getId();
        } + (dryRun ? " (check only)" : "");
    }
}
