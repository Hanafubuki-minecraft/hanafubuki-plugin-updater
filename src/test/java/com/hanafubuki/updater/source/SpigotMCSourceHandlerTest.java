package com.hanafubuki.updater.source;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SpigotMCSourceHandler utility methods.
 */
class SpigotMCSourceHandlerTest {

    @Test
    void testValidateConfigWithResourceId() throws Exception {
        SpigotMCSourceHandler handler = createMinimalInstance("1.21.9");
        
        Map<String, Object> config = Map.of(
            "resource_id", "9089",
            "version", "2.20.1"
        );
        
        assertTrue(handler.validateConfig("EssentialsX", config));
    }

    @Test
    void testValidateConfigWithResourceName() throws Exception {
        SpigotMCSourceHandler handler = createMinimalInstance("1.21.9");
        
        Map<String, Object> config = Map.of(
            "resource_name", "Vault",
            "version", "1.7.3"
        );
        
        assertTrue(handler.validateConfig("Vault", config));
    }

    @Test
    void testValidateConfigMissingBoth() throws Exception {
        SpigotMCSourceHandler handler = createMinimalInstance("1.21.9");
        
        Map<String, Object> config = Map.of(
            "version", "1.0.0"
        );
        
        assertFalse(handler.validateConfig("TestPlugin", config));
    }

    @Test
    void testValidateConfigInvalidResourceId() throws Exception {
        SpigotMCSourceHandler handler = createMinimalInstance("1.21.9");
        
        Map<String, Object> config = Map.of(
            "resource_id", "not-a-number",
            "version", "1.0.0"
        );
        
        assertFalse(handler.validateConfig("TestPlugin", config));
    }

    @Test
    void testGetSourceName() throws Exception {
        SpigotMCSourceHandler handler = createMinimalInstance("1.21.9");
        assertEquals("spigotmc", handler.getSourceName());
    }

    private SpigotMCSourceHandler createMinimalInstance(String serverVersion) throws Exception {
        // Use Constructor with all null args for testing - use the backward-compatible constructor (index 1)
        var constructor = SpigotMCSourceHandler.class.getDeclaredConstructors()[1];
        constructor.setAccessible(true);
        return (SpigotMCSourceHandler) constructor.newInstance(
            java.net.http.HttpClient.newHttpClient(), serverVersion, "1.0.0", java.util.logging.Logger.getLogger("test"), Executors.newSingleThreadExecutor(), 10, 60, "update"
        );
    }
}