package com.hanafubuki.updater.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The parts of a plugin's descriptor (paper-plugin.yml or plugin.yml) that the updater needs.
 *
 * @param name         plugin name
 * @param version      plugin version
 * @param website      website URL, may be null
 * @param apiVersion   declared api-version, may be null
 * @param depend       hard dependencies (plugin names)
 * @param paperPlugin  true if read from paper-plugin.yml
 */
public record PluginDescriptor(String name, String version, String website, String apiVersion,
                               List<String> depend, boolean paperPlugin) {

    /** Descriptor files larger than this are rejected (protects against zip bombs). */
    private static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;

    public PluginDescriptor {
        depend = depend == null ? List.of() : List.copyOf(depend);
    }

    /**
     * Reads the descriptor from a jar. paper-plugin.yml wins over plugin.yml, like Paper does.
     *
     * @return the descriptor, or empty if the file is not a readable plugin jar
     */
    public static Optional<PluginDescriptor> read(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return Optional.empty();
        }
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            ZipEntry paper = jarFile.getEntry("paper-plugin.yml");
            if (paper != null) {
                Optional<PluginDescriptor> d = parse(readEntry(jarFile, paper), true);
                if (d.isPresent()) {
                    return d;
                }
            }
            ZipEntry bukkit = jarFile.getEntry("plugin.yml");
            if (bukkit != null) {
                return parse(readEntry(jarFile, bukkit), false);
            }
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String readEntry(JarFile jarFile, ZipEntry entry) throws IOException {
        try (InputStream in = jarFile.getInputStream(entry)) {
            byte[] data = in.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
            if (data.length > MAX_DESCRIPTOR_BYTES) {
                throw new IOException(entry.getName() + " is larger than 1 MB");
            }
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    /** Parses descriptor YAML text. Empty if there is no name. */
    static Optional<PluginDescriptor> parse(String yamlText, boolean paperPlugin) {
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlText);
        if (!(loaded instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        String name = string(map.get("name"));
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        List<String> depend = new ArrayList<>();
        if (paperPlugin) {
            // paper-plugin.yml: dependencies.server.<Name>.required (default true)
            if (map.get("dependencies") instanceof Map<?, ?> deps && deps.get("server") instanceof Map<?, ?> server) {
                for (Map.Entry<?, ?> e : server.entrySet()) {
                    boolean required = true;
                    if (e.getValue() instanceof Map<?, ?> opts && opts.get("required") instanceof Boolean b) {
                        required = b;
                    }
                    if (required) {
                        depend.add(String.valueOf(e.getKey()));
                    }
                }
            }
        } else if (map.get("depend") instanceof Collection<?> list) {
            for (Object o : list) {
                depend.add(String.valueOf(o));
            }
        }
        return Optional.of(new PluginDescriptor(name.trim(), string(map.get("version")), string(map.get("website")),
                string(map.get("api-version")), depend, paperPlugin));
    }

    private static String string(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
