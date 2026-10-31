package com.hanafubuki.updater.source;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GitHubSourceHandler utility methods.
 */
class GitHubSourceHandlerTest {

    @Test
    void testMatchesPatternWildcard() throws Exception {
        // Test the private static matchesPattern method directly via reflection
        assertTrue(invokeMatchesPattern("plugin-1.0.0.jar", "plugin-*.jar"));
        assertTrue(invokeMatchesPattern("plugin-v2.0.0.jar", "plugin-*.jar"));
        assertTrue(invokeMatchesPattern("my-plugin.jar", "*.jar"));
        assertTrue(invokeMatchesPattern("anything", "*"));
    }

    @Test
    void testMatchesPatternExactMatch() throws Exception {
        assertTrue(invokeMatchesPattern("plugin.jar", "plugin.jar"));
        assertTrue(invokeMatchesPattern("exact-name", "exact-name"));
    }

    @Test
    void testMatchesPatternNoMatch() throws Exception {
        assertFalse(invokeMatchesPattern("plugin.jar", "other.jar"));
        assertFalse(invokeMatchesPattern("plugin-1.0.0.jar", "plugin-v*.jar"));
        assertFalse(invokeMatchesPattern("test.jar", "plugin-*.jar"));
    }

    @Test
    void testMatchesPatternNullOrEmpty() throws Exception {
        assertTrue(invokeMatchesPattern("anything.jar", null));
        assertTrue(invokeMatchesPattern("anything.jar", ""));
    }

    @Test
    void testMatchesPatternEscapedDots() throws Exception {
        // Dots in pattern should be treated as literal dots
        assertTrue(invokeMatchesPattern("plugin-1.0.0.jar", "plugin-1.0.0.jar"));
        assertFalse(invokeMatchesPattern("plugin-1x0x0.jar", "plugin-1.0.0.jar"));
    }

    private boolean invokeMatchesPattern(String assetName, String pattern) throws Exception {
        Method method = GitHubSourceHandler.class.getDeclaredMethod("matchesPattern", String.class, String.class);
        method.setAccessible(true);
        // The method is not static, but we can invoke it on any instance
        // Create a minimal instance using reflection with null args
        GitHubSourceHandler handler = createMinimalInstance();
        return (Boolean) method.invoke(handler, assetName, pattern);
    }

    private GitHubSourceHandler createMinimalInstance() throws Exception {
        // Use Constructor with all null args for testing - use the backward-compatible constructor (index 1)
        var constructor = GitHubSourceHandler.class.getDeclaredConstructors()[1];
        constructor.setAccessible(true);
        return (GitHubSourceHandler) constructor.newInstance(
            null, null, "1.0.0", null, null, 10, 60, "update"
        );
    }
}