package com.hanafubuki.updater.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One entry of the {@code plugins:} section of config.yml.
 *
 * @param name    plugin name as shown by /plugins (case-insensitive match)
 * @param source  source id: modrinth, github, hangar, spigotmc, local or custom
 * @param options all other keys of the entry (project_id, repo, asset_pattern, ...)
 */
public record PluginEntry(String name, String source, Map<String, Object> options) {

    public PluginEntry {
        source = source == null ? null : source.trim().toLowerCase(Locale.ROOT);
        options = Collections.unmodifiableMap(new LinkedHashMap<>(options == null ? Map.of() : options));
    }

    /** Trimmed string option, or null if missing or blank. */
    public String string(String key) {
        Object v = options.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    public boolean bool(String key, boolean def) {
        Object v = options.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return s.equalsIgnoreCase("true") || (!s.equalsIgnoreCase("false") && def);
        }
        return def;
    }

    /** A list option. A single value is returned as a one-element list. Null if missing. */
    public List<String> list(String key) {
        Object v = options.get(key);
        if (v == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
            }
        } else if (!String.valueOf(v).isBlank()) {
            out.add(String.valueOf(v).trim());
        }
        return out;
    }

    /** A nested map option with string values. Empty if missing. */
    public Map<String, String> map(String key) {
        Map<String, String> out = new LinkedHashMap<>();
        if (options.get(key) instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
        return out;
    }

    public String minVersion() {
        return string("min_version");
    }

    public String maxVersion() {
        return string("max_version");
    }
}
