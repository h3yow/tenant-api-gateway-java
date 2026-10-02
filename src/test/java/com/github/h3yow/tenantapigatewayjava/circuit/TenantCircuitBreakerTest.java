package com.github.h3yow.tenantapigatewayjava.circuit;

import com.github.h3yow.tenantapigatewayjava.core.MutableTimeSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantCircuitBreakerTest {

    private final MutableTimeSource clock = new MutableTimeSource();
    /** 阈值 3 次连续失败，冷静期 10s，探测名额 1。 */
    private final TenantCircuitBreaker breaker =
            new TenantCircuitBreaker("t1", new CircuitBreakerConfig(3, 10_000L, 1, 2_000L), clock);

    private void failClosed(int times) {
        for (int i = 0; i < times; i++) {
            breaker.recordFailure(false);
        }
    }

    @Test
    void staysClosedBelowThresholdAndRecoversCountOnSuccess() {
        failClosed(2);
        assertEquals(CircuitState.CLOSED, breaker.currentState());
        assertTrue(breaker.admit().permitted());

        breaker.recordSuccess(false);
        // 成功后连续失败计数归零，再来 2 次失败不应熔断
        failClosed(2);
        assertEquals(CircuitState.CLOSED, breaker.currentState());
        assertTrue(breaker.admit().permitted());
    }

    @Test
    void opensAfterThresholdAndFastFailsDuringCooldown() {
        failClosed(3);
        assertEquals(CircuitState.OPEN, breaker.currentState());

        Admission rejected = breaker.admit();
        assertFalse(rejected.permitted());
        assertFalse(rejected.probe());
        assertFalse(rejected.probeFailurePenalty());
        assertEquals(10_000L, rejected.retryAfterMillis());

        // 冷静期内任意时刻都继续快速失败，不消耗探测名额
        clock.advance(5_000L);
        assertFalse(breaker.admit().permitted());
        assertEquals(5_000L, breaker.admit().retryAfterMillis());
    }

    @Test
    void allowsExactlyOneProbeAfterCooldownAndBlocksRest() {
        failClosed(3);
        clock.advance(10_000L);

        Admission probe = breaker.admit();
        assertTrue(probe.permitted());
        assertTrue(probe.probe());
        assertEquals(CircuitState.HALF_OPEN, breaker.currentState());

        // 探测在途期间，后续并发请求不能一瞬间穿透
        Admission second = breaker.admit();
        assertFalse(second.permitted());
        assertFalse(second.probe());

        Admission third = breaker.admit();
        assertFalse(third.permitted());
    }

    @Test
    void probeSuccessClosesAndResumesNormalTraffic() {
        failClosed(3);
        clock.advance(10_000L);

        Admission probe = breaker.admit();
        breaker.recordSuccess(probe.probe());
        assertEquals(CircuitState.CLOSED, breaker.currentState());

        // 整体恢复：连续放两个普通请求都应通过，且不再是探测
        Admission a = breaker.admit();
        Admission b = breaker.admit();
        assertTrue(a.permitted() && b.permitted());
        assertFalse(a.probe() || b.probe());
    }

    @Test
    void probeFailureReopensAndRestartsCooldownWithPenaltyFlag() {
        failClosed(3);
        clock.advance(10_000L);

        Admission probe = breaker.admit();
        assertTrue(probe.probe());
        breaker.recordFailure(probe.probe());
        assertEquals(CircuitState.OPEN, breaker.currentState());

        // 重新计时：冷静期从头算
        assertFalse(breaker.admit().permitted());
        assertEquals(10_000L, breaker.admit().retryAfterMillis());

        // 冷静期内被挡下的请求带上“探测失败”惩罚标记，供审计区分
        assertTrue(breaker.admit().probeFailurePenalty());

        // 再过完整冷静期才能再探测
        clock.advance(9_999L);
        assertFalse(breaker.admit().permitted());
        clock.advance(1L);
        Admission nextProbe = breaker.admit();
        assertTrue(nextProbe.permitted());
        assertTrue(nextProbe.probe());
    }

    @Test
    void repeatedFailingProbesKeepCircuitOpenWithoutFlapping() {
        failClosed(3);
        for (int round = 0; round < 3; round++) {
            clock.advance(10_000L);
            Admission probe = breaker.admit();
            assertTrue(probe.probe());
            breaker.recordFailure(true);
            assertEquals(CircuitState.OPEN, breaker.currentState());
        }
        // 一直没恢复，普通请求始终被挡
        assertFalse(breaker.admit().permitted());
    }

    @Test
    void multipleProbePermitsCapConcurrentProbes() {
        TenantCircuitBreaker multi = new TenantCircuitBreaker(
                "t2", new CircuitBreakerConfig(1, 10_000L, 2, 2_000L), clock);
        multi.recordFailure(false);
        clock.advance(10_000L);

        Admission p1 = multi.admit();
        Admission p2 = multi.admit();
        assertTrue(p1.probe() && p2.probe());

        // 名额用尽后，即使还在半开窗口也不能继续穿透
        Admission extra = multi.admit();
        assertFalse(extra.permitted());

        // 任一探测成功即整体恢复
        multi.recordSuccess(true);
        assertEquals(CircuitState.CLOSED, multi.currentState());
        assertTrue(multi.admit().permitted());
    }

    @Test
    void failureWhileHalfOpenReopensImmediately() {
        // 2 个探测名额：第一个探测成功、第二个失败时不应误判为整体恢复
        TenantCircuitBreaker multi = new TenantCircuitBreaker(
                "t3", new CircuitBreakerConfig(1, 10_000L, 2, 2_000L), clock);
        multi.recordFailure(false);
        clock.advance(10_000L);
        Admission p1 = multi.admit();
        Admission p2 = multi.admit();
        assertTrue(p1.probe() && p2.probe());

        multi.recordFailure(true);
        assertEquals(CircuitState.OPEN, multi.currentState());
        // 第二个在途探测即使回报成功，也不能把已经因失败重新打开的熔断器恢复
        multi.recordSuccess(true);
        assertEquals(CircuitState.OPEN, multi.currentState());
    }

    @Test
    void independentBreakersDoNotShareState() {
        TenantCircuitBreaker other = new TenantCircuitBreaker(
                "t-other", new CircuitBreakerConfig(3, 10_000L, 1, 2_000L), clock);
        failClosed(3);
        assertEquals(CircuitState.OPEN, breaker.currentState());
        assertEquals(CircuitState.CLOSED, other.currentState());
        assertTrue(other.admit().permitted());
    }
}
