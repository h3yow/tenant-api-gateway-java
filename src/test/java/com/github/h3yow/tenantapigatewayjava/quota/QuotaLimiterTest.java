package com.github.h3yow.tenantapigatewayjava.quota;

import com.github.h3yow.tenantapigatewayjava.core.MutableTimeSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotaLimiterTest {

    private final MutableTimeSource clock = new MutableTimeSource();
    private final QuotaLimiter limiter = new QuotaLimiter(clock);

    @Test
    void allowsUpToWindowLimitThenRejects() {
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("t1", 60, 3));
        }
        assertFalse(limiter.tryAcquire("t1", 60, 3));
    }

    @Test
    void resetsInNewWindow() {
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("t1", 60, 3));
        }
        assertFalse(limiter.tryAcquire("t1", 60, 3));

        clock.advance(60_000L);
        assertTrue(limiter.tryAcquire("t1", 60, 3));
    }

    @Test
    void windowsAreIsolatedPerTenant() {
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("t1", 60, 3));
        }
        assertFalse(limiter.tryAcquire("t1", 60, 3));
        assertTrue(limiter.tryAcquire("t2", 60, 3));
    }

    @Test
    void releaseReturnsSlotWithinCurrentWindow() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("t1", 60, 3);
        }
        assertFalse(limiter.tryAcquire("t1", 60, 3));
        limiter.release("t1", 60);
        assertTrue(limiter.tryAcquire("t1", 60, 3));
    }

    @Test
    void releaseInNewWindowDoesNotCorruptCount() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("t1", 60, 3);
        }
        clock.advance(60_000L);
        limiter.release("t1", 60);
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("t1", 60, 3));
        }
        assertFalse(limiter.tryAcquire("t1", 60, 3));
    }
}
