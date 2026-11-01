package com.hanafubuki.updater.http;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Collections;
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

    /** In-memory cache that keeps the {@code maxEntries} most recently used responses. */
    static HttpCache inMemory(int maxEntries) {
        Map<String, Entry> map = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > maxEntries;
            }
        });
        return new HttpCache() {
            @Override
            public Optional<Entry> get(URI uri) {
                return Optional.ofNullable(map.get(uri.toString()));
            }

            @Override
            public void put(URI uri, Entry entry) {
                map.put(uri.toString(), entry);
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
