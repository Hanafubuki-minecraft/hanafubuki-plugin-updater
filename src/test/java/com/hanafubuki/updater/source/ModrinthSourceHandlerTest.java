package com.hanafubuki.updater.source;

import com.hanafubuki.updater.util.JarUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
        Path jarPath = tempDir.resolve("test-plugin.jar");
        createTestJar(jarPath, "TestPlugin", "1.0.0");

        String name = JarUtils.getPluginNameFromJar(jarPath);
        assertEquals("TestPlugin", name);
    }

    @Test
    void testGetPluginNameFromJarMissingPluginYml(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("no-plugin-yml.jar");
        createTestJar(jarPath, null, null);

        String name = JarUtils.getPluginNameFromJar(jarPath);
        assertNull(name);
    }

    @Test
    void testGetPluginNameFromJarMissingNameField(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("no-name.jar");
        createTestJarWithCustomPluginYml(jarPath, "version: 1.0.0\nmain: com.test.Test");

        String name = JarUtils.getPluginNameFromJar(jarPath);
        assertNull(name);
    }

    @Test
    void testGetPluginNameFromJarNonExistentFile(@TempDir Path tempDir) {
        Path jarPath = tempDir.resolve("nonexistent.jar");
        String name = JarUtils.getPluginNameFromJar(jarPath);
        assertNull(name);
    }

    @Test
    void testGetPluginNameFromJarInvalidJar(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("invalid.jar");
        Files.writeString(jarPath, "not a jar file");
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

    @ParameterizedTest
    @CsvSource({
        "1.21.9, %5B%22paper%22%5D, %5B%221.21.9%22%5D",
        "1.20.1, %5B%22paper%22%5D, %5B%221.20.1%22%5D",
        "1.19.4, %5B%22paper%22%5D, %5B%221.19.4%22%5D",
    })
    void testBuildVersionsUrlEncoding(String serverVersion, String expectedLoaders, String expectedGameVersions) throws Exception {
        ModrinthSourceHandler handler = createMinimalHandler(serverVersion);
        Method method = ModrinthSourceHandler.class.getDeclaredMethod("buildVersionsUrl", String.class);
        method.setAccessible(true);
        
        String url = (String) method.invoke(handler, "test-project-id");
        
        assertTrue(url.contains("loaders=" + expectedLoaders), "URL should contain encoded loaders: " + url);
        assertTrue(url.contains("game_versions=" + expectedGameVersions), "URL should contain encoded game_versions: " + url);
        assertFalse(url.contains("["), "URL should not contain unencoded square brackets: " + url);
        assertFalse(url.contains("]"), "URL should not contain unencoded square brackets: " + url);
    }

    @Test
    void testBuildVersionsUrlStructure() throws Exception {
        ModrinthSourceHandler handler = createMinimalHandler("1.21.9");
        Method method = ModrinthSourceHandler.class.getDeclaredMethod("buildVersionsUrl", String.class);
        method.setAccessible(true);
        
        String url = (String) method.invoke(handler, "P1OZGk5p");
        
        assertTrue(url.startsWith("https://api.modrinth.com/v2/project/P1OZGk5p/version?"));
        assertTrue(url.contains("loaders="));
        assertTrue(url.contains("game_versions="));
    }

    @Test
    void testUrlEncodingMatchesManualEncoding() {
        // Verify our manual encoding matches URLEncoder
        String loaders = URLEncoder.encode("[\"paper\"]", StandardCharsets.UTF_8);
        String gameVersions = URLEncoder.encode("[\"", StandardCharsets.UTF_8) 
                + URLEncoder.encode("1.21.9", StandardCharsets.UTF_8) 
                + URLEncoder.encode("\"]", StandardCharsets.UTF_8);
        
        assertEquals("%5B%22paper%22%5D", loaders);
        assertEquals("%5B%221.21.9%22%5D", gameVersions);
    }

    private void createTestJar(Path jarPath, String pluginName, String version) throws IOException {
        if (pluginName == null) {
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
            jos.putNextEntry(new ZipEntry("plugin.yml"));
            jos.write(pluginYmlContent.getBytes());
            jos.closeEntry();
            jos.putNextEntry(new ZipEntry("com/test/Test.class"));
            jos.write("dummy".getBytes());
            jos.closeEntry();
        }
    }

    private ModrinthSourceHandler createMinimalHandler(String serverVersion) throws Exception {
        var constructor = ModrinthSourceHandler.class.getDeclaredConstructors()[1];
        constructor.setAccessible(true);
        return (ModrinthSourceHandler) constructor.newInstance(
            java.net.http.HttpClient.newHttpClient(),
            serverVersion,
            "1.0.0",
            java.util.logging.Logger.getLogger("test"),
            java.util.concurrent.Executors.newSingleThreadExecutor(),
            10, 60, "update"
        );
    }
}