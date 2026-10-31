package com.hanafubuki.updater.source;

import com.hanafubuki.updater.util.JarUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ModrinthSourceHandler and related utilities.
 */
class ModrinthSourceHandlerTest {

    @Test
    void testGetPluginNameFromJar(@TempDir Path tempDir) throws IOException {
        // Create a test JAR with plugin.yml
        Path jarPath = tempDir.resolve("test-plugin.jar");
        createTestJar(jarPath, "TestPlugin", "1.0.0");

        String name = JarUtils.getPluginNameFromJar(jarPath);
        assertEquals("TestPlugin", name);
    }

    @Test
    void testGetPluginNameFromJarMissingPluginYml(@TempDir Path tempDir) throws IOException {
        // Create a JAR without plugin.yml
        Path jarPath = tempDir.resolve("no-plugin-yml.jar");
        createTestJar(jarPath, null, null);

        String name = JarUtils.getPluginNameFromJar(jarPath);
        assertNull(name);
    }

    @Test
    void testGetPluginNameFromJarMissingNameField(@TempDir Path tempDir) throws IOException {
        // Create a JAR with plugin.yml but no name field
        Path jarPath = tempDir.resolve("no-name.jar");
        createTestJarWithCustomPluginYml(jarPath, "version: 1.0.0\nmain: com.test.Test");

        String name = JarUtils.getPluginNameFromJar(jarPath);
        assertNull(name);
    }

    @Test
    void testGetPluginNameFromJarNonExistentFile(@TempDir Path tempDir) {
        Path jarPath = tempDir.resolve("nonexistent.jar");
        // File doesn't exist - method should handle gracefully
        String name = JarUtils.getPluginNameFromJar(jarPath);
        assertNull(name);
    }

    @Test
    void testGetPluginNameFromJarInvalidJar(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("invalid.jar");
        Files.writeString(jarPath, "not a jar file");

        // Should handle invalid JAR gracefully and return null
        String name = JarUtils.getPluginNameFromJar(jarPath);
        assertNull(name);
    }

    @Test
    void testGetPluginNameFromJarWithSpecialChars(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("special-plugin.jar");
        createTestJar(jarPath, "My-Awesome_Plugin", "2.0.0");

        String name = JarUtils.getPluginNameFromJar(jarPath);
        assertEquals("My-Awesome_Plugin", name);
    }

    private void createTestJar(Path jarPath, String pluginName, String version) throws IOException {
        if (pluginName == null) {
            // Create JAR without plugin.yml
            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath), new Manifest())) {
                jos.putNextEntry(new ZipEntry("com/test/Test.class"));
                jos.write("dummy".getBytes());
                jos.closeEntry();
            }
        } else {
            String pluginYmlContent = "name: " + pluginName + "\nversion: " + version + "\nmain: com.test.Test";
            createTestJarWithCustomPluginYml(jarPath, pluginYmlContent);
        }
    }

    private void createTestJarWithCustomPluginYml(Path jarPath, String pluginYmlContent) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath), new Manifest())) {
            // Add plugin.yml
            jos.putNextEntry(new ZipEntry("plugin.yml"));
            jos.write(pluginYmlContent.getBytes());
            jos.closeEntry();

            // Add a dummy class
            jos.putNextEntry(new ZipEntry("com/test/Test.class"));
            jos.write("dummy".getBytes());
            jos.closeEntry();
        }
    }
}