package com.hanafubuki.updater.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginJarsTest {

    @TempDir
    Path dir;

    @Test
    void normalJarIsUsedAsIs() throws IOException {
        Path plugins = Files.createDirectories(dir.resolve("plugins"));
        Path jar = Files.writeString(plugins.resolve("LuckPerms.jar"), "x");
        assertEquals(Optional.of(jar.toAbsolutePath().normalize()), PluginJars.original(jar, plugins));
    }

    @Test
    void paperRemappedCopyMapsToTheOriginalInPlugins() throws IOException {
        // Paper loads Spigot-mapped plugins such as GSit from plugins/.paper-remapped/<same name>.
        Path plugins = Files.createDirectories(dir.resolve("plugins"));
        Path original = Files.writeString(plugins.resolve("GSit-3.7.0.jar"), "original");
        Path remapped = Files.writeString(Files.createDirectories(plugins.resolve(".paper-remapped"))
                .resolve("GSit-3.7.0.jar"), "remapped");

        assertEquals(Optional.of(original.toAbsolutePath().normalize()), PluginJars.original(remapped, plugins));
    }

    @Test
    void remappedCopyWithoutOriginalInPluginsIsNotUpdatable() throws IOException {
        // Plugins added with -add-plugin are remapped into .paper-remapped/extra-plugins; their original is elsewhere.
        Path plugins = Files.createDirectories(dir.resolve("plugins"));
        Path remapped = Files.writeString(Files.createDirectories(plugins.resolve(".paper-remapped").resolve("extra-plugins"))
                .resolve("Extra.jar"), "remapped");

        assertEquals(Optional.empty(), PluginJars.original(remapped, plugins));
    }

    @Test
    void missingFileIsEmpty() {
        assertEquals(Optional.empty(), PluginJars.original(dir.resolve("plugins").resolve("Gone.jar"), dir.resolve("plugins")));
    }
}
