package com.hanafubuki.updater.manager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.hanafubuki.updater.source.InstalledPlugin;
import com.hanafubuki.updater.util.Checksum;
import com.hanafubuki.updater.util.PluginDescriptor;

/**
 * Keeps copies of replaced plugin jars so an update can be rolled back.
 *
 * <p>Layout: {@code <backupRoot>/<Plugin>/<version>__<epochMillis>.jar}. Before an update is
 * staged, the running jar is copied there (skipped if an identical copy exists). Only the newest
 * {@code keep} backups per plugin are kept. A rollback stages a backup in the update folder under
 * the installed jar's file name, so Paper swaps it in on the next restart like any update.
 */
public final class BackupManager {

    /**
     * One stored backup.
     *
     * @param plugin  plugin name
     * @param version plugin version inside the backup
     * @param created when the backup was made
     * @param file    path of the backup jar
     * @param size    size in bytes
     */
    public record Backup(String plugin, String version, Instant created, Path file, long size) {
    }

    private final Path root;

    public BackupManager(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /**
     * Copies the installed jar into the backup folder and prunes old copies.
     *
     * @return the backup, or empty if an identical backup already existed
     */
    public synchronized Optional<Backup> backup(InstalledPlugin installed, int keep) throws IOException {
        Path dir = pluginDir(installed.name());
        Files.createDirectories(dir);
        String hash = Checksum.hash(installed.jar(), Checksum.Algorithm.SHA256);
        for (Backup b : list(installed.name())) {
            if (Checksum.hash(b.file(), Checksum.Algorithm.SHA256).equals(hash)) {
                prune(installed.name(), keep);
                return Optional.empty();
            }
        }
        Instant now = Instant.now();
        String version = installed.version() == null ? "unknown" : installed.version();
        Path target = dir.resolve(safe(version) + "__" + now.toEpochMilli() + ".jar");
        Path tmp = dir.resolve(target.getFileName() + ".part");
        Files.copy(installed.jar(), tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.setLastModifiedTime(target, FileTime.from(now));
        prune(installed.name(), keep);
        return Optional.of(new Backup(installed.name(), version, now, target, Files.size(target)));
    }

    /** Backups of a plugin, newest first. */
    public synchronized List<Backup> list(String plugin) throws IOException {
        Path dir = pluginDir(plugin);
        List<Backup> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".jar")).toList()) {
                String name = p.getFileName().toString();
                int sep = name.lastIndexOf("__");
                if (sep <= 0) {
                    continue;
                }
                long millis;
                try {
                    millis = Long.parseLong(name.substring(sep + 2, name.length() - 4));
                } catch (NumberFormatException e) {
                    continue;
                }
                String version = PluginDescriptor.read(p).map(PluginDescriptor::version).orElse(name.substring(0, sep));
                out.add(new Backup(plugin, version, Instant.ofEpochMilli(millis), p, Files.size(p)));
            }
        }
        out.sort(Comparator.comparing(Backup::created).reversed());
        return out;
    }

    /** Names of plugins that have backups. */
    public synchronized List<String> pluginsWithBackups() throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory).map(d -> d.getFileName().toString()).sorted().toList();
        }
    }

    /** Deletes all but the newest {@code keep} backups. */
    public synchronized void prune(String plugin, int keep) throws IOException {
        List<Backup> backups = list(plugin);
        for (int i = keep; i < backups.size(); i++) {
            Files.deleteIfExists(backups.get(i).file());
        }
    }

    /**
     * Stages a backup for installation on next restart.
     *
     * @param version      version to restore, or null for the newest backup
     * @param installed    the plugin as currently installed (its jar name is used)
     * @param updateFolder Bukkit update folder
     * @return the restored backup
     */
    public synchronized Backup restore(String version, InstalledPlugin installed, Path updateFolder) throws IOException {
        List<Backup> backups = list(installed.name());
        Backup chosen = null;
        for (Backup b : backups) {
            if (version == null || version.equalsIgnoreCase(b.version())) {
                chosen = b;
                break;
            }
        }
        if (chosen == null) {
            throw new IOException(version == null
                    ? "No backups exist for " + installed.name()
                    : "No backup of " + installed.name() + " version " + version);
        }
        Optional<PluginDescriptor> d = PluginDescriptor.read(chosen.file());
        if (d.isEmpty() || !d.get().name().equalsIgnoreCase(installed.name())) {
            throw new IOException("Backup " + chosen.file().getFileName() + " is not a valid " + installed.name() + " jar");
        }
        Files.createDirectories(updateFolder);
        Path target = updateFolder.resolve(installed.jar().getFileName().toString());
        Path tmp = updateFolder.resolve("." + target.getFileName() + ".rollback.part");
        try {
            Files.copy(chosen.file(), tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return chosen;
    }

    /** Backup folder of a plugin; an existing folder whose name differs only in case is reused. */
    private Path pluginDir(String plugin) {
        String name = safe(plugin);
        if (Files.isDirectory(root)) {
            try (Stream<Path> dirs = Files.list(root)) {
                Optional<Path> existing = dirs.filter(Files::isDirectory)
                        .filter(d -> d.getFileName().toString().equalsIgnoreCase(name)).findFirst();
                if (existing.isPresent()) {
                    return existing.get();
                }
            } catch (IOException ignored) {
                // Fall back to the exact name.
            }
        }
        return root.resolve(name);
    }

    /** File-name safe version of a plugin name or version. */
    static String safe(String s) {
        String cleaned = s.replaceAll("[^A-Za-z0-9._+-]", "_");
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return "_";
        }
        return cleaned;
    }
}
