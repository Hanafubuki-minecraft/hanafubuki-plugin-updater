package com.hanafubuki.updater.discovery;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.hanafubuki.updater.source.GitHubSource;

/**
 * Suggests config entries for installed plugins that are not in config.yml yet.
 *
 * <p>Order of evidence:
 * <ol>
 *   <li>Modrinth hash lookup of the installed jar. This is exact: the file itself is on Modrinth.</li>
 *   <li>The plugin's {@code website}: modrinth.com/plugin/x, hangar.papermc.io/Owner/Slug,
 *       spigotmc.org/resources/name.ID, github.com/owner/repo.</li>
 * </ol>
 * Plugins with no evidence get no source and are only listed, never added.
 */
public final class PluginDiscovery {

    /** Finds the Modrinth project id of a jar; empty if the jar is not on Modrinth. */
    @FunctionalInterface
    public interface ModrinthLookup {
        Optional<String> projectIdOf(Path jar) throws IOException;
    }

    /**
     * A loaded plugin to analyse.
     *
     * @param website value of {@code website} in its descriptor, may be null
     */
    public record Candidate(String name, String version, Path jar, String website) {
    }

    /**
     * A suggested config entry.
     *
     * @param source  source id, or null if nothing reliable was found
     * @param options entry options (project_id, repo, ...)
     * @param reason  how it was found, or why not
     * @param lookupFailed true if the Modrinth lookup failed (network error), so the result may be
     *                     incomplete and a later detection should try again
     */
    public record Suggestion(String name, String version, String source, Map<String, Object> options, String reason,
                             boolean lookupFailed) {

        public Suggestion(String name, String version, String source, Map<String, Object> options, String reason) {
            this(name, version, source, options, reason, false);
        }

        public boolean isUsable() {
            return source != null;
        }
    }

    private static final Pattern SPIGOT_RESOURCE = Pattern.compile("/resources/(?:[^/]*\\.)?(\\d+)/?");
    private static final Set<String> GITHUB_RESERVED = Set.of("sponsors", "orgs", "topics", "features", "marketplace",
            "settings", "apps", "about", "collections", "enterprise", "login", "pricing", "readme", "explore", "users",
            "notifications", "issues", "pulls", "search", "trending", "site", "security", "customer-stories");
    private static final Set<String> HANGAR_RESERVED = Set.of("api", "auth", "admin", "tools", "staff", "notifications",
            "linkout", "settings", "authors", "guidelines", "paper", "velocity", "waterfall", "new", "logout", "login");

    private final ModrinthLookup modrinth;

    public PluginDiscovery(ModrinthLookup modrinth) {
        this.modrinth = modrinth;
    }

    /**
     * Suggests entries for all candidates. After the first failed Modrinth lookup the remaining
     * candidates are not sent to Modrinth (it is most likely down); they fall back to their website
     * and are marked {@link Suggestion#lookupFailed()} so a later detection tries again.
     */
    public List<Suggestion> discover(List<Candidate> candidates) throws InterruptedIOException {
        List<Suggestion> out = new ArrayList<>();
        boolean modrinthDown = false;
        for (Candidate c : candidates) {
            Suggestion s = suggest(c, modrinthDown);
            modrinthDown |= s.lookupFailed();
            out.add(s);
        }
        return out;
    }

    public Suggestion suggest(Candidate c) throws InterruptedIOException {
        return suggest(c, false);
    }

    private Suggestion suggest(Candidate c, boolean skipModrinth) throws InterruptedIOException {
        String lookupError = skipModrinth ? "skipped after an earlier Modrinth error" : null;
        if (!skipModrinth && modrinth != null && c.jar() != null) {
            try {
                Optional<String> projectId = modrinth.projectIdOf(c.jar());
                if (projectId.isPresent()) {
                    return new Suggestion(c.name(), c.version(), "modrinth", Map.of("project_id", projectId.get()),
                            "the installed jar is published on Modrinth");
                }
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                lookupError = e.getMessage();
            }
        }
        Suggestion fromWebsite = fromWebsite(c.name(), c.version(), c.website());
        if (lookupError == null) {
            return fromWebsite;
        }
        if (fromWebsite.isUsable()) {
            // Usable, but an exact Modrinth match may exist: look again next time.
            return new Suggestion(c.name(), c.version(), fromWebsite.source(), fromWebsite.options(),
                    fromWebsite.reason() + " (Modrinth lookup failed: " + lookupError + ")", true);
        }
        return new Suggestion(c.name(), c.version(), null, Map.of(), "Modrinth lookup failed: " + lookupError, true);
    }

    /** Derives a source from a website URL alone. */
    public static Suggestion fromWebsite(String name, String version, String website) {
        if (website == null || website.isBlank()) {
            return new Suggestion(name, version, null, Map.of(), "not on Modrinth and no website in plugin.yml");
        }
        URI uri;
        try {
            String w = website.trim();
            uri = URI.create(w.contains("://") ? w : "https://" + w);
        } catch (IllegalArgumentException e) {
            return new Suggestion(name, version, null, Map.of(), "website is not a valid URL");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        List<String> path = segments(uri.getPath());

        if (host.equals("modrinth.com") && path.size() >= 2 && List.of("plugin", "mod", "project").contains(path.get(0))) {
            return found(name, version, "modrinth", Map.of("project_id", path.get(1)), "website is a Modrinth page");
        }
        if (host.equals("hangar.papermc.io") && path.size() >= 2 && !HANGAR_RESERVED.contains(path.get(0).toLowerCase(Locale.ROOT))) {
            return found(name, version, "hangar", Map.of("project_slug", path.get(1)), "website is a Hangar page");
        }
        if (host.equals("spigotmc.org")) {
            Matcher m = SPIGOT_RESOURCE.matcher(uri.getPath() == null ? "" : uri.getPath());
            if (m.find()) {
                return found(name, version, "spigotmc", Map.of("resource_id", m.group(1)),
                        "website is a SpigotMC resource page");
            }
        }
        if (host.equals("github.com") && path.size() >= 2 && !GITHUB_RESERVED.contains(path.get(0).toLowerCase(Locale.ROOT))) {
            String repo = path.get(0) + "/" + path.get(1).replaceFirst("\\.git$", "");
            if (GitHubSource.REPO.matcher(repo).matches()) {
                return found(name, version, "github", Map.of("repo", repo),
                        "website is a GitHub repository; check that releases contain the plugin jar");
            }
        }
        return new Suggestion(name, version, null, Map.of(), "website " + host + " is not a known plugin source");
    }

    private static Suggestion found(String name, String version, String source, Map<String, Object> options, String reason) {
        return new Suggestion(name, version, source, new LinkedHashMap<>(options), reason);
    }

    private static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        if (path == null) {
            return out;
        }
        for (String s : path.split("/")) {
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }
}
