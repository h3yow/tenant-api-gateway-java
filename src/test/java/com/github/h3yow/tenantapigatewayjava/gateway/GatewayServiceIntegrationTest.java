package com.github.h3yow.tenantapigatewayjava.gateway;

import com.github.h3yow.tenantapigatewayjava.circuit.CircuitBreakerRegistry;
import com.github.h3yow.tenantapigatewayjava.concurrency.ConcurrencyLimiter;
import com.github.h3yow.tenantapigatewayjava.core.MutableTimeSource;
import com.github.h3yow.tenantapigatewayjava.model.DecisionCode;
import com.github.h3yow.tenantapigatewayjava.model.GatewayRequest;
import com.github.h3yow.tenantapigatewayjava.model.GatewayResponse;
import com.github.h3yow.tenantapigatewayjava.quota.QuotaLimiter;
import com.github.h3yow.tenantapigatewayjava.upstream.UpstreamExceptionNormalizer;
import com.github.h3yow.tenantapigatewayjava.upstream.UpstreamInvoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关编排集成测试：熔断故障隔离/探测 + 配额/并发/幂等/异常归一化回归。
 * 直接手工装配各组件，用可控时钟驱动窗口与冷静期，用可切换的上游桩制造成功/失败。
 */
class GatewayServiceIntegrationTest {

    private MutableTimeSource clock;
    private GatewayProperties props;
    private QuotaLimiter quota;
    private ConcurrencyLimiter concurrency;
    private com.github.h3yow.tenantapigatewayjava.idempotency.IdempotencyStore idempotency;
    private AtomicInteger upstreamCalls;
    private java.util.Set<String> failingTenants = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile RuntimeException upstreamError;
    private GatewayService gateway;

    private GatewayRequest req(String tenant, String key, String payload, String data) {
        return new GatewayRequest(tenant, key, payload, data);
    }

    @BeforeEach
    void setUp() {
        clock = new MutableTimeSource(60_000L);
        props = new GatewayProperties();
        props.setWindowSeconds(60);
        props.setWindowLimit(100);
        props.setMaxConcurrent(20);
        props.setFailureThreshold(3);
        props.setOpenDurationMillis(10_000L);
        props.setProbePermits(1);
        props.setProbeWaitMillis(2_000L);

        quota = new QuotaLimiter(clock);
        concurrency = new ConcurrencyLimiter(clock);
        idempotency = new com.github.h3yow.tenantapigatewayjava.idempotency.IdempotencyStore();
        upstreamCalls = new AtomicInteger();
        upstreamError = new RuntimeException("secret-upstream-host timed out");

        UpstreamInvoker invoker = (tenantId, data) -> {
            upstreamCalls.incrementAndGet();
            if (failingTenants.contains(tenantId)) {
                throw upstreamError;
            }
            return "result:" + data;
        };

        CircuitBreakerRegistry registry = new CircuitBreakerRegistry(clock, props);
        gateway = new GatewayService(quota, concurrency, idempotency, invoker,
                new UpstreamExceptionNormalizer(), registry, props);
    }

    // ---------- 基础：正常放行 ----------

    @Test
    void normalRequestPassesAndReachesUpstream() {
        GatewayResponse resp = gateway.handle(req("t1", null, null, "d1"));
        assertEquals(DecisionCode.PASS, resp.decision());
        assertEquals("OK", resp.reason());
        assertFalse(resp.retryable());
        assertEquals("result:d1", resp.result());
        assertEquals(1, upstreamCalls.get());
    }

    // ---------- 连续失败 -> 打开 -> 快速失败 ----------

    @Test
    void opensAfterConsecutiveFailuresAndFastFailsWithoutHittingUpstream() {
        failingTenants.add("t1");
        for (int i = 0; i < 3; i++) {
            GatewayResponse r = gateway.handle(req("t1", null, null, "d" + i));
            assertEquals(DecisionCode.UPSTREAM_ERROR, r.decision());
            assertTrue(r.retryable());
        }

        // 第 4 个请求被熔断快速失败，不再打上游
        int callsBefore = upstreamCalls.get();
        GatewayResponse rejected = gateway.handle(req("t1", null, null, "d3"));
        assertEquals(DecisionCode.REJECT_CIRCUIT_OPEN, rejected.decision());
        assertEquals("CIRCUIT_OPEN", rejected.reason());
        assertTrue(rejected.retryable());
        assertEquals(10L, rejected.retryAfterSeconds());
        assertNull(rejected.result());
        assertEquals(callsBefore, upstreamCalls.get());
        // 原始上游异常信息不得出现在响应中
        assertFalse(String.valueOf(rejected).contains("secret-upstream-host"));
    }

    @Test
    void fastFailDoesNotConsumeWindowQuota() {
        props.setWindowLimit(3);
        failingTenants.add("t1");
        // 恰好 3 次真正的上游调用：打满本窗口配额，同时达到熔断阈值
        for (int i = 0; i < 3; i++) {
            gateway.handle(req("t1", null, null, "d" + i));
        }
        // 熔断打开期间，即使配额已满，被挡请求也只报熔断、不额外消耗名额
        for (int i = 0; i < 5; i++) {
            GatewayResponse blocked = gateway.handle(req("t1", null, null, "blocked-" + i));
            assertEquals(DecisionCode.REJECT_CIRCUIT_OPEN, blocked.decision());
        }

        // 推进到下一个配额窗口且冷静期结束，上游恢复
        clock.advance(60_000L);
        failingTenants.clear();
        GatewayResponse probe = gateway.handle(req("t1", null, null, "probe"));
        assertEquals(DecisionCode.PASS_PROBE, probe.decision());
        assertEquals("result:probe", probe.result());

        // 探测占新窗口 1 个名额，后续仍可放行 2 个普通请求，第 3 个才配额见底
        assertEquals(DecisionCode.PASS, gateway.handle(req("t1", null, null, "a")).decision());
        assertEquals(DecisionCode.PASS, gateway.handle(req("t1", null, null, "b")).decision());
        assertEquals(DecisionCode.REJECT_QUOTA_EXHAUSTED,
                gateway.handle(req("t1", null, null, "c")).decision());
    }

    @Test
    void fastFailRequestsAreNotCountedAgainstNextWindowLimit() {
        props.setWindowLimit(3);
        failingTenants.add("t1");
        gateway.handle(req("t1", null, null, "d0"));
        gateway.handle(req("t1", null, null, "d1"));
        gateway.handle(req("t1", null, null, "d2"));
        for (int i = 0; i < 50; i++) {
            assertEquals(DecisionCode.REJECT_CIRCUIT_OPEN,
                    gateway.handle(req("t1", null, null, "b" + i)).decision());
        }
        clock.advance(60_000L);
        failingTenants.clear();
        // 新窗口：探测 + 2 个普通请求通过，第 3 个普通请求才配额见底（证明被挡的 50 个没占名额）
        assertEquals(DecisionCode.PASS_PROBE, gateway.handle(req("t1", null, null, "probe")).decision());
        assertEquals(DecisionCode.PASS, gateway.handle(req("t1", null, null, "a")).decision());
        assertEquals(DecisionCode.PASS, gateway.handle(req("t1", null, null, "b")).decision());
        assertEquals(DecisionCode.REJECT_QUOTA_EXHAUSTED,
                gateway.handle(req("t1", null, null, "c")).decision());
    }

    // ---------- 幂等折叠在熔断打开期间仍然有效 ----------

    @Test
    void settledIdempotentWriteStillReplaysWhileCircuitOpen() {
        // 先成功落定一个带幂等键的写请求
        GatewayResponse first = gateway.handle(req("t1", "k1", "payload-A", "data-A"));
        assertEquals(DecisionCode.PASS, first.decision());
        assertEquals("result:data-A", first.result());

        // 打挂该租户上游并打开熔断
        failingTenants.add("t1");
        for (int i = 0; i < 3; i++) {
            gateway.handle(req("t1", null, null, "noise" + i));
        }
        int callsBefore = upstreamCalls.get();

        // 同键同内容回来：必须折叠重放，不被熔断挡下，也不打上游
        GatewayResponse replay = gateway.handle(req("t1", "k1", "payload-A", "data-A"));
        assertEquals(DecisionCode.IDEMPOTENT_REPLAY, replay.decision());
        assertEquals("result:data-A", replay.result());
        assertEquals(callsBefore, upstreamCalls.get());

        // 同键不同内容即使在熔断期间也要按幂等冲突拒绝（同样不打上游）
        GatewayResponse conflict = gateway.handle(req("t1", "k1", "payload-B", "data-B"));
        assertEquals(DecisionCode.REJECT_IDEMPOTENT_CONFLICT, conflict.decision());
        assertEquals(callsBefore, upstreamCalls.get());

        // 没有幂等终态的新写请求仍然被熔断快速失败
        GatewayResponse blocked = gateway.handle(req("t1", "k-new", "p", "d"));
        assertEquals(DecisionCode.REJECT_CIRCUIT_OPEN, blocked.decision());
        assertEquals(callsBefore, upstreamCalls.get());
    }

    // ---------- 冷静期后探测：成功恢复 / 失败继续 ----------

    @Test
    void probeSuccessAfterCooldownReopensAllTraffic() {
        failingTenants.add("t1");
        for (int i = 0; i < 3; i++) {
            gateway.handle(req("t1", null, null, "d" + i));
        }
        clock.advance(10_000L);
        failingTenants.clear();

        GatewayResponse probe = gateway.handle(req("t1", null, null, "probe"));
        assertEquals(DecisionCode.PASS_PROBE, probe.decision());
        assertEquals("PASS_PROBE", probe.reason());
        assertFalse(probe.retryable());

        GatewayResponse normal = gateway.handle(req("t1", null, null, "after"));
        assertEquals(DecisionCode.PASS, normal.decision());
    }

    @Test
    void probeFailureKeepsCircuitOpenWithDistinctReasonAndRestartsCooldown() {
        failingTenants.add("t1");
        for (int i = 0; i < 3; i++) {
            gateway.handle(req("t1", null, null, "d" + i));
        }
        clock.advance(10_000L);

        GatewayResponse probe = gateway.handle(req("t1", null, null, "probe"));
        assertEquals(DecisionCode.REJECT_PROBE_FAILED, probe.decision());
        assertEquals("CIRCUIT_PROBE_FAILED", probe.reason());
        assertTrue(probe.retryable());
        assertEquals(10L, probe.retryAfterSeconds());
        assertFalse(String.valueOf(probe).contains("secret-upstream-host"));

        // 紧跟的请求在新冷静期内被挡，原因是“探测失败”，与首次熔断、配额、并发都可区分
        GatewayResponse stillBlocked = gateway.handle(req("t1", null, null, "x"));
        assertEquals(DecisionCode.REJECT_PROBE_FAILED, stillBlocked.decision());
        assertEquals(10L, stillBlocked.retryAfterSeconds());

        // 冷静期未到，即使上游已恢复也不能再探测
        failingTenants.clear();
        clock.advance(9_999L);
        assertEquals(DecisionCode.REJECT_PROBE_FAILED, gateway.handle(req("t1", null, null, "y")).decision());

        // 重新计满冷静期后探测成功才恢复
        clock.advance(1L);
        GatewayResponse nextProbe = gateway.handle(req("t1", null, null, "probe2"));
        assertEquals(DecisionCode.PASS_PROBE, nextProbe.decision());
        assertEquals(DecisionCode.PASS, gateway.handle(req("t1", null, null, "z")).decision());
    }

    @Test
    void floodDuringCooldownNeverPenetratesToUpstream() {
        failingTenants.add("t1");
        for (int i = 0; i < 3; i++) {
            gateway.handle(req("t1", null, null, "d" + i));
        }

        // 冷静期内涌入：全部快速失败，没有任何请求到达上游
        int callsAtOpen = upstreamCalls.get();
        for (int i = 0; i < 20; i++) {
            assertEquals(DecisionCode.REJECT_CIRCUIT_OPEN,
                    gateway.handle(req("t1", null, null, "flood-a" + i)).decision());
        }
        assertEquals(callsAtOpen, upstreamCalls.get());

        // 冷静期到期的一瞬间涌入：只有 1 个请求能抢到探测名额真正调上游；
        // 它失败后其余请求全部落在“探测失败”惩罚窗口中被挡，无一穿透。
        clock.advance(10_000L);
        int callsAtCooldown = upstreamCalls.get();
        int executedProbes = 0;
        int blocked = 0;
        for (int i = 0; i < 20; i++) {
            int before = upstreamCalls.get();
            GatewayResponse r = gateway.handle(req("t1", null, null, "flood-b" + i));
            if (upstreamCalls.get() == before + 1) {
                executedProbes++;
                assertEquals(DecisionCode.REJECT_PROBE_FAILED, r.decision());
            } else {
                blocked++;
                assertEquals(DecisionCode.REJECT_PROBE_FAILED, r.decision());
                assertEquals(10L, r.retryAfterSeconds());
            }
        }
        assertEquals(1, executedProbes, "exactly one request may reach the upstream as probe");
        assertEquals(19, blocked);
        assertEquals(callsAtCooldown + 1, upstreamCalls.get());
    }

    @Test
    void successfulProbeClosesFullyAndFloodThenAllPasses() {
        failingTenants.add("t1");
        for (int i = 0; i < 3; i++) {
            gateway.handle(req("t1", null, null, "d" + i));
        }
        clock.advance(10_000L);
        failingTenants.clear();

        // 探测成功，整体恢复；之后一批请求全部正常放行（不再是探测判定）
        assertEquals(DecisionCode.PASS_PROBE, gateway.handle(req("t1", null, null, "probe")).decision());
        for (int i = 0; i < 5; i++) {
            GatewayResponse r = gateway.handle(req("t1", null, null, "after" + i));
            assertEquals(DecisionCode.PASS, r.decision());
            assertEquals("OK", r.reason());
        }
    }

    // ---------- 租户隔离 ----------

    @Test
    void failureOfOneTenantDoesNotAffectOthers() {
        failingTenants.add("bad-tenant");
        for (int i = 0; i < 3; i++) {
            gateway.handle(req("bad-tenant", null, null, "d" + i));
        }
        assertEquals(DecisionCode.REJECT_CIRCUIT_OPEN,
                gateway.handle(req("bad-tenant", null, null, "x")).decision());

        // 健康租户完全不受影响
        GatewayResponse healthy = gateway.handle(req("good-tenant", null, null, "d"));
        assertEquals(DecisionCode.PASS, healthy.decision());
        assertEquals("result:d", healthy.result());
    }

    // ---------- 回归：配额 ----------

    @Test
    void quotaExhaustionStillRejectsWithItsOwnReason() {
        props.setWindowLimit(2);
        assertEquals(DecisionCode.PASS, gateway.handle(req("t1", null, null, "a")).decision());
        assertEquals(DecisionCode.PASS, gateway.handle(req("t1", null, null, "b")).decision());
        GatewayResponse r = gateway.handle(req("t1", null, null, "c"));
        assertEquals(DecisionCode.REJECT_QUOTA_EXHAUSTED, r.decision());
        assertEquals("QUOTA_EXHAUSTED", r.reason());
        assertTrue(r.retryable());
    }

    // ---------- 回归：并发上限，且被挡不泄漏在途名额 ----------

    @Test
    void concurrencyLimitRejectsAndReleasesSlotAfterUpstreamReturns() {
        props.setMaxConcurrent(1);
        // 上游桩是同步执行的，每次 handle 返回即释放名额；连续两次都应成功
        assertEquals(DecisionCode.PASS, gateway.handle(req("t1", null, null, "a")).decision());
        assertEquals(DecisionCode.PASS, gateway.handle(req("t1", null, null, "b")).decision());

        // 制造在途占用：直接占住并发器唯一名额，网关请求必须被并发挡下
        assertTrue(concurrency.tryAcquire("t1", 1));
        GatewayResponse r = gateway.handle(req("t1", null, null, "c"));
        assertEquals(DecisionCode.REJECT_CONCURRENCY_LIMITED, r.decision());
        assertEquals("CONCURRENCY_LIMITED", r.reason());
        concurrency.release("t1");

        // 名额释放后恢复正常，说明被挡请求没有泄漏在途名额，也归还了窗口配额
        assertEquals(DecisionCode.PASS, gateway.handle(req("t1", null, null, "d")).decision());
    }

    // ---------- 回归：幂等进行中不重复调用上游 ----------

    @Test
    void duplicateInFlightKeyIsRejectedAndDoesNotCallUpstream() {
        assertTrue(idempotency.markInProgress("t1", "k1", "payload"));
        int calls = upstreamCalls.get();
        GatewayResponse r = gateway.handle(req("t1", "k1", "payload", "data"));
        assertEquals(DecisionCode.REJECT_IDEMPOTENT_IN_PROGRESS, r.decision());
        assertEquals("IDEMPOTENT_IN_PROGRESS", r.reason());
        assertEquals(calls, upstreamCalls.get());
    }

    // ---------- 回归：上游异常归一化，原始异常不外泄 ----------

    @Test
    void upstreamTimeoutIsNormalizedAndNeverLeaked() {
        failingTenants.add("t1");
        upstreamError = new RuntimeException("wrap", new IOException("connection refused"));
        GatewayResponse r = gateway.handle(req("t1", null, null, "d"));
        assertEquals(DecisionCode.UPSTREAM_ERROR, r.decision());
        assertEquals("UPSTREAM_UNAVAILABLE", r.reason());
        assertFalse(String.valueOf(r).contains("connection refused"));
    }
}
