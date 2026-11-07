package com.hanafubuki.updater.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

class HttpCacheTest {

    private static URI uri(int i) {
        return URI.create("https://example.com/" + i);
    }

    private static HttpCache.Entry body(int chars) {
        return new HttpCache.Entry("\"e\"", null, "x".repeat(chars));
    }

    @Test
    void keepsAtMostMaxEntriesLeastRecentlyUsedFirst() {
        HttpCache cache = HttpCache.inMemory(2, 1_000_000);
        cache.put(uri(1), body(1));
        cache.put(uri(2), body(1));
        cache.get(uri(1));
        cache.put(uri(3), body(1));
        assertTrue(cache.get(uri(1)).isPresent());
        assertTrue(cache.get(uri(2)).isEmpty(), "least recently used is evicted");
        assertTrue(cache.get(uri(3)).isPresent());
    }

    @Test
    void totalSizeIsBounded() {
        HttpCache cache = HttpCache.inMemory(100, 10_000);
        for (int i = 0; i < 10; i++) {
            cache.put(uri(i), body(2_000));
        }
        int kept = 0;
        for (int i = 0; i < 10; i++) {
            kept += cache.get(uri(i)).isPresent() ? 1 : 0;
        }
        assertEquals(4, kept, "4 x (2000 + 64) fits into 10000, 5 do not");
        assertTrue(cache.get(uri(9)).isPresent(), "newest is kept");
    }

    @Test
    void oversizedBodiesAreNotCachedAndReplaceTheOldEntry() {
        HttpCache cache = HttpCache.inMemory(100, 10_000);
        cache.put(uri(1), body(10));
        cache.put(uri(1), body(5_000));
        assertTrue(cache.get(uri(1)).isEmpty(), "too big for a quarter of the budget; the stale entry is gone too");
        cache.put(uri(2), body(2_000));
        assertEquals(2_000, cache.get(uri(2)).orElseThrow().body().length());
    }

    @Test
    void replacingAnEntryDoesNotLeakBudget() {
        HttpCache cache = HttpCache.inMemory(100, 10_000);
        for (int i = 0; i < 1_000; i++) {
            cache.put(uri(1), body(2_000));
        }
        cache.put(uri(2), body(2_000));
        cache.put(uri(3), body(2_000));
        cache.put(uri(4), body(2_000));
        assertTrue(cache.get(uri(1)).isPresent());
        assertTrue(cache.get(uri(4)).isPresent());
    }
}
