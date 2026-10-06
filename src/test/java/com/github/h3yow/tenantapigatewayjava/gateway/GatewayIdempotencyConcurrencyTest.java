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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一租户、同一幂等键、同内容请求并发到达时的折叠语义。
 * <p>
 * 全部用例用「可控阻塞上游」把时序钉死：第一个请求停在上游调用里，其余请求再进入，
 * 断言结果不依赖线程调度。核心不变量：同一键同一时刻最多一笔真正调用上游，
 * 其余请求拿到“处理中”提示，且不消耗窗口配额、不占在途名额。
 */
class GatewayIdempotencyConcurrencyTest {

    private static final Logger log =
            LoggerFactory.getLogger(GatewayIdempotencyConcurrencyTest.class);

    private MutableTimeSource clock;
    private GatewayProperties properties;
    private QuotaLimiter quotaLimiter;
    private ConcurrencyLimiter concurrencyLimiter;
    private IdempotencyStore idempotencyStore;
    private CircuitBreakerRegistry registry;

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
        quotaLimiter = new QuotaLimiter(clock);
        concurrencyLimiter = new ConcurrencyLimiter(clock);
        idempotencyStore = new IdempotencyStore();
        registry = new CircuitBreakerRegistry(clock, properties);
    }

    private GatewayRequest req(String tenant, String key, String payload, String data) {
        return new GatewayRequest(tenant, key, payload, data);
    }

    private GatewayService newService(UpstreamInvoker upstream) {
        return new GatewayService(quotaLimiter, concurrencyLimiter, idempotencyStore,
                upstream, new UpstreamExceptionNormalizer(), registry, properties);
    }

    /** 可控上游：data 等于 blockedData 的调用会停住，直到测试放行；可切换健康状态并计数。 */
    private static final class ControlledUpstream implements UpstreamInvoker {
        private final String blockedData;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean healthy = true;

        ControlledUpstream(String blockedData) {
            this.blockedData = blockedData;
        }

        int callCount() {
            return calls.get();
        }

        void setHealthy(boolean value) {
            healthy = value;
        }

        void awaitEntered() throws InterruptedException {
            entered.await();
        }

        void release() {
            release.countDown();
        }

        @Override
        public String invoke(String tenantId, String data) throws Exception {
            calls.incrementAndGet();
            if (blockedData.equals(data)) {
                entered.countDown();
                release.await();
            }
            if (!healthy) {
                throw new SocketTimeoutException("simulated upstream timeout");
            }
            return "ok:" + tenantId + ":" + data;
        }
    }

    /** 把「查询」与「占位」之间的竞态窗口撕开的幂等存储：第一个占位调用会停住，直到测试放行。 */
    private static final class PausingIdempotencyStore extends IdempotencyStore {
        private final CountDownLatch firstMarkEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstMark = new CountDownLatch(1);
        private final java.util.concurrent.atomic.AtomicBoolean paused =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        void awaitFirstMarkEntered() throws InterruptedException {
            firstMarkEntered.await();
        }

        void releaseFirstMark() {
            releaseFirstMark.countDown();
        }

        @Override
        public boolean markInProgress(String tenantId, String idempotencyKey, String payload) {
            if (paused.compareAndSet(false, true)) {
                firstMarkEntered.countDown();
                try {
                    releaseFirstMark.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.markInProgress(tenantId, idempotencyKey, payload);
        }
    }

    /** 并发执行 n 次同一任务并收集响应；调用方需保证断言不依赖线程调度。 */
    private List<GatewayResponse> runConcurrent(int n, Supplier<GatewayResponse> task)
            throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<GatewayResponse> responses = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                responses.add(task.get());
            });
            threads.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }
        return responses;
    }

    @Test
    void concurrentSameKeySameContentCallsUpstreamOnlyOnce() throws Exception {
        // 人为撕开「幂等查询」与「占位」之间的竞态窗口：第一笔请求停在查询之后、占位之前，
        // 此刻 10 笔同键同内容请求到达。若认领不是原子的，这些请求会同时穿透到上游。
        PausingIdempotencyStore store = new PausingIdempotencyStore();
        AtomicInteger upstreamCalls = new AtomicInteger();
        UpstreamInvoker counting = (tenant, data) -> {
            upstreamCalls.incrementAndGet();
            return "ok:" + tenant + ":" + data;
        };
        GatewayService service = new GatewayService(quotaLimiter, concurrencyLimiter, store,
                counting, new UpstreamExceptionNormalizer(), registry, properties);

        AtomicReference<GatewayResponse> first = new AtomicReference<>();
        Thread firstThread = new Thread(
                () -> first.set(service.handle(req("t1", "k1", "body", "d"))));
        firstThread.start();
        store.awaitFirstMarkEntered();

        // 竞态窗口打开期间，10 笔同键同内容请求并发到达
        List<GatewayResponse> duplicates =
                runConcurrent(10, () -> service.handle(req("t1", "k1", "body", "d")));

        store.releaseFirstMark();
        firstThread.join();

        long passed = duplicates.stream().filter(r -> r.decision() == DecisionCode.PASS).count();
        long folded = duplicates.stream()
                .filter(r -> r.decision() == DecisionCode.REJECT_IDEMPOTENT_IN_PROGRESS
                        || r.decision() == DecisionCode.IDEMPOTENT_REPLAY).count();
        log.info("case=concurrent-same-key tenant=t1 key=k1 windowSeconds={} duplicates={} passed={} folded={} upstreamCalls={} firstDecision={}",
                properties.getWindowSeconds(), duplicates.size(), passed, folded,
                upstreamCalls.get(), first.get().decision());
        assertEquals(1, upstreamCalls.get(), "同一键同一时刻最多一笔真正调用上游");
        assertEquals(1, passed, "并发重复请求里只有一笔真正执行");
        assertEquals(duplicates.size() - 1, folded, "其余同键请求必须折叠为“处理中”或“重放”");
        assertTrue(duplicates.stream()
                .filter(r -> r.decision() == DecisionCode.REJECT_IDEMPOTENT_IN_PROGRESS)
                .allMatch(GatewayResponse::retryable));
        // 被窗口拦住的第一笔在占位释放后发现键已落定：折叠重放，不再打上游
        assertEquals(DecisionCode.IDEMPOTENT_REPLAY, first.get().decision());
    }

    @Test
    void inProgressRejectsDoNotConsumeQuotaOrInflightSlot() throws Exception {
        // 窗口只放 2 笔、在途只放 1 个：用后续请求反推“处理中”请求没有留下任何资源占用
        properties.setWindowLimit(2);
        properties.setMaxConcurrent(1);
        ControlledUpstream upstream = new ControlledUpstream("blocked");
        GatewayService service = newService(upstream);

        // 第一笔停在上游：占走唯一在途名额与 1 个窗口名额
        AtomicReference<GatewayResponse> first = new AtomicReference<>();
        Thread firstThread = new Thread(
                () -> first.set(service.handle(req("t1", "k1", "body", "blocked"))));
        firstThread.start();
        upstream.awaitEntered();

        // 同键请求被折叠为“处理中”：若它走到并发/配额环节，会拿到 CONCURRENCY_LIMITED 或消耗配额
        GatewayResponse dup = service.handle(req("t1", "k1", "body", "blocked"));
        log.info("case=in-progress-no-resource tenant=t1 key=k1 decision={} reason={}",
                dup.decision(), dup.reason());
        assertEquals(DecisionCode.REJECT_IDEMPOTENT_IN_PROGRESS, dup.decision());

        upstream.release();
        firstThread.join();
        assertEquals(DecisionCode.PASS, first.get().decision());

        // 第一笔已落定：本窗口只剩 1 个名额。若“处理中”请求偷占了配额或在途名额，
        // 下面第一笔新键请求就会被配额或并发挡下。
        GatewayResponse second = service.handle(req("t1", "k2", "body", "d2"));
        assertEquals(DecisionCode.PASS, second.decision());
        GatewayResponse third = service.handle(req("t1", "k3", "body", "d3"));
        log.info("case=in-progress-no-resource tenant=t1 windowLimit=2 thirdDecision={}",
                third.decision());
        assertEquals(DecisionCode.REJECT_QUOTA_EXHAUSTED, third.decision());
        assertEquals(2, upstream.callCount(), "窗口内只有两笔真正调用上游");
    }

    @Test
    void failedInFlightReleasesKeyAndConcurrentDuplicateCanRetry() throws Exception {
        ControlledUpstream upstream = new ControlledUpstream("blocked");
        GatewayService service = newService(upstream);

        // 第一笔停在上游；同键并发请求折叠为“处理中”
        AtomicReference<GatewayResponse> first = new AtomicReference<>();
        Thread firstThread = new Thread(
                () -> first.set(service.handle(req("t1", "k1", "body", "blocked"))));
        firstThread.start();
        upstream.awaitEntered();
        GatewayResponse dup = service.handle(req("t1", "k1", "body", "blocked"));
        assertEquals(DecisionCode.REJECT_IDEMPOTENT_IN_PROGRESS, dup.decision());

        // 第一笔上游失败：不写终态、清掉占位，同键允许重试
        upstream.setHealthy(false);
        upstream.release();
        firstThread.join();
        log.info("case=failed-inflight-retry tenant=t1 key=k1 firstDecision={} reason={}",
                first.get().decision(), first.get().reason());
        assertEquals(DecisionCode.UPSTREAM_ERROR, first.get().decision());

        upstream.setHealthy(true);
        GatewayResponse retry = service.handle(req("t1", "k1", "body", "retry-data"));
        assertEquals(DecisionCode.PASS, retry.decision());
        assertEquals(2, upstream.callCount(), "失败一笔 + 重试一笔，共两次真实上游调用");

        GatewayResponse replay = service.handle(req("t1", "k1", "body", "retry-data"));
        assertEquals(DecisionCode.IDEMPOTENT_REPLAY, replay.decision());
        assertEquals(2, upstream.callCount());
    }

    @Test
    void inProgressFoldAndSettledReplayBothBypassCircuitOpen() throws Exception {
        ControlledUpstream upstream = new ControlledUpstream("blocked");
        GatewayService service = newService(upstream);

        // 同键第一笔停在上游（熔断器此时 CLOSED，已正常准入）
        AtomicReference<GatewayResponse> first = new AtomicReference<>();
        Thread firstThread = new Thread(
                () -> first.set(service.handle(req("t1", "k1", "body", "blocked"))));
        firstThread.start();
        upstream.awaitEntered();

        // 其它请求连续失败把租户熔断打开
        upstream.setHealthy(false);
        for (int i = 0; i < 3; i++) {
            assertEquals(DecisionCode.UPSTREAM_ERROR,
                    service.handle(req("t1", null, "p", "f" + i)).decision());
        }
        assertEquals(DecisionCode.REJECT_CIRCUIT_OPEN,
                service.handle(req("t1", null, "p", "x")).decision());

        // 熔断打开期间：同键同内容仍在途 -> “处理中”折叠优先于熔断，不被熔断拦截
        GatewayResponse dup = service.handle(req("t1", "k1", "body", "blocked"));
        log.info("case=fold-during-circuit-open tenant=t1 key=k1 decision={} reason={}",
                dup.decision(), dup.reason());
        assertEquals(DecisionCode.REJECT_IDEMPOTENT_IN_PROGRESS, dup.decision());

        // 第一笔上游恢复后成功落定（非探测成功不改变 OPEN 状态）
        upstream.setHealthy(true);
        upstream.release();
        firstThread.join();
        assertEquals(DecisionCode.PASS, first.get().decision());

        // 熔断仍开着：同键已落定 -> 重放优先于熔断；无键新请求仍被快速失败
        GatewayResponse replay = service.handle(req("t1", "k1", "body", "blocked"));
        assertEquals(DecisionCode.IDEMPOTENT_REPLAY, replay.decision());
        assertEquals(first.get().result(), replay.result());
        assertEquals(DecisionCode.REJECT_CIRCUIT_OPEN,
                service.handle(req("t1", null, "p", "y")).decision());
        assertEquals(4, upstream.callCount(), "3 笔失败 + 1 笔成功，折叠与重放都不打上游");
    }
}
