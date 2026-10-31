package com.hanafubuki.updater.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LocalFileSourceHandler utility methods.
 */
class LocalFileSourceHandlerTest {

    @Test
    void testValidateConfigValidDirectory() throws Exception {
        LocalFileSourceHandler handler = createMinimalHandler();
        
        Map<String, Object> config = Map.of(
            "path", "/tmp/plugins",
            "version", "1.0.0"
        );
        
        assertTrue(handler.validateConfig("TestPlugin", config));
    }

    @Test
    void testValidateConfigValidWithPattern() throws Exception {
        LocalFileSourceHandler handler = createMinimalHandler();
        
        Map<String, Object> config = Map.of(
            "path", "/tmp/plugins",
            "version", "1.0.0",
            "pattern", "MyPlugin-(\\d+\\.\\d+\\.\\d+)\\.jar"
        );
        
        assertTrue(handler.validateConfig("TestPlugin", config));
    }

    @Test
    void testValidateConfigInvalidPattern() throws Exception {
        LocalFileSourceHandler handler = createMinimalHandler();
        
        Map<String, Object> config = Map.of(
            "path", "/tmp/plugins",
            "version", "1.0.0",
            "pattern", "[invalid regex"
        );
        
        assertFalse(handler.validateConfig("TestPlugin", config));
    }

    @Test
    void testValidateConfigMissingPath() throws Exception {
        LocalFileSourceHandler handler = createMinimalHandler();
        
        Map<String, Object> config = Map.of(
            "version", "1.0.0"
        );
        
        assertFalse(handler.validateConfig("TestPlugin", config));
    }

    @Test
    void testGetSourceName() throws Exception {
        LocalFileSourceHandler handler = createMinimalHandler();
        assertEquals("local", handler.getSourceName());
    }

    @Test
    void testExtractVersionFromFilenameDefault(@TempDir Path tempDir) throws Exception {
        LocalFileSourceHandler handler = createMinimalHandler();
        Method method = LocalFileSourceHandler.class.getDeclaredMethod("extractVersionFromFilename", String.class, String.class);
        method.setAccessible(true);

        // Standard format: plugin-1.2.3.jar
        assertEquals("1.2.3", method.invoke(handler, "MyPlugin-1.2.3.jar", null));
        
        // With v prefix: plugin_v1.2.3.jar
        assertEquals("1.2.3", method.invoke(handler, "MyPlugin_v1.2.3.jar", null));
        
        // Pre-release: plugin-1.2.3-beta.jar
        assertEquals("1.2.3-beta", method.invoke(handler, "MyPlugin-1.2.3-beta.jar", null));
        
        // Snapshot: plugin-1.2.3-SNAPSHOT.jar
        assertEquals("1.2.3-SNAPSHOT", method.invoke(handler, "MyPlugin-1.2.3-SNAPSHOT.jar", null));
        
        // Two-part version: plugin-1.21.jar
        assertEquals("1.21", method.invoke(handler, "MyPlugin-1.21.jar", null));
        
        // No match
        assertNull(method.invoke(handler, "MyPlugin.jar", null));
    }

    @Test
    void testExtractVersionFromFilenameCustomPattern(@TempDir Path tempDir) throws Exception {
        LocalFileSourceHandler handler = createMinimalHandler();
        Method method = LocalFileSourceHandler.class.getDeclaredMethod("extractVersionFromFilename", String.class, String.class);
        method.setAccessible(true);

        // Custom pattern with capture group
        String pattern = "custom-(\\d+\\.\\d+\\.\\d+)\\.jar";
        assertEquals("1.2.3", method.invoke(handler, "custom-1.2.3.jar", pattern));
        
        // Custom pattern without capture group (uses full match)
        String patternNoGroup = "custom-\\d+\\.\\d+\\.\\d+\\.jar";
        assertEquals("custom-1.2.3.jar", method.invoke(handler, "custom-1.2.3.jar", patternNoGroup));
    }

    private LocalFileSourceHandler createMinimalHandler() {
        return new LocalFileSourceHandler(
            java.util.logging.Logger.getLogger("test"),
            "update",
            5
        );
    }
}