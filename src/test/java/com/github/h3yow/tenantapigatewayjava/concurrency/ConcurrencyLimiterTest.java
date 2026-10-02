package com.github.h3yow.tenantapigatewayjava.concurrency;

import com.github.h3yow.tenantapigatewayjava.core.MutableTimeSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyLimiterTest {

    private final ConcurrencyLimiter limiter = new ConcurrencyLimiter(new MutableTimeSource());

    @Test
    void limitsInflightPerTenantAndReleases() {
        assertTrue(limiter.tryAcquire("t1", 2));
        assertTrue(limiter.tryAcquire("t1", 2));
        assertFalse(limiter.tryAcquire("t1", 2));

        limiter.release("t1");
        assertTrue(limiter.tryAcquire("t1", 2));
    }

    @Test
    void concurrencyIsIsolatedPerTenant() {
        assertTrue(limiter.tryAcquire("t1", 1));
        assertFalse(limiter.tryAcquire("t1", 1));
        // t2 不受 t1 影响
        assertTrue(limiter.tryAcquire("t2", 1));
    }
}
