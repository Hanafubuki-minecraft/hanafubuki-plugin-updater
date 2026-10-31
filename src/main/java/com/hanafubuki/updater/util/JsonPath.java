package com.hanafubuki.updater.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A small JSONPath subset for the custom HTTP source. Supported syntax:
 * <ul>
 *   <li>{@code $} - the root (optional at the start)</li>
 *   <li>{@code .field} or {@code ['field']} / {@code ["field"]} - object member</li>
 *   <li>{@code [0]}, {@code [-1]} - array element (negative counts from the end)</li>
 * </ul>
 * Example: {@code $.assets[0].browser_download_url}
 */
public final class JsonPath {

    private final List<Object> segments;
    private final String expression;

    private JsonPath(String expression, List<Object> segments) {
        this.expression = expression;
        this.segments = segments;
    }

    /**
     * Compiles an expression.
     *
     * @throws IllegalArgumentException with a readable message if the syntax is invalid
     */
    public static JsonPath compile(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("JSON path is empty");
        }
        String e = expression.trim();
        List<Object> segments = new ArrayList<>();
        int i = 0;
        if (e.startsWith("$")) {
            i = 1;
        } else if (!e.startsWith(".") && !e.startsWith("[")) {
            e = "." + e;
        }
        while (i < e.length()) {
            char c = e.charAt(i);
            if (c == '.') {
                int start = ++i;
                while (i < e.length() && e.charAt(i) != '.' && e.charAt(i) != '[') {
                    i++;
                }
                if (start == i) {
                    throw new IllegalArgumentException("Empty field name at position " + start + " in '" + expression + "'");
                }
                segments.add(e.substring(start, i));
            } else if (c == '[') {
                int close = e.indexOf(']', i);
                if (close < 0) {
                    throw new IllegalArgumentException("Missing ']' in '" + expression + "'");
                }
                String inner = e.substring(i + 1, close).trim();
                if (inner.length() >= 2 && (inner.startsWith("'") && inner.endsWith("'")
                        || inner.startsWith("\"") && inner.endsWith("\""))) {
                    segments.add(inner.substring(1, inner.length() - 1));
                } else {
                    try {
                        segments.add(Integer.parseInt(inner));
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("Unsupported selector [" + inner + "] in '" + expression
                                + "' (use [number] or ['name'])");
                    }
                }
                i = close + 1;
            } else {
                throw new IllegalArgumentException("Unexpected '" + c + "' at position " + i + " in '" + expression + "'");
            }
        }
        return new JsonPath(expression, List.copyOf(segments));
    }

    /** Evaluates the path. Empty if any step is missing or has the wrong type. */
    public Optional<JsonElement> read(JsonElement root) {
        JsonElement current = root;
        for (Object segment : segments) {
            if (current == null || current.isJsonNull()) {
                return Optional.empty();
            }
            if (segment instanceof String field) {
                if (!current.isJsonObject()) {
                    return Optional.empty();
                }
                JsonObject obj = current.getAsJsonObject();
                current = obj.get(field);
            } else {
                if (!current.isJsonArray()) {
                    return Optional.empty();
                }
                JsonArray arr = current.getAsJsonArray();
                int index = (Integer) segment;
                if (index < 0) {
                    index = arr.size() + index;
                }
                if (index < 0 || index >= arr.size()) {
                    return Optional.empty();
                }
                current = arr.get(index);
            }
        }
        return current == null || current.isJsonNull() ? Optional.empty() : Optional.of(current);
    }

    /** Evaluates the path and returns a primitive value as text. */
    public Optional<String> readString(JsonElement root) {
        return read(root).filter(JsonElement::isJsonPrimitive).map(JsonElement::getAsString);
    }

    @Override
    public String toString() {
        return expression;
    }
}
