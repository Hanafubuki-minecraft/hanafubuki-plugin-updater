package com.hanafubuki.updater.source;

import java.nio.file.Path;
import java.util.List;

/**
 * A plugin loaded by the server.
 *
 * @param name    plugin name
 * @param version version from its descriptor
 * @param jar     the jar file it was loaded from
 * @param depend  hard dependencies of the running version
 */
public record InstalledPlugin(String name, String version, Path jar, List<String> depend) {

    public InstalledPlugin {
        depend = depend == null ? List.of() : List.copyOf(depend);
    }

    public InstalledPlugin(String name, String version, Path jar) {
        this(name, version, jar, List.of());
    }
}
