package com.github.h3yow.tenantapigatewayjava.idempotency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotencyStoreTest {

    private final IdempotencyStore store = new IdempotencyStore();

    @Test
    void absentThenInProgressThenSettledReplay() {
        assertEquals(IdempotencyStore.LookupStatus.ABSENT,
                store.lookup("t1", "k1", "body").status());

        assertTrue(store.markInProgress("t1", "k1", "body"));
        IdempotencyStore.Lookup inProgress = store.lookup("t1", "k1", "body");
        assertEquals(IdempotencyStore.LookupStatus.IN_PROGRESS, inProgress.status());
        assertTrue(inProgress.samePayload());

        store.settle("t1", "k1", "body", "result-1");
        IdempotencyStore.Lookup hit = store.lookup("t1", "k1", "body");
        assertEquals(IdempotencyStore.LookupStatus.SETTLED, hit.status());
        assertEquals("result-1", hit.result());
        assertEquals("result-1", store.settledResult("t1", "k1").orElseThrow());
    }

    @Test
    void onlyOneInProgressMarkerPerKey() {
        assertTrue(store.markInProgress("t1", "k1", "body"));
        assertFalse(store.markInProgress("t1", "k1", "body"));
    }

    @Test
    void differentPayloadIsConflictEvenWhenSettled() {
        store.settle("t1", "k1", "body-A", "r");
        IdempotencyStore.Lookup hit = store.lookup("t1", "k1", "body-B");
        assertEquals(IdempotencyStore.LookupStatus.SETTLED, hit.status());
        assertFalse(hit.samePayload());
    }

    @Test
    void clearInProgressAllowsRetryButKeepsSettledResult() {
        // 进行中占位被清除后可重试
        store.markInProgress("t1", "k1", "body");
        store.clearInProgress("t1", "k1");
        assertEquals(IdempotencyStore.LookupStatus.ABSENT,
                store.lookup("t1", "k1", "body").status());
        assertTrue(store.markInProgress("t1", "k1", "body"));

        // 已落定终态绝不被清除
        store.settle("t1", "k2", "body", "final");
        store.clearInProgress("t1", "k2");
        assertEquals("final", store.settledResult("t1", "k2").orElseThrow());
    }

    @Test
    void idempotencyKeysAreIsolatedPerTenant() {
        store.settle("t1", "k1", "body", "r1");
        assertEquals(IdempotencyStore.LookupStatus.ABSENT,
                store.lookup("t2", "k1", "body").status());
    }
}
