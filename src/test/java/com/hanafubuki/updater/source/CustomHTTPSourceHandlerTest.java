package com.hanafubuki.updater.source;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CustomHTTPSourceHandler utility methods.
 */
class CustomHTTPSourceHandlerTest {

    @Test
    void testValidateConfigValid() throws Exception {
        CustomHTTPSourceHandler handler = createMinimalInstance();
        
        Map<String, Object> config = Map.of(
            "url", "https://api.example.com/plugin/latest",
            "version", "1.0.0"
        );
        
        assertTrue(handler.validateConfig("TestPlugin", config));
    }

    @Test
    void testValidateConfigWithJsonPaths() throws Exception {
        CustomHTTPSourceHandler handler = createMinimalInstance();
        
        Map<String, Object> config = Map.of(
            "url", "https://api.example.com/plugin/latest",
            "version", "1.0.0",
            "version_path", "$.version",
            "download_url_path", "$.download_url",
            "checksum_path", "$.sha256"
        );
        
        assertTrue(handler.validateConfig("TestPlugin", config));
    }

    @Test
    void testValidateConfigWithAuth() throws Exception {
        CustomHTTPSourceHandler handler = createMinimalInstance();
        
        Map<String, Object> config = Map.of(
            "url", "https://api.example.com/plugin/latest",
            "version", "1.0.0",
            "auth", Map.of(
                "type", "bearer",
                "token", "test-token"
            )
        );
        
        assertTrue(handler.validateConfig("TestPlugin", config));
    }

    @Test
    void testValidateConfigMissingUrl() throws Exception {
        CustomHTTPSourceHandler handler = createMinimalInstance();
        
        Map<String, Object> config = Map.of(
            "version", "1.0.0"
        );
        
        assertFalse(handler.validateConfig("TestPlugin", config));
    }

    @Test
    void testValidateConfigInvalidJsonPath() throws Exception {
        CustomHTTPSourceHandler handler = createMinimalInstance();
        
        Map<String, Object> config = Map.of(
            "url", "https://api.example.com/plugin/latest",
            "version", "1.0.0",
            "version_path", "[invalid jsonpath"
        );
        
        assertFalse(handler.validateConfig("TestPlugin", config));
    }

    @Test
    void testGetSourceName() throws Exception {
        CustomHTTPSourceHandler handler = createMinimalInstance();
        assertEquals("custom", handler.getSourceName());
    }

    private CustomHTTPSourceHandler createMinimalInstance() throws Exception {
        // Use Constructor with all null args for testing - use the backward-compatible constructor (index 1)
        var constructor = CustomHTTPSourceHandler.class.getDeclaredConstructors()[1];
        constructor.setAccessible(true);
        return (CustomHTTPSourceHandler) constructor.newInstance(
            null, "1.0.0", Logger.getLogger("test"), null, 10, 60, "update"
        );
    }
}