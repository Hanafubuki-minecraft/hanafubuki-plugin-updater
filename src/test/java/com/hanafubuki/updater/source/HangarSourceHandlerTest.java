package com.hanafubuki.updater.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HangarSourceHandler and related utilities.
 */
class HangarSourceHandlerTest {

    @Test
    void testVersionMatchesExact() throws Exception {
        HangarSourceHandler handler = createMinimalHandler("1.21.9");
        Method method = HangarSourceHandler.class.getDeclaredMethod("versionMatches", String.class);
        method.setAccessible(true);

        // Exact match
        assertTrue((Boolean) method.invoke(handler, "1.21.9"));
        
        // Pre-release versions like 1.21.9-R0.1 don't match exact 1.21.9
        assertFalse((Boolean) method.invoke(handler, "1.21.9-R0.1"));
    }

    @Test
    void testVersionMatchesRange() throws Exception {
        HangarSourceHandler handler = createMinimalHandler("1.21.9");
        Method method = HangarSourceHandler.class.getDeclaredMethod("versionMatches", String.class);
        method.setAccessible(true);

        // Range format: "1.21.4-1.21.11"
        assertTrue((Boolean) method.invoke(handler, "1.21.4-1.21.11"));
        assertTrue((Boolean) method.invoke(handler, "1.21.4-26.3"));
        
        // Out of range
        assertFalse((Boolean) method.invoke(handler, "1.20.0-1.20.6"));
        assertFalse((Boolean) method.invoke(handler, "1.22.0-1.22.5"));
        
        // No match for non-exact, non-range
        assertFalse((Boolean) method.invoke(handler, "1.21"));
        assertFalse((Boolean) method.invoke(handler, "1.20"));
    }

    @Test
    void testCompareVersions() throws Exception {
        HangarSourceHandler handler = createMinimalHandler("1.21.9");
        Method method = HangarSourceHandler.class.getDeclaredMethod("compareVersions", String.class, String.class);
        method.setAccessible(true);

        // v1 > v2
        assertTrue((Integer) method.invoke(handler, "1.21.9", "1.21.8") > 0);
        assertTrue((Integer) method.invoke(handler, "1.22.0", "1.21.9") > 0);
        assertTrue((Integer) method.invoke(handler, "2.0.0", "1.21.9") > 0);

        // v1 < v2
        assertTrue((Integer) method.invoke(handler, "1.21.8", "1.21.9") < 0);
        assertTrue((Integer) method.invoke(handler, "1.21.9", "1.22.0") < 0);

        // v1 == v2
        assertEquals(0, (Integer) method.invoke(handler, "1.21.9", "1.21.9"));
    }

    @Test
    void testValidateConfigValid() throws Exception {
        HangarSourceHandler handler = createMinimalHandler("1.21.9");
        
        Map<String, Object> config = Map.of(
            "project_slug", "EngineHub/WorldEdit",
            "version", "7.4.5"
        );
        
        assertTrue(handler.validateConfig("WorldEdit", config));
    }

    @Test
    void testValidateConfigMissingProjectSlug() throws Exception {
        HangarSourceHandler handler = createMinimalHandler("1.21.9");
        
        Map<String, Object> config = Map.of(
            "version", "7.4.5"
        );
        
        assertFalse(handler.validateConfig("WorldEdit", config));
    }

    @Test
    void testValidateConfigInvalidProjectSlugFormat() throws Exception {
        HangarSourceHandler handler = createMinimalHandler("1.21.9");
        
        Map<String, Object> config = Map.of(
            "project_slug", "InvalidFormat",
            "version", "7.4.5"
        );
        
        assertFalse(handler.validateConfig("WorldEdit", config));
    }

    @Test
    void testGetSourceName() throws Exception {
        HangarSourceHandler handler = createMinimalHandler("1.21.9");
        assertEquals("hangar", handler.getSourceName());
    }

    private HangarSourceHandler createMinimalHandler(String serverVersion) throws Exception {
        var constructor = HangarSourceHandler.class.getDeclaredConstructors()[1];
        constructor.setAccessible(true);
        return (HangarSourceHandler) constructor.newInstance(
            java.net.http.HttpClient.newHttpClient(),
            serverVersion,
            "1.0.0",
            java.util.logging.Logger.getLogger("test"),
            Executors.newSingleThreadExecutor(),
            10, 60, "update"
        );
    }
}