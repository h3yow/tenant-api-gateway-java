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

        Admission second = breaker.admit();
        assertFalse(second.permitted());
        assertFalse(second.probe());
        assertEquals(2_000L, second.retryAfterMillis());

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

        assertFalse(breaker.admit().permitted());
        assertEquals(10_000L, breaker.admit().retryAfterMillis());
        assertTrue(breaker.admit().probeFailurePenalty());

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

        Admission extra = multi.admit();
        assertFalse(extra.permitted());

        multi.recordSuccess(true);
        assertEquals(CircuitState.CLOSED, multi.currentState());
        assertTrue(multi.admit().permitted());
    }

    @Test
    void failureWhileHalfOpenReopensImmediatelyAndLateResultsAreIgnored() {
        // 2 个探测名额：第一个探测失败立即重新 OPEN，迟到的第二个探测无论成败都不能改变状态
        TenantCircuitBreaker multi = new TenantCircuitBreaker(
                "t3", new CircuitBreakerConfig(1, 10_000L, 2, 2_000L), clock);
        multi.recordFailure(false);
        clock.advance(10_000L);
        Admission p1 = multi.admit();
        Admission p2 = multi.admit();
        assertTrue(p1.probe() && p2.probe());

        multi.recordFailure(true);
        assertEquals(CircuitState.OPEN, multi.currentState());

        // 迟到的成功结果必须被忽略，熔断器不能被“救回”半开或关闭
        multi.recordSuccess(true);
        assertEquals(CircuitState.OPEN, multi.currentState());
        // 冷静期从头计算：此刻不允许探测
        assertFalse(multi.admit().permitted());
        assertEquals(10_000L, multi.admit().retryAfterMillis());

        // 迟到的失败结果同样被忽略，不重复重置计时之外也不产生额外副作用
        multi.recordFailure(true);
        clock.advance(9_999L);
        assertFalse(multi.admit().permitted());
        clock.advance(1L);
        assertTrue(multi.admit().probe());
    }

    @Test
    void firstProbeFailsButNextRoundProbeSucceedsRecovers() {
        // 失败 -> 重新计时 -> 下一轮探测成功，确保失败后仍能自动恢复
        failClosed(3);
        clock.advance(10_000L);
        breaker.recordFailure(breaker.admit().probe());
        assertEquals(CircuitState.OPEN, breaker.currentState());

        clock.advance(10_000L);
        Admission probe = breaker.admit();
        assertTrue(probe.probe());
        breaker.recordSuccess(true);
        assertEquals(CircuitState.CLOSED, breaker.currentState());
        assertFalse(breaker.admit().probe());
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
