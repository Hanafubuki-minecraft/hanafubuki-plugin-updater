package com.hanafubuki.updater.util;

import java.util.regex.Pattern;

/**
 * File name globs: {@code *} matches any text, {@code ?} one character, everything else is literal.
 * Matching is case-insensitive and covers the whole name.
 */
public final class Glob {

    private Glob() {
    }

    public static Pattern toPattern(String glob) {
        if (glob == null || glob.isEmpty()) {
            throw new IllegalArgumentException("Glob pattern is empty");
        }
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public static boolean matches(String glob, String name) {
        return toPattern(glob).matcher(name).matches();
    }
}
