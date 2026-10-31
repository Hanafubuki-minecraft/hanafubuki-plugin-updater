package com.hanafubuki.updater.manager;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hanafubuki.updater.source.InstalledPlugin;
import com.hanafubuki.updater.testing.TestJars;

class BackupManagerTest {

    @TempDir
    Path dir;

    private Path plugins;
    private Path updateFolder;
    private BackupManager backups;

    @BeforeEach
    void setUp() {
        plugins = dir.resolve("plugins");
        updateFolder = plugins.resolve("update");
        backups = new BackupManager(dir.resolve("backups"));
    }

    /** Puts the given version in place as the installed jar (same file name every time, like on a real server). */
    private InstalledPlugin install(String version) throws IOException {
        Path jar = TestJars.write(plugins, "Chunky.jar", TestJars.plugin("Chunky", version));
        return new InstalledPlugin("Chunky", version, jar);
    }

    /** Backups get millisecond timestamps; wait so each one is distinct and the order is defined. */
    private static void tick() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void backupCopiesTheInstalledJar() throws IOException {
        InstalledPlugin p = install("1.4.10");

        BackupManager.Backup b = backups.backup(p, 3).orElseThrow();

        assertEquals("Chunky", b.plugin());
        assertEquals("1.4.10", b.version());
        assertEquals(dir.resolve("backups").resolve("Chunky"), b.file().getParent());
        assertTrue(b.file().getFileName().toString().matches("1\\.4\\.10__\\d+\\.jar"), b.file().toString());
        assertArrayEquals(Files.readAllBytes(p.jar()), Files.readAllBytes(b.file()));
        assertEquals(Files.size(p.jar()), b.size());
        assertEquals(List.of("Chunky"), backups.pluginsWithBackups());
    }

    @Test
    void identicalBackupIsNotRepeated() throws IOException {
        InstalledPlugin p = install("1.4.10");
        backups.backup(p, 3);
        tick();

        assertEquals(Optional.empty(), backups.backup(p, 3));
        assertEquals(1, backups.list("Chunky").size());
    }

    @Test
    void oldBackupsArePrunedToKeep() throws IOException {
        for (String v : List.of("1.0", "1.1", "1.2", "1.3")) {
            backups.backup(install(v), 2);
            tick();
        }

        List<BackupManager.Backup> list = backups.list("Chunky");
        assertEquals(List.of("1.3", "1.2"), list.stream().map(BackupManager.Backup::version).toList());
        try (var files = Files.list(dir.resolve("backups").resolve("Chunky"))) {
            assertEquals(2, files.count(), "pruned files are deleted, no temporary files remain");
        }
    }

    @Test
    void listIsNewestFirstAndIgnoresForeignFiles() throws IOException {
        for (String v : List.of("1.0", "1.1", "1.2")) {
            backups.backup(install(v), 10);
            tick();
        }
        Path pluginDir = dir.resolve("backups").resolve("Chunky");
        Files.writeString(pluginDir.resolve("notes.txt"), "x");
        Files.writeString(pluginDir.resolve("random.jar"), "x");
        Files.writeString(pluginDir.resolve("1.0__notanumber.jar"), "x");

        List<BackupManager.Backup> list = backups.list("Chunky");

        assertEquals(List.of("1.2", "1.1", "1.0"), list.stream().map(BackupManager.Backup::version).toList());
        assertTrue(list.get(0).created().isAfter(list.get(1).created()));
        assertEquals(List.of(), backups.list("NoSuchPlugin"));
    }

    @Test
    void restoreNewestStagesItUnderTheInstalledJarName() throws IOException {
        backups.backup(install("1.0"), 5);
        tick();
        backups.backup(install("1.1"), 5);
        tick();
        InstalledPlugin current = install("1.2");
        byte[] newest = Files.readAllBytes(backups.list("Chunky").get(0).file());

        BackupManager.Backup restored = backups.restore(null, current, updateFolder);

        assertEquals("1.1", restored.version());
        Path staged = updateFolder.resolve("Chunky.jar");
        assertArrayEquals(newest, Files.readAllBytes(staged));
        try (var files = Files.list(updateFolder)) {
            assertEquals(1, files.count(), "no temporary file is left in the update folder");
        }
        assertArrayEquals(TestJars.plugin("Chunky", "1.2"), Files.readAllBytes(current.jar()),
                "the running jar is never touched");
    }

    @Test
    void restoreSpecificVersion() throws IOException {
        backups.backup(install("1.0"), 5);
        tick();
        backups.backup(install("1.1"), 5);
        tick();
        InstalledPlugin current = install("1.2");

        BackupManager.Backup restored = backups.restore("1.0", current, updateFolder);

        assertEquals("1.0", restored.version());
        assertArrayEquals(TestJars.plugin("Chunky", "1.0"), Files.readAllBytes(updateFolder.resolve("Chunky.jar")));
    }

    @Test
    void restoreOfUnknownVersionIsAnError() throws IOException {
        backups.backup(install("1.0"), 5);
        InstalledPlugin current = install("1.2");

        IOException e = assertThrows(IOException.class, () -> backups.restore("0.9", current, updateFolder));
        assertTrue(e.getMessage().contains("0.9"), e.getMessage());
        assertFalse(Files.exists(updateFolder.resolve("Chunky.jar")));
    }

    @Test
    void restoreWithoutBackupsIsAnError() throws IOException {
        InstalledPlugin current = install("1.2");

        IOException e = assertThrows(IOException.class, () -> backups.restore(null, current, updateFolder));
        assertTrue(e.getMessage().contains("No backups"), e.getMessage());
    }

    @Test
    void restoreRefusesABackupOfAnotherPlugin() throws IOException {
        Path pluginDir = Files.createDirectories(dir.resolve("backups").resolve("Chunky"));
        TestJars.write(pluginDir, "1.0__1700000000000.jar", TestJars.plugin("NotChunky", "1.0"));
        InstalledPlugin current = install("1.2");

        IOException e = assertThrows(IOException.class, () -> backups.restore("1.0", current, updateFolder));
        assertTrue(e.getMessage().contains("not a valid Chunky jar"), e.getMessage());
        assertFalse(Files.exists(updateFolder.resolve("Chunky.jar")));
    }

    @Test
    void namesAreMadeFileSafe() {
        assertEquals("Chunky", BackupManager.safe("Chunky"));
        assertEquals("1.0.0-SNAPSHOT+12", BackupManager.safe("1.0.0-SNAPSHOT+12"));
        assertEquals("My_Plugin", BackupManager.safe("My Plugin"));
        assertEquals(".._.._etc", BackupManager.safe("../../etc"));
        assertEquals("a_b_c", BackupManager.safe("a/b\\c"));
        assertEquals("_", BackupManager.safe(""));
        assertEquals("_", BackupManager.safe("."));
        assertEquals("_", BackupManager.safe(".."));
    }

    @Test
    void hostileNamesStayInsideTheBackupFolder() throws IOException {
        Path jar = TestJars.write(plugins, "evil.jar", TestJars.plugin("../Evil", "../../1.0"));
        BackupManager.Backup b = backups.backup(new InstalledPlugin("../Evil", "../../1.0", jar), 3).orElseThrow();

        assertTrue(b.file().normalize().startsWith(dir.resolve("backups")), b.file().toString());
        assertEquals(dir.resolve("backups"), b.file().getParent().getParent());
    }

    @Test
    void pluginsWithBackupsIsEmptyWithoutFolder() throws IOException {
        assertEquals(List.of(), backups.pluginsWithBackups());
    }
}
