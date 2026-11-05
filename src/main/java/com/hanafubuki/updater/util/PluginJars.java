package com.hanafubuki.updater.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Finds the jar file a plugin really came from.
 *
 * <p>Paper remaps plugins that use Spigot mappings and loads a modified copy from
 * {@code plugins/.paper-remapped/<same file name>}. That copy has a different hash (so Modrinth and
 * Hangar cannot identify it) and lives outside the plugins folder (so staging an update under its
 * path would be wrong). The original file in {@code plugins/} is what has to be checked and replaced.
 */
public final class PluginJars {

    /** Folder Paper writes remapped plugin copies to, inside the plugins folder. */
    public static final String PAPER_REMAPPED = ".paper-remapped";

    private PluginJars() {
    }

    /**
     * Maps the jar a plugin class was loaded from to the original jar in the plugins folder.
     *
     * @param loaded     location of the loaded classes (may be a remapped copy)
     * @param pluginsDir the server's plugins folder
     * @return the original jar; empty if the plugin was loaded from a remapped copy whose original
     *         is not in the plugins folder (for example a plugin added with {@code -add-plugin})
     */
    public static Optional<Path> original(Path loaded, Path pluginsDir) {
        Path normalized = loaded.toAbsolutePath().normalize();
        boolean remapped = false;
        for (Path part : normalized) {
            if (part.toString().equals(PAPER_REMAPPED)) {
                remapped = true;
                break;
            }
        }
        if (!remapped) {
            return Files.isRegularFile(normalized) ? Optional.of(normalized) : Optional.empty();
        }
        Path original = pluginsDir.toAbsolutePath().normalize().resolve(normalized.getFileName().toString());
        return Files.isRegularFile(original) ? Optional.of(original) : Optional.empty();
    }
}
