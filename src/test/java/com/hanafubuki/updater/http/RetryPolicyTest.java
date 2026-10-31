package com.hanafubuki.updater.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void constants() {
        assertEquals(new RetryPolicy(3, 1000, 10_000), RetryPolicy.DEFAULT);
        assertEquals(0, RetryPolicy.NONE.maxRetries());
        assertEquals(0, RetryPolicy.NONE.delayMs(0));
    }

    @Test
    void maxRetriesMustBeBetweenZeroAndTen() {
        new RetryPolicy(0, 0, 0);
        new RetryPolicy(10, 0, 0);
        IllegalArgumentException low = assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(-1, 0, 0));
        assertTrue(low.getMessage().contains("-1"), low.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(11, 0, 0));
    }

    @Test
    void negativeDelaysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 0, -1));
    }

    @Test
    void maxDelayBelowBaseIsRaisedToBase() {
        RetryPolicy p = new RetryPolicy(3, 5000, 100);
        assertEquals(5000, p.maxDelayMs());
        assertEquals(5000, p.delayMs(0));
        assertEquals(5000, p.delayMs(4));
    }

    @Test
    void zeroBaseDelayNeverWaits() {
        RetryPolicy p = new RetryPolicy(5, 0, 10_000);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals(0, p.delayMs(attempt));
        }
    }

    @RepeatedTest(20)
    void delaysGrowExponentiallyWithBoundedJitter() {
        RetryPolicy p = new RetryPolicy(10, 1000, 10_000);
        long d0 = p.delayMs(0);
        long d1 = p.delayMs(1);
        long d2 = p.delayMs(2);
        assertTrue(d0 >= 1000 && d0 <= 2000, "attempt 0: " + d0);
        assertTrue(d1 >= 2000 && d1 <= 3000, "attempt 1: " + d1);
        assertTrue(d2 >= 4000 && d2 <= 5000, "attempt 2: " + d2);
        // 8000 + jitter may reach the cap
        long d3 = p.delayMs(3);
        assertTrue(d3 >= 8000 && d3 <= 9000, "attempt 3: " + d3);
        assertEquals(10_000, p.delayMs(4));
    }

    @RepeatedTest(5)
    void jitterIsAtMostOneSecondForLargeBases() {
        RetryPolicy p = new RetryPolicy(3, 20_000, 300_000);
        long d = p.delayMs(0);
        assertTrue(d >= 20_000 && d <= 21_000, "delay " + d);
    }

    @Test
    void hugeAttemptNumbersDoNotOverflow() {
        RetryPolicy p = new RetryPolicy(10, 60_000, 300_000);
        assertEquals(300_000, p.delayMs(30));
        assertEquals(300_000, p.delayMs(Integer.MAX_VALUE));
    }
}
