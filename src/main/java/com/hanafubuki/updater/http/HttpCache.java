package com.hanafubuki.updater.http;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * Stores the last successful response of a GET request together with its validators
 * (ETag / Last-Modified). On the next request the validators are sent and a
 * "304 Not Modified" answer is served from here. GitHub does not count 304 answers
 * against the API rate limit.
 */
public interface HttpCache {

    /** A cached response. */
    record Entry(String etag, String lastModified, String body) {
    }

    Optional<Entry> get(URI uri);

    void put(URI uri, Entry entry);

    /** Default memory budget of {@link #inMemory(int)}: about 8 MB of response text. */
    long DEFAULT_MAX_CHARS = 8L * 1024 * 1024;

    /** In-memory cache of the {@code maxEntries} most recently used responses, within {@link #DEFAULT_MAX_CHARS}. */
    static HttpCache inMemory(int maxEntries) {
        return inMemory(maxEntries, DEFAULT_MAX_CHARS);
    }

    /**
     * In-memory LRU cache limited by entry count and by the total length of the cached bodies,
     * so a few large version lists cannot fill the server's memory. A body longer than a quarter
     * of the budget is not cached.
     */
    static HttpCache inMemory(int maxEntries, long maxChars) {
        long maxEntryChars = Math.max(1, maxChars / 4);
        LinkedHashMap<String, Entry> map = new LinkedHashMap<>(16, 0.75f, true);
        return new HttpCache() {
            private long chars;

            @Override
            public synchronized Optional<Entry> get(URI uri) {
                return Optional.ofNullable(map.get(uri.toString()));
            }

            @Override
            public synchronized void put(URI uri, Entry entry) {
                String key = uri.toString();
                Entry old = map.remove(key);
                if (old != null) {
                    chars -= size(old);
                }
                if (entry == null || size(entry) > maxEntryChars) {
                    return;
                }
                map.put(key, entry);
                chars += size(entry);
                Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
                while ((map.size() > maxEntries || chars > maxChars) && it.hasNext()) {
                    chars -= size(it.next().getValue());
                    it.remove();
                }
            }

            private static long size(Entry e) {
                return (e.body() == null ? 0 : e.body().length()) + 64;
            }
        };
    }

    /** A cache that stores nothing. */
    static HttpCache none() {
        return new HttpCache() {
            @Override
            public Optional<Entry> get(URI uri) {
                return Optional.empty();
            }

            @Override
            public void put(URI uri, Entry entry) {
            }
        };
    }
}
