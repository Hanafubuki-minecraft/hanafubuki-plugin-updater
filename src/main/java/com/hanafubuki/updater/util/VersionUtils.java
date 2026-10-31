package com.hanafubuki.updater.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Version parsing and comparison for the many version styles used by Minecraft plugins.
 *
 * <p>A version is read as: an optional non-numeric prefix ("v", "version-"), a numeric core
 * ("5.12.3", any number of segments), an optional qualifier after "-" and optional build
 * metadata after "+". Examples that compare correctly:
 * <ul>
 *   <li>{@code v2.0.0} vs {@code 1.5.0} (prefix is ignored)</li>
 *   <li>{@code 1.2.3.4} vs {@code 1.2.3} (extra segments count)</li>
 *   <li>{@code 2.0.0-beta.2} vs {@code 2.0.0-rc1} vs {@code 2.0.0} (pre-release order)</li>
 *   <li>{@code 2.12.1-b1280} vs {@code 2.12.1-b1279} (build numbers, Geyser style)</li>
 *   <li>{@code 5.12.3-SNAPSHOT+1113} vs {@code 5.12.3-SNAPSHOT+1112} (build metadata)</li>
 *   <li>{@code v5.5.71-bukkit} (platform words in the qualifier are ignored)</li>
 * </ul>
 */
public final class VersionUtils {

    /** Orders versions from oldest to newest. */
    public static final Comparator<String> COMPARATOR = VersionUtils::compare;

    private static final int RANK_DEV = 0;
    private static final int RANK_ALPHA = 1;
    private static final int RANK_BETA = 2;
    private static final int RANK_RC = 3;
    private static final int RANK_RELEASE = 4;

    private VersionUtils() {
    }

    /**
     * Returns true if {@code candidate} is strictly newer than {@code current}.
     * Null or blank values are never newer and never older than anything.
     */
    public static boolean isNewer(String candidate, String current) {
        if (isBlank(candidate) || isBlank(current)) {
            return false;
        }
        return compare(candidate, current) > 0;
    }

    /**
     * Compares two versions. Negative if {@code a} is older, positive if newer, zero if equal.
     * Null sorts before any version.
     */
    public static int compare(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        return Parsed.of(a).compareTo(Parsed.of(b));
    }

    /**
     * Checks a version against optional bounds. A bound that is null, blank, "none", "latest"
     * or "*" means "no limit".
     *
     * @return true if {@code min <= version <= max}
     */
    public static boolean inRange(String version, String min, String max) {
        if (!isUnbounded(min) && compare(version, min) < 0) {
            return false;
        }
        return isUnbounded(max) || compare(version, max) <= 0;
    }

    /** True if the bound string means "no limit". */
    public static boolean isUnbounded(String bound) {
        if (isBlank(bound)) {
            return true;
        }
        String b = bound.trim().toLowerCase(Locale.ROOT);
        return b.equals("none") || b.equals("latest") || b.equals("*");
    }

    /**
     * Checks a version against a range expression as used by GitHub security advisories,
     * for example {@code "< 1.2.3"}, {@code ">= 1.0, < 1.4.2"} or {@code "= 2.0.0"}.
     * Several comma-separated conditions must all hold.
     *
     * @throws IllegalArgumentException if the expression cannot be parsed
     */
    public static boolean matchesRange(String version, String rangeExpression) {
        if (isBlank(rangeExpression)) {
            throw new IllegalArgumentException("Empty version range");
        }
        for (String rawPart : rangeExpression.split(",")) {
            String part = rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }
            String op;
            if (part.startsWith(">=") || part.startsWith("<=")) {
                op = part.substring(0, 2);
            } else if (part.startsWith(">") || part.startsWith("<") || part.startsWith("=")) {
                op = part.substring(0, 1);
            } else {
                op = "=";
            }
            String bound = part.substring(op.equals("=") && !part.startsWith("=") ? 0 : op.length()).trim();
            if (bound.isEmpty()) {
                throw new IllegalArgumentException("Missing version in range part: '" + part + "'");
            }
            int cmp = compare(version, bound);
            boolean ok = switch (op) {
                case ">=" -> cmp >= 0;
                case "<=" -> cmp <= 0;
                case ">" -> cmp > 0;
                case "<" -> cmp < 0;
                default -> cmp == 0;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Strips a leading non-numeric prefix such as "v" or "release-". */
    public static String normalize(String version) {
        if (version == null) {
            return null;
        }
        String v = version.trim();
        int i = 0;
        while (i < v.length() && !Character.isDigit(v.charAt(i))) {
            i++;
        }
        return i < v.length() ? v.substring(i) : v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Parsed form of a version string. */
    private record Parsed(List<Long> core, int preRank, long preNumber, long build) implements Comparable<Parsed> {

        static Parsed of(String raw) {
            String v = normalize(raw);
            String buildPart = null;
            int plus = v.indexOf('+');
            if (plus >= 0) {
                buildPart = v.substring(plus + 1);
                v = v.substring(0, plus);
            }

            // Numeric core: digits separated by dots, read until the first other character.
            List<Long> core = new ArrayList<>();
            int i = 0;
            while (i < v.length() && Character.isDigit(v.charAt(i))) {
                int start = i;
                while (i < v.length() && Character.isDigit(v.charAt(i))) {
                    i++;
                }
                core.add(parseLong(v.substring(start, i)));
                if (i < v.length() - 1 && v.charAt(i) == '.' && Character.isDigit(v.charAt(i + 1))) {
                    i++;
                } else {
                    break;
                }
            }
            // Trailing zero segments do not change the version: 1.2 == 1.2.0
            while (core.size() > 1 && core.get(core.size() - 1) == 0L) {
                core.remove(core.size() - 1);
            }

            int preRank = RANK_RELEASE;
            long preNumber = 0;
            long build = -1;

            String qualifier = v.substring(i).toLowerCase(Locale.ROOT);
            List<String> tokens = tokenize(qualifier);
            for (int t = 0; t < tokens.size(); t++) {
                String token = tokens.get(t);
                String next = t + 1 < tokens.size() ? tokens.get(t + 1) : null;
                int rank = preReleaseRank(token);
                if (rank >= 0) {
                    if (rank < preRank) {
                        preRank = rank;
                        preNumber = 0;
                    }
                    if (rank == preRank && next != null && isNumber(next)) {
                        preNumber = parseLong(next);
                        t++;
                    }
                } else if (token.equals("b") || token.equals("build")) {
                    if (next != null && isNumber(next)) {
                        build = Math.max(build, parseLong(next));
                        t++;
                    }
                } else if (isNumber(token)) {
                    // A bare number in the qualifier ("1.0.0-45") is a build number.
                    build = Math.max(build, parseLong(token));
                }
                // Any other word ("bukkit", "paper", "r0") carries no ordering information.
            }

            if (buildPart != null) {
                for (String token : tokenize(buildPart.toLowerCase(Locale.ROOT))) {
                    if (isNumber(token)) {
                        build = Math.max(build, parseLong(token));
                        break;
                    }
                }
            }
            return new Parsed(core, preRank, preNumber, build);
        }

        /** Splits "beta.2-b45" into [beta, 2, b, 45]: words and numbers become separate tokens. */
        private static List<String> tokenize(String s) {
            List<String> tokens = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            Boolean digits = null;
            for (char c : s.toCharArray()) {
                if (!Character.isLetterOrDigit(c)) {
                    flush(tokens, current);
                    digits = null;
                    continue;
                }
                boolean isDigit = Character.isDigit(c);
                if (digits != null && digits != isDigit) {
                    flush(tokens, current);
                }
                current.append(c);
                digits = isDigit;
            }
            flush(tokens, current);
            return tokens;
        }

        private static void flush(List<String> tokens, StringBuilder current) {
            if (!current.isEmpty()) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }

        private static int preReleaseRank(String token) {
            return switch (token) {
                case "snapshot", "dev", "nightly", "experimental" -> RANK_DEV;
                case "alpha", "a" -> RANK_ALPHA;
                case "beta" -> RANK_BETA;
                case "rc", "pre", "preview", "cr" -> RANK_RC;
                case "release", "final", "stable", "ga" -> RANK_RELEASE;
                default -> -1;
            };
        }

        private static boolean isNumber(String s) {
            if (s.isEmpty()) {
                return false;
            }
            for (int k = 0; k < s.length(); k++) {
                if (!Character.isDigit(s.charAt(k))) {
                    return false;
                }
            }
            return true;
        }

        private static long parseLong(String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return Long.MAX_VALUE;
            }
        }

        @Override
        public int compareTo(Parsed other) {
            int size = Math.max(core.size(), other.core.size());
            for (int k = 0; k < size; k++) {
                long x = k < core.size() ? core.get(k) : 0L;
                long y = k < other.core.size() ? other.core.get(k) : 0L;
                if (x != y) {
                    return Long.compare(x, y);
                }
            }
            if (preRank != other.preRank) {
                return Integer.compare(preRank, other.preRank);
            }
            if (preNumber != other.preNumber) {
                return Long.compare(preNumber, other.preNumber);
            }
            return Long.compare(build, other.build);
        }
    }
}
