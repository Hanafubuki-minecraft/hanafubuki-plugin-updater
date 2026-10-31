package com.hanafubuki.updater.source;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;
import java.util.function.Function;

import com.hanafubuki.updater.util.JarUtils;
import com.hanafubuki.updater.util.VersionUtils;

/**
 * Handler for local file source (filesystem, network shares, NFS mounts)
 * Watches for new versions by filename pattern or version in filename
 */
public class LocalFileSourceHandler implements SourceHandler {

    private final Logger logger;
    private final String updateFolderName;
    private final int timeoutSeconds;

    /**
     * Constructor for LocalFileSourceHandler
     * @param logger The logger to use for output
     * @param updateFolderName The name of the update folder (from bukkit.yml settings.update-folder)
     * @param timeoutSeconds Timeout for file operations in seconds
     */
    public LocalFileSourceHandler(Logger logger, String updateFolderName, int timeoutSeconds) {
        this.logger = logger;
        this.updateFolderName = updateFolderName != null ? updateFolderName : "update";
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 5;
    }

    @Override
    public String getSourceName() {
        return "local";
    }

    @Override
    public boolean validateConfig(String pluginName, Map<String, Object> pluginDetails) {
        Object pathObj = pluginDetails.get("path");
        if (pathObj == null || pathObj.toString().trim().isEmpty()) {
            logger.severe("Plugin '" + pluginName + "' (local source) is missing required field 'path' (directory or file path)");
            return false;
        }

        Path path = Path.of(pathObj.toString().trim());
        if (!Files.exists(path)) {
            logger.warning("Plugin '" + pluginName + "' (local source) path does not exist: " + path + " (will be checked at update time)");
        }

        // Optional: pattern for filename matching
        Object patternObj = pluginDetails.get("pattern");
        if (patternObj != null) {
            try {
                Pattern.compile(patternObj.toString());
            } catch (Exception e) {
                logger.severe("Plugin '" + pluginName + "' (local source) has invalid regex pattern: " + e.getMessage());
                return false;
            }
        }

        return true;
    }

    @Override
    public String getLatestVersion(String pluginName, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException {
        try {
            Path sourcePath = getSourcePath(pluginDetails);
            if (sourcePath == null || !Files.exists(sourcePath)) {
                throw new IOException("Source path does not exist: " + sourcePath);
            }

            String pattern = getPattern(pluginDetails);
            String currentVersion = (String) pluginDetails.get("version");

            List<VersionedFile> versionedFiles = scanForVersions(sourcePath, pattern, pluginName);
            
            if (versionedFiles.isEmpty()) {
                logger.info("No versioned files found for " + pluginName + " at " + sourcePath);
                return null;
            }

            // Sort by version (newest first)
            versionedFiles.sort(Comparator.comparing(VersionedFile::version, Comparator.nullsLast(VersionUtils.VERSION_COMPARATOR)).reversed());

            // Return the newest version that's newer than current
            for (VersionedFile vf : versionedFiles) {
                if (vf.version != null && VersionUtils.isVersionNewer(vf.version, currentVersion)) {
                    logger.info("Found newer local version for " + pluginName + ": " + vf.version + " (file: " + vf.file.getFileName() + ")");
                    return vf.version;
                }
            }

            // If no newer version, return the latest available
            VersionedFile latest = versionedFiles.get(0);
            if (latest.version != null) {
                return latest.version;
            }

        } catch (Exception e) {
            throw new IOException("Failed to get latest version from local source: " + e.getMessage(), e);
        }
        return null;
    }

    @Override
    public String getDownloadUrl(String pluginName, String version, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException {
        try {
            Path sourcePath = getSourcePath(pluginDetails);
            if (sourcePath == null || !Files.exists(sourcePath)) {
                throw new IOException("Source path does not exist: " + sourcePath);
            }

            String pattern = getPattern(pluginDetails);
            List<VersionedFile> versionedFiles = scanForVersions(sourcePath, pattern, pluginName);

            for (VersionedFile vf : versionedFiles) {
                if (vf.version != null && vf.version.equals(version)) {
                    // Return file:// URL for local file
                    return vf.file.toUri().toString();
                }
            }

            // If exact version not found, try to find a file matching the version in name
            for (VersionedFile vf : versionedFiles) {
                if (vf.file.getFileName().toString().contains(version)) {
                    return vf.file.toUri().toString();
                }
            }

        } catch (Exception e) {
            throw new IOException("Failed to get download URL from local source: " + e.getMessage(), e);
        }
        return null;
    }

    @Override
    public void downloadAndInstall(String pluginName, String downloadUrl, Path installedJarPath, Map<String, Object> pluginDetails) throws IOException {
        try {
            // Parse file:// URL
            URI uri = URI.create(downloadUrl);
            if (!"file".equals(uri.getScheme())) {
                throw new IOException("Local source only supports file:// URLs, got: " + downloadUrl);
            }

            Path sourceFile = Path.of(uri);
            if (!Files.exists(sourceFile)) {
                throw new IOException("Source file does not exist: " + sourceFile);
            }

            // Determine target filename (same as installed jar)
            String targetFileName = installedJarPath.getFileName().toString();
            Path updateDir = installedJarPath.getParent().resolve(updateFolderName);
            Files.createDirectories(updateDir);

            Path tempFile = updateDir.resolve(targetFileName + ".part");
            Path targetFile = updateDir.resolve(targetFileName);

            // Copy the file (local "download")
            logger.info("Copying " + pluginName + " from local source: " + sourceFile);
            Files.copy(sourceFile, tempFile, StandardCopyOption.REPLACE_EXISTING);

            // Verify checksum if provided
            String expectedChecksum = getExpectedChecksum(pluginName, (String) pluginDetails.get("version"), pluginDetails, installedJarPath);
            if (expectedChecksum != null && !expectedChecksum.isEmpty()) {
                String actualChecksum = computeSha256(tempFile);
                if (!actualChecksum.equalsIgnoreCase(expectedChecksum)) {
                    Files.deleteIfExists(tempFile);
                    throw new IOException("SHA-256 mismatch for " + pluginName + ": expected " + expectedChecksum + ", got " + actualChecksum);
                }
            }

            // Verify plugin.yml name inside JAR matches installed plugin name
            String jarPluginName = JarUtils.getPluginNameFromJar(tempFile);
            if (jarPluginName == null || !jarPluginName.equalsIgnoreCase(pluginName)) {
                Files.deleteIfExists(tempFile);
                throw new IOException("Plugin name mismatch: JAR contains '" + jarPluginName + "', expected '" + pluginName + "'");
            }

            // Atomic move to target location
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            logger.info("Plugin " + pluginName + " copied and verified successfully to: " + targetFile);

        } catch (Exception e) {
            throw new IOException("Failed to copy and install plugin from local source: " + e.getMessage(), e);
        }
    }

    @Override
    public String getExpectedChecksum(String pluginName, String version, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException {
        // Check if checksum is explicitly provided in config
        Object checksumObj = pluginDetails.get("checksum");
        if (checksumObj != null) {
            return checksumObj.toString().trim();
        }

        // Check for separate checksum file (.sha256 or .sha512)
        try {
            Path sourcePath = getSourcePath(pluginDetails);
            String pattern = getPattern(pluginDetails);
            List<VersionedFile> versionedFiles = scanForVersions(sourcePath, pattern, pluginName);

            for (VersionedFile vf : versionedFiles) {
                if (vf.version != null && vf.version.equals(version)) {
                    // Look for .sha256 or .sha512 file alongside the jar
                    Path sha256File = vf.file.resolveSibling(vf.file.getFileName() + ".sha256");
                    Path sha512File = vf.file.resolveSibling(vf.file.getFileName() + ".sha512");
                    
                    if (Files.exists(sha256File)) {
                        return Files.readString(sha256File).trim().split("\\s+")[0];
                    }
                    if (Files.exists(sha512File)) {
                        return Files.readString(sha512File).trim().split("\\s+")[0];
                    }
                }
            }
        } catch (Exception ignored) {
            // Ignore checksum file errors
        }

        return null;
    }

    private Path getSourcePath(Map<String, Object> pluginDetails) {
        Object pathObj = pluginDetails.get("path");
        if (pathObj == null) return null;
        String pathStr = pathObj.toString().trim();
        if (pathStr.isEmpty()) return null;
        return Path.of(pathStr);
    }

    private String getPattern(Map<String, Object> pluginDetails) {
        Object patternObj = pluginDetails.get("pattern");
        if (patternObj == null) return null;
        String pattern = patternObj.toString().trim();
        return pattern.isEmpty() ? null : pattern;
    }

    private List<VersionedFile> scanForVersions(Path sourcePath, String pattern, String pluginName) throws IOException {
        List<VersionedFile> result = new ArrayList<>();

        if (Files.isRegularFile(sourcePath)) {
            // Single file - extract version from filename
            String version = extractVersionFromFilename(sourcePath.getFileName().toString(), pattern);
            if (version != null) {
                result.add(new VersionedFile(version, sourcePath));
            }
        } else if (Files.isDirectory(sourcePath)) {
            // Directory - scan for matching files
            try (var stream = Files.list(sourcePath)) {
                stream.filter(Files::isRegularFile)
                      .filter(p -> p.getFileName().toString().endsWith(".jar"))
                      .forEach(jarFile -> {
                          String version = extractVersionFromFilename(jarFile.getFileName().toString(), pattern);
                          if (version != null) {
                              result.add(new VersionedFile(version, jarFile));
                          }
                      });
            }
        }

        return result;
    }

    private String extractVersionFromFilename(String filename, String pattern) {
        // If custom pattern provided, use it to extract version
        if (pattern != null && !pattern.isEmpty()) {
            try {
                Pattern p = Pattern.compile(pattern);
                Matcher m = p.matcher(filename);
                if (m.find()) {
                    // Return first capturing group if exists, otherwise whole match
                    return m.groupCount() > 0 ? m.group(1) : m.group();
                }
            } catch (Exception ignored) {
                // Fall back to default extraction
            }
        }

        // Default: try to extract version from filename
        // Common patterns: plugin-1.2.3.jar, plugin_v1.2.3.jar, plugin-1.2.3-SNAPSHOT.jar
        String[] patterns = {
            ".*[_-]v?(\\d+\\.\\d+\\.\\d+(?:[-.][\\w.]+)?)\\.jar$",  // plugin-1.2.3.jar or plugin_v1.2.3.jar
            ".*[_-](\\d+\\.\\d+\\.\\d+)\\..*\\.jar$",               // plugin-1.2.3-something.jar
            ".*[_-](\\d+\\.\\d+)\\.jar$",                            // plugin-1.21.jar
        };

        for (String p : patterns) {
            Pattern pat = Pattern.compile(p, Pattern.CASE_INSENSITIVE);
            Matcher m = pat.matcher(filename);
            if (m.matches()) {
                return m.group(1);
            }
        }

        // Try to get version from plugin.yml inside JAR
        try {
            Path jarPath = Path.of(filename); // This won't work for relative paths
            // We need the full path - skip for now
        } catch (Exception ignored) {
        }

        return null;
    }

    private String computeSha256(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }
    }

    private record VersionedFile(String version, Path file) {}
}