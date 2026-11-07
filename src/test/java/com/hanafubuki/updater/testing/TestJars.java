package com.hanafubuki.updater.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * Builds small plugin jars for tests.
 */
public final class TestJars {

    private TestJars() {
    }

    /** Jar bytes with a plugin.yml for the given name and version. */
    public static byte[] plugin(String name, String version) {
        return plugin(name, version, List.of(), null);
    }

    /** Jar bytes with a plugin.yml including hard dependencies and an api-version. */
    public static byte[] plugin(String name, String version, List<String> depend, String apiVersion) {
        StringBuilder yml = new StringBuilder();
        yml.append("name: ").append(name).append('\n');
        yml.append("version: '").append(version).append("'\n");
        yml.append("main: test.Main\n");
        if (apiVersion != null) {
            yml.append("api-version: '").append(apiVersion).append("'\n");
        }
        if (!depend.isEmpty()) {
            yml.append("depend: [").append(String.join(", ", depend)).append("]\n");
        }
        return jar("plugin.yml", yml.toString());
    }

    /** Jar bytes with a paper-plugin.yml. */
    public static byte[] paperPlugin(String name, String version) {
        return jar("paper-plugin.yml", "name: " + name + "\nversion: '" + version + "'\nmain: test.Main\napi-version: '1.21'\n");
    }

    /** Jar with one text entry. */
    public static byte[] jar(String entryName, String content) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (JarOutputStream out = new JarOutputStream(bytes)) {
                out.putNextEntry(entry(entryName));
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
                // Unique filler so different versions never have the same hash.
                out.putNextEntry(entry("filler.txt"));
                out.write((entryName + content).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Zip entry with a fixed time, so the same content always gives the same bytes and hash. */
    private static ZipEntry entry(String name) {
        ZipEntry e = new ZipEntry(name);
        e.setTime(FIXED_TIME);
        return e;
    }

    private static final long FIXED_TIME = 1_700_000_000_000L;

    /** Writes a plugin jar to dir/fileName and returns its path. */
    public static Path write(Path dir, String fileName, byte[] content) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(fileName);
        Files.write(p, content);
        return p;
    }
}
