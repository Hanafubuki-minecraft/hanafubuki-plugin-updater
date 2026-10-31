package com.hanafubuki.updater.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ScheduleTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static Schedule schedule(Schedule.Mode mode, LocalTime time, DayOfWeek day, ZoneId zone) {
        return new Schedule(mode, Duration.ofMinutes(90), time, day, zone, false, Duration.ofSeconds(60), false);
    }

    private static Schedule daily(int hour, int minute, ZoneId zone) {
        return schedule(Schedule.Mode.DAILY, LocalTime.of(hour, minute), DayOfWeek.MONDAY, zone);
    }

    private static Schedule weekly(DayOfWeek day, int hour, int minute, ZoneId zone) {
        return schedule(Schedule.Mode.WEEKLY, LocalTime.of(hour, minute), day, zone);
    }

    /** 2026-10-28 is a Wednesday. */
    private static ZonedDateTime at(int day, int hour, int minute, ZoneId zone) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone);
    }

    @Test
    void offHasNoNextRun() {
        assertEquals(Optional.empty(), Schedule.DISABLED.next(ZonedDateTime.now()));
        assertEquals(Optional.empty(), schedule(Schedule.Mode.OFF, LocalTime.NOON, DayOfWeek.FRIDAY, UTC).next(ZonedDateTime.now()));
    }

    @Test
    void intervalAddsPeriodToNow() {
        Schedule s = schedule(Schedule.Mode.INTERVAL, LocalTime.NOON, DayOfWeek.MONDAY, UTC);
        ZonedDateTime now = ZonedDateTime.of(2026, 10, 28, 23, 45, 12, 500, BERLIN);
        assertEquals(now.plusMinutes(90), s.next(now).orElseThrow());
    }

    @Test
    void dailyBeforeTimeRunsToday() {
        ZonedDateTime next = daily(4, 30, UTC).next(at(28, 3, 59, UTC)).orElseThrow();
        assertEquals(at(28, 4, 30, UTC), next);
    }

    @Test
    void dailyAfterTimeRunsTomorrow() {
        assertEquals(at(29, 4, 30, UTC), daily(4, 30, UTC).next(at(28, 12, 0, UTC)).orElseThrow());
    }

    @Test
    void dailyExactlyAtTimeRunsTomorrow() {
        // "strictly after now": a run that is starting right now is not scheduled again
        assertEquals(at(29, 4, 30, UTC), daily(4, 30, UTC).next(at(28, 4, 30, UTC)).orElseThrow());
    }

    @Test
    void dailyDropsSecondsOfNow() {
        ZonedDateTime now = ZonedDateTime.of(2026, 10, 28, 4, 29, 59, 999_000_000, UTC);
        assertEquals(at(28, 4, 30, UTC), daily(4, 30, UTC).next(now).orElseThrow());
    }

    @Test
    void dailyAcrossMonthEnd() {
        assertEquals(ZonedDateTime.of(2026, 11, 1, 4, 0, 0, 0, UTC), daily(4, 0, UTC).next(at(31, 5, 0, UTC)).orElseThrow());
    }

    @Test
    void dailyUsesScheduleTimeZone() {
        // 2026-10-28 02:30 UTC is 03:30 in Berlin (UTC+1 in winter), before 04:00 Berlin time
        ZonedDateTime next = daily(4, 0, BERLIN).next(at(28, 2, 30, UTC)).orElseThrow();
        assertEquals(at(28, 4, 0, BERLIN), next);
        assertEquals(BERLIN, next.getZone());
        assertEquals(at(28, 3, 0, UTC).toInstant(), next.toInstant());

        // 03:30 UTC is 04:30 in Berlin: today's run has passed
        assertEquals(at(29, 4, 0, BERLIN), daily(4, 0, BERLIN).next(at(28, 3, 30, UTC)).orElseThrow());
    }

    @Test
    void dailyAcrossTimeZoneDateLine() {
        // 2026-10-28 03:00 UTC is still 2026-10-27 23:00 in New York (EDT, UTC-4)
        ZonedDateTime next = daily(23, 30, NEW_YORK).next(at(28, 3, 0, UTC)).orElseThrow();
        assertEquals(ZonedDateTime.of(2026, 10, 27, 23, 30, 0, 0, NEW_YORK), next);
    }

    @Test
    void dailyOnDaylightSavingChange() {
        // Berlin falls back from 03:00 CEST to 02:00 CET on 2026-10-25; 02:30 happens twice that night.
        ZonedDateTime beforeChange = ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, BERLIN);
        ZonedDateTime next = daily(2, 30, BERLIN).next(beforeChange).orElseThrow();
        assertEquals(25, next.getDayOfMonth());
        assertEquals(LocalTime.of(2, 30), next.toLocalTime());

        // Spring forward in New York: 2026-03-08 02:30 does not exist and moves to 03:30.
        ZonedDateTime spring = daily(2, 30, NEW_YORK).next(ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, NEW_YORK)).orElseThrow();
        assertEquals(8, spring.getDayOfMonth());
        assertEquals(LocalTime.of(3, 30), spring.toLocalTime());
    }

    @Test
    void weeklySameDayBeforeTimeRunsToday() {
        // Wednesday 03:00, schedule Wednesday 04:00
        assertEquals(at(28, 4, 0, UTC), weekly(DayOfWeek.WEDNESDAY, 4, 0, UTC).next(at(28, 3, 0, UTC)).orElseThrow());
    }

    @Test
    void weeklySameDayAfterTimeRunsNextWeek() {
        assertEquals(ZonedDateTime.of(2026, 11, 4, 4, 0, 0, 0, UTC),
                weekly(DayOfWeek.WEDNESDAY, 4, 0, UTC).next(at(28, 4, 0, UTC)).orElseThrow());
        assertEquals(ZonedDateTime.of(2026, 11, 4, 4, 0, 0, 0, UTC),
                weekly(DayOfWeek.WEDNESDAY, 4, 0, UTC).next(at(28, 22, 0, UTC)).orElseThrow());
    }

    @Test
    void weeklyOtherDayLaterThisWeek() {
        ZonedDateTime next = weekly(DayOfWeek.FRIDAY, 6, 15, UTC).next(at(28, 23, 0, UTC)).orElseThrow();
        assertEquals(at(30, 6, 15, UTC), next);
        assertEquals(DayOfWeek.FRIDAY, next.getDayOfWeek());
    }

    @Test
    void weeklyOtherDayEarlierInWeekWrapsAround() {
        // Wednesday -> next Monday
        ZonedDateTime next = weekly(DayOfWeek.MONDAY, 4, 0, UTC).next(at(28, 1, 0, UTC)).orElseThrow();
        assertEquals(ZonedDateTime.of(2026, 11, 2, 4, 0, 0, 0, UTC), next);
    }

    @Test
    void weeklyUsesScheduleTimeZoneForTheWeekday() {
        // Tuesday 23:30 UTC is already Wednesday 00:30 in Berlin
        ZonedDateTime next = weekly(DayOfWeek.WEDNESDAY, 4, 0, BERLIN).next(at(27, 23, 30, UTC)).orElseThrow();
        assertEquals(at(28, 4, 0, BERLIN), next);
    }

    @Test
    void nextIsAlwaysStrictlyAfterNow() {
        ZonedDateTime now = at(28, 10, 0, BERLIN);
        for (Schedule.Mode mode : Schedule.Mode.values()) {
            for (DayOfWeek day : DayOfWeek.values()) {
                schedule(mode, LocalTime.of(10, 0), day, BERLIN).next(now).ifPresent(next -> {
                    assertTrue(next.isAfter(now), mode + " " + day + " -> " + next);
                    assertTrue(!next.isAfter(now.plusWeeks(1)), mode + " " + day + " -> " + next);
                });
            }
        }
    }

    @Test
    void describe() {
        assertEquals("off", Schedule.DISABLED.describe());
        assertEquals("every 90 min", schedule(Schedule.Mode.INTERVAL, LocalTime.NOON, DayOfWeek.MONDAY, UTC).describe());
        assertEquals("daily at 04:30 Europe/Berlin", daily(4, 30, BERLIN).describe());
        assertEquals("weekly on sunday at 23:00 UTC", weekly(DayOfWeek.SUNDAY, 23, 0, UTC).describe());
        Schedule dry = new Schedule(Schedule.Mode.DAILY, Duration.ofHours(1), LocalTime.of(4, 0), DayOfWeek.MONDAY, UTC,
                false, Duration.ZERO, true);
        assertEquals("daily at 04:00 UTC (check only)", dry.describe());
    }

    @Test
    void describeDoesNotDependOnDefaultLocale() {
        Locale before = Locale.getDefault();
        try {
            // Turkish lower-cases "I" to a dotless "ı"
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("weekly on friday at 04:00 UTC", weekly(DayOfWeek.FRIDAY, 4, 0, UTC).describe());
        } finally {
            Locale.setDefault(before);
        }
    }
}
