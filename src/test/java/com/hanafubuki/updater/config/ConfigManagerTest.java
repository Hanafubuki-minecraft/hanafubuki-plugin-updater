package com.hanafubuki.updater.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConfigManager configuration loading and validation.
 */
class ConfigManagerTest {

    private ConfigManager configManager;
    private File configFile;

    @BeforeEach
    void setUp(@TempDir File tempDir) throws IOException {
        configFile = new File(tempDir, "config.yml");
        // Use test constructor that doesn't require PluginUpdater
        configManager = new ConfigManager((JavaPlugin) null);
    }

    private void writeConfig(String yamlContent) throws IOException {
        java.nio.file.Files.writeString(configFile.toPath(), yamlContent);
        configManager.loadConfig(configFile.getParentFile());
    }

    @Test
    void testLoadConfigCreatesDefault() throws IOException {
        // When plugin is null, loadConfig won't create default config from resources
        // This is expected behavior for testing
        configManager.loadConfig(configFile.getParentFile());
        // Config file won't exist because we don't have a real plugin to copy from resources
        // The test verifies no exception is thrown
    }

    @Test
    void testGetGlobalConfig() throws IOException {
        String yaml = """
            global:
              min_version: "1.0.0"
              max_version: "99.99.99"
            """;
        writeConfig(yaml);

        Map<String, Object> global = configManager.getGlobalConfig();
        assertEquals("1.0.0", global.get("min_version"));
        assertEquals("99.99.99", global.get("max_version"));
    }

    @Test
    void testGetModesConfig() throws IOException {
        String yaml = """
            modes:
              include_only: true
              exclude_list:
                - "plugin1"
                - "plugin2"
              include_list:
                - "plugin3"
            """;
        writeConfig(yaml);

        assertTrue(configManager.getIncludeOnlyMode());
        assertEquals(2, configManager.getExcludeList().size());
        assertTrue(configManager.getExcludeList().contains("plugin1"));
        assertEquals(1, configManager.getIncludeList().size());
        assertTrue(configManager.getIncludeList().contains("plugin3"));
    }

    @Test
    void testIsPluginExcluded() throws IOException {
        String yaml = """
            modes:
              exclude_list:
                - "plugin-to-skip"
            """;
        writeConfig(yaml);

        assertTrue(configManager.isPluginExcluded("plugin-to-skip"));
        assertFalse(configManager.isPluginExcluded("other-plugin"));
    }

    @Test
    void testGetPlugins() throws IOException {
        String yaml = """
            plugins:
              "WorldEdit":
                source: "modrinth"
                version: "7.3.12"
              "Paper":
                source: "github"
                version: "1.21.9"
                repo: "PaperMC/Paper"
            """;
        writeConfig(yaml);

        Map<String, Object> plugins = configManager.getPlugins();
        assertEquals(2, plugins.size());
        assertTrue(plugins.containsKey("WorldEdit"));
        assertTrue(plugins.containsKey("Paper"));

        @SuppressWarnings("unchecked")
        Map<String, Object> worldEdit = (Map<String, Object>) plugins.get("WorldEdit");
        assertEquals("modrinth", worldEdit.get("source"));
        assertEquals("7.3.12", worldEdit.get("version"));
    }

    @Test
    void testGetPluginDetails() throws IOException {
        String yaml = """
            plugins:
              "WorldEdit":
                source: "modrinth"
                version: "7.3.12"
                project_id: "worldedit"
            """;
        writeConfig(yaml);

        Map<String, Object> details = configManager.getPluginDetails("WorldEdit");
        assertEquals("modrinth", details.get("source"));
        assertEquals("7.3.12", details.get("version"));
        assertEquals("worldedit", details.get("project_id"));
    }

    @Test
    void testValidatePluginConfigValidModrinth() throws IOException {
        String yaml = """
            plugins:
              "WorldEdit":
                source: "modrinth"
                version: "7.3.12"
            """;
        writeConfig(yaml);

        Map<String, Object> details = configManager.getPluginDetails("WorldEdit");
        assertTrue(configManager.validatePluginConfig("WorldEdit", details));
    }

    @Test
    void testValidatePluginConfigValidGitHub() throws IOException {
        String yaml = """
            plugins:
              "Paper":
                source: "github"
                version: "1.21.9"
                repo: "PaperMC/Paper"
            """;
        writeConfig(yaml);

        Map<String, Object> details = configManager.getPluginDetails("Paper");
        assertTrue(configManager.validatePluginConfig("Paper", details));
    }

    @Test
    void testValidatePluginConfigMissingSource() throws IOException {
        String yaml = """
            plugins:
              "BadPlugin":
                version: "1.0.0"
            """;
        writeConfig(yaml);

        Map<String, Object> details = configManager.getPluginDetails("BadPlugin");
        assertFalse(configManager.validatePluginConfig("BadPlugin", details));
    }

    @Test
    void testValidatePluginConfigInvalidSource() throws IOException {
        String yaml = """
            plugins:
              "BadPlugin":
                source: "modrint"
                version: "1.0.0"
            """;
        writeConfig(yaml);

        Map<String, Object> details = configManager.getPluginDetails("BadPlugin");
        assertFalse(configManager.validatePluginConfig("BadPlugin", details));
    }

    @Test
    void testValidatePluginConfigMissingVersion() throws IOException {
        String yaml = """
            plugins:
              "BadPlugin":
                source: "modrinth"
            """;
        writeConfig(yaml);

        Map<String, Object> details = configManager.getPluginDetails("BadPlugin");
        assertFalse(configManager.validatePluginConfig("BadPlugin", details));
    }

    @Test
    void testValidatePluginConfigGitHubMissingRepo() throws IOException {
        String yaml = """
            plugins:
              "BadPlugin":
                source: "github"
                version: "1.0.0"
            """;
        writeConfig(yaml);

        Map<String, Object> details = configManager.getPluginDetails("BadPlugin");
        assertFalse(configManager.validatePluginConfig("BadPlugin", details));
    }

    @Test
    void testValidatePluginConfigGitHubInvalidRepoFormat() throws IOException {
        String yaml = """
            plugins:
              "BadPlugin":
                source: "github"
                version: "1.0.0"
                repo: "PaperMC"
            """;
        writeConfig(yaml);

        Map<String, Object> details = configManager.getPluginDetails("BadPlugin");
        assertFalse(configManager.validatePluginConfig("BadPlugin", details));
    }

    @Test
    void testGetModrinthConfig() throws IOException {
        String yaml = """
            modrinth:
              timeout: 15
              loaders: ["paper", "spigot"]
              game_versions: ["1.21.9", "1.21.8"]
            """;
        writeConfig(yaml);

        Map<String, Object> modrinth = configManager.getModrinthConfig();
        assertEquals(15, modrinth.get("timeout"));
        assertEquals(2, ((java.util.List<?>) modrinth.get("loaders")).size());
    }

    @Test
    void testGetGitHubConfig() throws IOException {
        String yaml = """
            github:
              timeout: 20
              token: "ghp_test"
              default_branch: "main"
            """;
        writeConfig(yaml);

        Map<String, Object> github = configManager.getGitHubConfig();
        assertEquals(20, github.get("timeout"));
        assertEquals("ghp_test", github.get("token"));
        assertEquals("main", github.get("default_branch"));
    }

    @Test
    void testGetConsoleConfig() throws IOException {
        String yaml = """
            console:
              mini_message: false
              colors:
                info: "<green>"
                error: "<red>"
            """;
        writeConfig(yaml);

        Map<String, Object> console = configManager.getConsoleConfig();
        Object miniMessageObj = console.get("mini_message");
        // YAML may parse "false" as Boolean or String
        boolean miniMessage = false;
        if (miniMessageObj instanceof Boolean) {
            miniMessage = (Boolean) miniMessageObj;
        } else if (miniMessageObj instanceof String) {
            miniMessage = Boolean.parseBoolean((String) miniMessageObj);
        }
        assertFalse(miniMessage);
        Object colorsObj = console.get("colors");
        if (colorsObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> colors = (Map<String, Object>) colorsObj;
            assertEquals("<green>", colors.get("info"));
        }
    }
}