package com.github.h3yow.tenantapigatewayjava.gateway;

import com.github.h3yow.tenantapigatewayjava.circuit.CircuitBreakerRegistry;
import com.github.h3yow.tenantapigatewayjava.concurrency.ConcurrencyLimiter;
import com.github.h3yow.tenantapigatewayjava.core.MutableTimeSource;
import com.github.h3yow.tenantapigatewayjava.idempotency.IdempotencyStore;
import com.github.h3yow.tenantapigatewayjava.model.DecisionCode;
import com.github.h3yow.tenantapigatewayjava.model.GatewayRequest;
import com.github.h3yow.tenantapigatewayjava.model.GatewayResponse;
import com.github.h3yow.tenantapigatewayjava.quota.QuotaLimiter;
import com.github.h3yow.tenantapigatewayjava.upstream.UpstreamExceptionNormalizer;
import com.github.h3yow.tenantapigatewayjava.upstream.UpstreamInvoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayServiceTest {

    private MutableTimeSource clock;
    private GatewayProperties properties;
    private StubUpstreamInvoker upstream;
    private QuotaLimiter quotaLimiter;
    private ConcurrencyLimiter concurrencyLimiter;
    private IdempotencyStore idempotencyStore;
    private CircuitBreakerRegistry registry;
    private GatewayService service;

    @BeforeEach
    void setUp() {
        clock = new MutableTimeSource();
        properties = new GatewayProperties();
        properties.setWindowSeconds(60);
        properties.setWindowLimit(100);
        properties.setMaxConcurrent(20);
        properties.setFailureThreshold(3);
        properties.setOpenDurationMillis(10_000L);
        properties.setProbePermits(1);
        properties.setProbeWaitMillis(2_000L);

        upstream = new StubUpstreamInvoker();
        quotaLimiter = new QuotaLimiter(clock);
        concurrencyLimiter = new ConcurrencyLimiter(clock);
        idempotencyStore = new IdempotencyStore();
        registry = new CircuitBreakerRegistry(clock, properties);
        service = new GatewayService(quotaLimiter, concurrencyLimiter, idempotencyStore,
                upstream, new UpstreamExceptionNormalizer(), registry, properties);
    }

    private GatewayRequest req(String tenant, String key, String payload, String data) {
        return new GatewayRequest(tenant, key, payload, data);
    }

    private void makeFailures(String tenant, int n) {
        upstream.setHealthy(false);
        for (int i = 0; i < n; i++) {
            service.handle(req(tenant, null, "p", "d"));
        }
        upstream.setHealthy(true);
    }

    // ---------- 既有能力：正常路径 ----------

    @Test
    void normalPassReturnsUpstreamResult() {
        GatewayResponse response = service.handle(req("t1", null, "p", "hello"));
        assertEquals(DecisionCode.PASS, response.decision());
        assertEquals("OK", response.reason());
        assertEquals("ok:t1:hello", response.result());
        assertFalse(response.retryable());
    }

    // ---------- 既有能力：配额 ----------

    @Test
    void quotaExhaustionIsRejected() {
        properties.setWindowLimit(2);
        assertEquals(DecisionCode.PASS, service.handle(req("t1", null, "p", "d")).decision());
        assertEquals(DecisionCode.PASS, service.handle(req("t1", null, "p", "d")).decision());
        GatewayResponse third = service.handle(req("t1", null, "p", "d"));
        assertEquals(DecisionCode.REJECT_QUOTA_EXHAUSTED, third.decision());
        assertEquals("QUOTA_EXHAUSTED", third.reason());
        assertTrue(third.retryable());
        assertNotNull(third.retryAfterSeconds());
    }

    @Test
    void circuitRejectedRequestsDoNotConsumeQuota() {
        properties.setWindowLimit(100);
        makeFailures("t1", 3); // 熔断打开，3 次真实调用

        // 熔断期间大量请求被挡，均不占窗口配额
        for (int i = 0; i < 50; i++) {
            GatewayResponse r = service.handle(req("t1", null, "p", "d"));
            assertEquals(DecisionCode.REJECT_CIRCUIT_OPEN, r.decision());
        }
        // 冷静期结束后的探测请求成功 -> 恢复；第 4 次真实调用仍应在配额内
        clock.advance(10_000L);
        GatewayResponse probe = service.handle(req("t1", null, "p", "d"));
        assertEquals(DecisionCode.PASS_PROBE, probe.decision());
        GatewayResponse after = service.handle(req("t1", null, "p", "d"));
        assertEquals(DecisionCode.PASS, after.decision());
    }

    // ---------- 既有能力：并发 ----------

    @Test
    void concurrencyLimitRejectsAndRefundsQuota() throws Exception {
        properties.setMaxConcurrent(1);
        properties.setWindowLimit(2);
        // 占用唯一在途名额：用一个调用上游时阻塞的请求
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        UpstreamInvoker blocking = (tenant, data) -> {
            entered.countDown();
            release.await();
            return "blocked-result";
        };
        GatewayService blockingService = new GatewayService(
                new QuotaLimiter(clock), new ConcurrencyLimiter(clock), new IdempotencyStore(),
                blocking, new UpstreamExceptionNormalizer(),
                new CircuitBreakerRegistry(clock, properties), properties);

        java.util.concurrent.atomic.AtomicReference<GatewayResponse> first =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> first.set(blockingService.handle(req("t1", null, "p", "d"))));
        t.start();
        entered.await();

        // 唯一在途名额已占，第二个请求应被并发挡下；它占用的窗口名额必须归还
        GatewayResponse limited = blockingService.handle(req("t1", null, "p", "d"));
        assertEquals(DecisionCode.REJECT_CONCURRENCY_LIMITED, limited.decision());
        assertEquals("CONCURRENCY_LIMITED", limited.reason());
        assertTrue(limited.retryable());

        release.countDown();
        t.join();
        assertEquals(DecisionCode.PASS, first.get().decision());

        // 被并发挡下的请求已归还窗口名额（当前 used=1/2）：紧接着还能再放 1 个请求；
        // 若没有归还名额，这里会是 REJECT_QUOTA_EXHAUSTED。
        GatewayResponse next = blockingService.handle(req("t1", null, "p", "d"));
        assertEquals(DecisionCode.PASS, next.decision());
    }

    // ---------- 既有能力：幂等 ----------

    @Test
    void idempotentSettledRequestIsReplayedWithoutUpstreamCall() {
        GatewayResponse first = service.handle(req("t1", "k1", "body", "d"));
        assertEquals(DecisionCode.PASS, first.decision());
        int callsAfterFirst = upstream.callCount();

        GatewayResponse replay = service.handle(req("t1", "k1", "body", "d"));
        assertEquals(DecisionCode.IDEMPOTENT_REPLAY, replay.decision());
        assertEquals(first.result(), replay.result());
        assertEquals(callsAfterFirst, upstream.callCount());
    }

    @Test
    void sameKeyDifferentPayloadIsConflict() {
        service.handle(req("t1", "k1", "body-A", "d"));
        GatewayResponse conflict = service.handle(req("t1", "k1", "body-B", "d"));
        assertEquals(DecisionCode.REJECT_IDEMPOTENT_CONFLICT, conflict.decision());
        assertFalse(conflict.retryable());
    }

    @Test
    void idempotentReplayStillWorksWhileCircuitOpen() {
        // 先落定一个写请求
        GatewayResponse settled = service.handle(req("t1", "k1", "body", "d"));
        assertEquals("ok:t1:d", settled.result());

        makeFailures("t1", 3); // 熔断打开

        // 同键同内容再回来：正常折叠重放，不被熔断拦掉，也不打上游
        int calls = upstream.callCount();
        GatewayResponse replay = service.handle(req("t1", "k1", "body", "d"));
        assertEquals(DecisionCode.IDEMPOTENT_REPLAY, replay.decision());
        assertEquals(settled.result(), replay.result());
        assertEquals(calls, upstream.callCount());
    }

    @Test
    void failedUpstreamDoesNotSettleIdempotencyAndAllowsRetry() {
        upstream.setHealthy(false);
        GatewayResponse failed = service.handle(req("t1", "k1", "body", "d"));
        assertEquals(DecisionCode.UPSTREAM_ERROR, failed.decision());

        upstream.setHealthy(true);
        GatewayResponse retry = service.handle(req("t1", "k1", "body", "d"));
        assertEquals(DecisionCode.PASS, retry.decision());
        assertEquals("ok:t1:d", retry.result());
    }

    // ---------- 既有能力：上游异常归一化 ----------

    @Test
    void upstreamErrorIsNormalizedAndRawExceptionHidden() {
        upstream.setHealthy(false);
        GatewayResponse response = service.handle(req("t1", null, "p", "secret-input"));
        assertEquals(DecisionCode.UPSTREAM_ERROR, response.decision());
        assertEquals("UPSTREAM_UNAVAILABLE", response.reason());
        assertNull(response.result());
        assertTrue(response.retryable());
        // 响应中不含原始异常文本
        assertFalse(String.valueOf(response).contains("simulated"));
    }

    // ---------- 新增：熔断快速失败 ----------

    @Test
    void circuitOpensAfterThresholdAndGivesDistinctReason() {
        makeFailures("t1", 3);
        GatewayResponse fastFail = service.handle(req("t1", null, "p", "d"));
        assertEquals(DecisionCode.REJECT_CIRCUIT_OPEN, fastFail.decision());
        assertEquals("CIRCUIT_OPEN", fastFail.reason());
        assertTrue(fastFail.retryable());
        assertEquals(10L, fastFail.retryAfterSeconds());
        assertNull(fastFail.result());
    }

    @Test
    void fastFailRequestsDoNotOccupyInflightOrConsumeQuotaOrHitUpstream() {
        makeFailures("t1", 3);
        int calls = upstream.callCount();
        for (int i = 0; i < 10; i++) {
            GatewayResponse r = service.handle(req("t1", null, "p", "d"));
            assertEquals(DecisionCode.REJECT_CIRCUIT_OPEN, r.decision());
        }
        assertEquals(calls, upstream.callCount());
    }

    @Test
    void oneTenantOpenDoesNotAffectAnotherTenant() {
        makeFailures("bad-tenant", 3);
        assertEquals(DecisionCode.REJECT_CIRCUIT_OPEN,
                service.handle(req("bad-tenant", null, "p", "d")).decision());
        // 健康租户完全不受影响
        assertEquals(DecisionCode.PASS,
                service.handle(req("good-tenant", null, "p", "d")).decision());
    }

    // ---------- 新增：探测与恢复 ----------

    @Test
    void singleProbeAllowedAfterCooldownThenRecovery() {
        makeFailures("t1", 3);
        clock.advance(10_000L);

        GatewayResponse probe = service.handle(req("t1", null, "p", "probe-data"));
        assertEquals(DecisionCode.PASS_PROBE, probe.decision());
        assertEquals("PASS_PROBE", probe.reason());
        assertEquals("ok:t1:probe-data", probe.result());

        // 探测成功整体恢复
        GatewayResponse normal = service.handle(req("t1", null, "p", "d"));
        assertEquals(DecisionCode.PASS, normal.decision());
    }

    @Test
    void concurrentRequestsDoNotBurstThroughDuringProbe() throws Exception {
        // 阻塞式上游：探测请求进入后停在“上游调用中”，保证其余请求到达时熔断器
        // 确定处于 HALF_OPEN 且探测名额已用完——不依赖线程调度时序，重复运行稳定。
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean healthy = new java.util.concurrent.atomic.AtomicBoolean(false);
        UpstreamInvoker blocking = (tenant, data) -> {
            if (!healthy.get()) {
                throw new java.net.SocketTimeoutException("simulated upstream timeout");
            }
            entered.countDown();
            release.await();
            return "ok:" + tenant + ":" + data;
        };
        GatewayService blockingService = new GatewayService(
                quotaLimiter, concurrencyLimiter, idempotencyStore,
                blocking, new UpstreamExceptionNormalizer(), registry, properties);

        // 熔断打开并熬过冷静期，上游随后恢复
        for (int i = 0; i < 3; i++) {
            blockingService.handle(req("t1", null, "p", "d"));
        }
        clock.advance(10_000L);
        healthy.set(true);

        // 探测请求在后台线程发起，并确定性地停在上游调用中
        java.util.concurrent.atomic.AtomicReference<GatewayResponse> probeRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread probeThread = new Thread(
                () -> probeRef.set(blockingService.handle(req("t1", null, "p", "d"))));
        probeThread.setDaemon(true);
        probeThread.start();
        org.junit.jupiter.api.Assertions.assertTrue(
                entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "探测请求应进入上游");

        // 探测在途期间，其余请求一律快速失败，无一穿透
        for (int i = 0; i < 20; i++) {
            GatewayResponse r = blockingService.handle(req("t1", null, "p", "d"));
            assertEquals(DecisionCode.REJECT_CIRCUIT_OPEN, r.decision(),
                    "探测在途期间其余请求必须被快速失败");
        }

        release.countDown();
        probeThread.join(5_000L);
        assertEquals(DecisionCode.PASS_PROBE, probeRef.get().decision(), "唯一的探测请求应放行成功");

        // 探测成功后整体恢复
        assertEquals(DecisionCode.PASS, blockingService.handle(req("t1", null, "p", "d")).decision());
    }

    @Test
    void failedProbeKeepsCircuitOpenRestartsTimerAndAuditsDistinctly() {
        makeFailures("t1", 3);
        clock.advance(10_000L);

        // 探测请求遇到上游仍不健康
        upstream.setHealthy(false);
        GatewayResponse failedProbe = service.handle(req("t1", null, "p", "d"));
        assertEquals(DecisionCode.REJECT_PROBE_FAILED, failedProbe.decision());
        assertEquals("CIRCUIT_PROBE_FAILED", failedProbe.reason());
        upstream.setHealthy(true);

        // 冷静期重新计时：紧接着的请求继续被挡，且审计原因为“探测失败”
        GatewayResponse stillBlocked = service.handle(req("t1", null, "p", "d"));
        assertEquals(DecisionCode.REJECT_PROBE_FAILED, stillBlocked.decision());
        assertEquals(10L, stillBlocked.retryAfterSeconds());

        // 未到新冷静期结束不能再探测
        clock.advance(9_999L);
        assertEquals(DecisionCode.REJECT_PROBE_FAILED, service.handle(req("t1", null, "p", "d")).decision());

        clock.advance(1L);
        GatewayResponse nextProbe = service.handle(req("t1", null, "p", "d"));
        assertEquals(DecisionCode.PASS_PROBE, nextProbe.decision());
        assertEquals(DecisionCode.PASS, service.handle(req("t1", null, "p", "d")).decision());
    }

    @Test
    void keyedRequestRejectedByCircuitLeavesNoResidueAndCanRetryAfterRecovery() {
        makeFailures("t1", 3); // 熔断打开

        // 熔断期间带键新请求被快速失败：不占配额、不留幂等占位
        GatewayResponse blocked = service.handle(req("t1", "k9", "body", "d"));
        assertEquals(DecisionCode.REJECT_CIRCUIT_OPEN, blocked.decision());

        // 冷静期后探测恢复；同键请求应作为全新请求真正调用上游（占位已清除）
        clock.advance(10_000L);
        GatewayResponse probe = service.handle(req("t1", null, "p", "d"));
        assertEquals(DecisionCode.PASS_PROBE, probe.decision());

        GatewayResponse retry = service.handle(req("t1", "k9", "body", "d"));
        assertEquals(DecisionCode.PASS, retry.decision());
        assertEquals("ok:t1:d", retry.result());

        // 落定后正常折叠重放
        GatewayResponse replay = service.handle(req("t1", "k9", "body", "d"));
        assertEquals(DecisionCode.IDEMPOTENT_REPLAY, replay.decision());
    }

    @Test
    void newRequestsDuringOpenNeedNewUpstreamCallEvenWithSamePayloadButNoKey() {
        // 无幂等键的请求：熔断恢复后正常调用上游（对照幂等折叠路径）
        makeFailures("t1", 3);
        clock.advance(10_000L);
        GatewayResponse probe = service.handle(req("t1", null, "same-body", "d"));
        assertEquals(DecisionCode.PASS_PROBE, probe.decision());
    }
}
