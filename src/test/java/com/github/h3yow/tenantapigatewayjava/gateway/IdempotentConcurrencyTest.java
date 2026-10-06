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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同键并发到达的幂等折叠回归测试。
 * <p>
 * 全部用例通过「阻塞式上游 + 门闩」让第一笔请求确定性地停在“上游调用中”，
 * 再投放后续同键请求，因此不依赖线程调度时序，重复运行结果稳定。
 */
class IdempotentConcurrencyTest {

    /** 阻塞式上游桩：进入后等待放行；可切换成功/失败；记录真实调用次数。 */
    private static final class BlockingUpstream implements UpstreamInvoker {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean healthy = true;

        void setHealthy(boolean value) {
            this.healthy = value;
        }

        int callCount() {
            return calls.get();
        }

        void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "上游应被进入一次");
        }

        void release() {
            release.countDown();
        }

        @Override
        public String invoke(String tenantId, String data) throws Exception {
            calls.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS), "测试应及时放行上游");
            if (!healthy) {
                throw new java.net.SocketTimeoutException("simulated upstream timeout");
            }
            return "ok:" + tenantId + ":" + data;
        }
    }

    private MutableTimeSource clock;
    private GatewayProperties properties;
    private BlockingUpstream upstream;
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

        upstream = new BlockingUpstream();
        service = new GatewayService(new QuotaLimiter(clock), new ConcurrencyLimiter(clock),
                new IdempotencyStore(), upstream, new UpstreamExceptionNormalizer(),
                new CircuitBreakerRegistry(clock, properties), properties);
    }

    private GatewayRequest req(String tenant, String key, String payload, String data) {
        return new GatewayRequest(tenant, key, payload, data);
    }

    /** 在后台线程发起的第一笔请求：可确定性等待其完成并取回响应。 */
    private static final class FirstCall {
        private final Thread thread;
        private final AtomicReference<GatewayResponse> response = new AtomicReference<>();

        FirstCall(GatewayService service, GatewayRequest request) {
            this.thread = new Thread(() -> response.set(service.handle(request)));
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        GatewayResponse joinAndGet() throws InterruptedException {
            thread.join(5_000L);
            GatewayResponse r = response.get();
            assertTrue(r != null, "第一笔请求应已完成");
            return r;
        }
    }

    /** 在后台线程发起第一笔同键请求，并等它确定性地停在上游调用中。 */
    private FirstCall startBlockingFirst(GatewayRequest first) throws Exception {
        FirstCall call = new FirstCall(service, first);
        call.start();
        upstream.awaitEntered();
        return call;
    }

    private List<GatewayResponse> fireDuplicates(String tenant, String key, String payload, int count) {
        List<GatewayResponse> responses = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            responses.add(service.handle(req(tenant, key, payload, "d")));
        }
        return responses;
    }

    @Test
    void concurrentSameKeyRequestsTriggerExactlyOneUpstreamCall() throws Exception {
        FirstCall first = startBlockingFirst(req("t1", "k1", "body", "d"));

        // 第一笔停在上游调用中：同键同内容的 8 笔并发请求全部拿到“处理中”，
        // 判定码必须与放行/配额/并发挡下明确区分。
        List<GatewayResponse> duplicates = fireDuplicates("t1", "k1", "body", 8);
        for (GatewayResponse dup : duplicates) {
            assertEquals(DecisionCode.REJECT_IDEMPOTENT_IN_PROGRESS, dup.decision());
            assertEquals("IDEMPOTENT_IN_PROGRESS", dup.reason());
            assertTrue(dup.retryable());
        }

        upstream.release();
        GatewayResponse firstResponse = first.joinAndGet();
        assertEquals(DecisionCode.PASS, firstResponse.decision());
        assertEquals(1, upstream.callCount(), "同键并发到达时上游只能被调用一次");

        // 落定后同键同内容折叠重放，仍不再打上游
        GatewayResponse replay = service.handle(req("t1", "k1", "body", "d"));
        assertEquals(DecisionCode.IDEMPOTENT_REPLAY, replay.decision());
        assertEquals(firstResponse.result(), replay.result());
        assertEquals(1, upstream.callCount());
    }

    @Test
    void inProgressRejectionsConsumeNeitherQuotaNorInflightSlots() throws Exception {
        properties.setWindowLimit(2);
        properties.setMaxConcurrent(1);
        FirstCall first = startBlockingFirst(req("t1", "k1", "body", "d"));

        // 同键重复请求：全部被“处理中”挡下
        List<GatewayResponse> duplicates = fireDuplicates("t1", "k1", "body", 6);
        for (GatewayResponse dup : duplicates) {
            assertEquals(DecisionCode.REJECT_IDEMPOTENT_IN_PROGRESS, dup.decision());
        }

        // 在途名额只被第一笔占着：不同键的新请求应被并发上限挡下（而非穿透），
        // 且它归还配额后不影响后续统计
        GatewayResponse otherKey = service.handle(req("t1", "k2", "body", "d"));
        assertEquals(DecisionCode.REJECT_CONCURRENCY_LIMITED, otherKey.decision());

        upstream.release();
        assertEquals(DecisionCode.PASS, first.joinAndGet().decision());
        assertEquals(1, upstream.callCount());

        // 窗口配额只被真实调用消耗：windowLimit=2，第一笔已用 1，
        // 若重复请求也扣了配额，这里第 2 笔真实调用就会被配额挡下。
        GatewayResponse second = service.handle(req("t1", "k3", "body", "d"));
        assertEquals(DecisionCode.PASS, second.decision());
        assertEquals(2, upstream.callCount());
        GatewayResponse third = service.handle(req("t1", "k4", "body", "d"));
        assertEquals(DecisionCode.REJECT_QUOTA_EXHAUSTED, third.decision());
    }

    @Test
    void failingFirstRequestLetsDuplicatesRetryAfterwards() throws Exception {
        upstream.setHealthy(false);
        FirstCall first = startBlockingFirst(req("t1", "k1", "body", "d"));

        List<GatewayResponse> duplicates = fireDuplicates("t1", "k1", "body", 4);
        for (GatewayResponse dup : duplicates) {
            assertEquals(DecisionCode.REJECT_IDEMPOTENT_IN_PROGRESS, dup.decision());
        }

        upstream.release();
        assertEquals(DecisionCode.UPSTREAM_ERROR, first.joinAndGet().decision());
        assertEquals(1, upstream.callCount());

        // 第一笔失败后占位已清除：同键重试真正再次调用上游并成功落定
        upstream.setHealthy(true);
        GatewayResponse retry = service.handle(req("t1", "k1", "body", "d"));
        assertEquals(DecisionCode.PASS, retry.decision());
        assertEquals(2, upstream.callCount());

        GatewayResponse replay = service.handle(req("t1", "k1", "body", "d"));
        assertEquals(DecisionCode.IDEMPOTENT_REPLAY, replay.decision());
        assertEquals(2, upstream.callCount());
    }

    @Test
    void sameKeyDifferentPayloadWhileInProgressIsConflict() throws Exception {
        FirstCall first = startBlockingFirst(req("t1", "k1", "body-A", "d"));

        GatewayResponse conflict = service.handle(req("t1", "k1", "body-B", "d"));
        assertEquals(DecisionCode.REJECT_IDEMPOTENT_CONFLICT, conflict.decision());
        assertFalse(conflict.retryable());

        upstream.release();
        assertEquals(DecisionCode.PASS, first.joinAndGet().decision());
        assertEquals(1, upstream.callCount());
    }

    @Test
    void inProgressDuplicateIsShortCircuitedEvenWhileCircuitOpen() throws Exception {
        // 上游：data=block 的调用阻塞等待放行，其余调用一律失败（用于把熔断器打到 OPEN）
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        UpstreamInvoker custom = (tenant, data) -> {
            calls.incrementAndGet();
            if ("block".equals(data)) {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS), "测试应及时放行上游");
                return "ok:" + tenant + ":" + data;
            }
            throw new java.net.SocketTimeoutException("simulated upstream timeout");
        };
        GatewayService svc = new GatewayService(new QuotaLimiter(clock), new ConcurrencyLimiter(clock),
                new IdempotencyStore(), custom, new UpstreamExceptionNormalizer(),
                new CircuitBreakerRegistry(clock, properties), properties);

        AtomicReference<GatewayResponse> first = new AtomicReference<>();
        Thread thread = new Thread(() -> first.set(svc.handle(req("t1", "k1", "body", "block"))));
        thread.setDaemon(true);
        thread.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "第一笔应停在上游调用中");

        // 第一笔在途期间，该租户熔断器被其它失败请求打开
        for (int i = 0; i < 3; i++) {
            assertEquals(DecisionCode.UPSTREAM_ERROR, svc.handle(req("t1", null, "p", "d")).decision());
        }
        assertEquals(DecisionCode.REJECT_CIRCUIT_OPEN, svc.handle(req("t1", null, "p", "d")).decision());

        // 熔断打开期间，同键同内容的在途重复请求仍短路为“处理中”：不暴露熔断码、不重复打上游
        GatewayResponse dup = svc.handle(req("t1", "k1", "body", "block"));
        assertEquals(DecisionCode.REJECT_IDEMPOTENT_IN_PROGRESS, dup.decision());
        assertEquals("IDEMPOTENT_IN_PROGRESS", dup.reason());

        release.countDown();
        thread.join();
        assertEquals(DecisionCode.PASS, first.get().decision());
        assertEquals(4, calls.get(), "真实上游调用只有 1 笔在途 + 3 笔失败，重复请求未新增调用");

        // 熔断打开期间，已落定的同键同内容请求照常折叠重放
        GatewayResponse replay = svc.handle(req("t1", "k1", "body", "block"));
        assertEquals(DecisionCode.IDEMPOTENT_REPLAY, replay.decision());
        assertEquals(4, calls.get());
    }
}
