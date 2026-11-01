package com.hanafubuki.updater;

/**
 * Permission nodes. All default to op (see plugin.yml) and can be granted or revoked one by one
 * with a permissions plugin such as LuckPerms, e.g.
 * {@code /lp group moderator permission set hanafubuki.updater.check true}.
 */
public final class Permissions {

    /** Use the /updateplugins command at all. */
    public static final String USE = "hanafubuki.updater.use";
    /** Check for updates without downloading (dry run). */
    public static final String CHECK = "hanafubuki.updater.check";
    /** Download and stage updates. */
    public static final String UPDATE = "hanafubuki.updater.update";
    /** Stop a running check. */
    public static final String STOP = "hanafubuki.updater.stop";
    /** See status, staged updates and schedule. */
    public static final String STATUS = "hanafubuki.updater.status";
    /** See per-plugin statistics. */
    public static final String STATS = "hanafubuki.updater.stats";
    /** See counters. */
    public static final String METRICS = "hanafubuki.updater.metrics";
    /** List backups, roll back, remove staged updates, hold/release plugins. */
    public static final String ROLLBACK = "hanafubuki.updater.rollback";
    /** Find installed plugins missing from config.yml and add them. */
    public static final String DISCOVER = "hanafubuki.updater.discover";
    /** Reload config.yml. */
    public static final String RELOAD = "hanafubuki.updater.reload";
    /** Send a test Discord/webhook notification. */
    public static final String NOTIFY_TEST = "hanafubuki.updater.notifytest";
    /** Open the inventory menu. */
    public static final String GUI = "hanafubuki.updater.gui";
    /** Receive in-game update and security messages. */
    public static final String NOTIFY = "hanafubuki.updater.notify";

    private Permissions() {
    }
}
