package com.github.h3yow.tenantapigatewayjava.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.h3yow.tenantapigatewayjava.config.TenantProperties;
import com.github.h3yow.tenantapigatewayjava.core.clock.GatewayClock;
import com.github.h3yow.tenantapigatewayjava.quota.QuotaResult;
import com.github.h3yow.tenantapigatewayjava.quota.QuotaService;
import com.github.h3yow.tenantapigatewayjava.quota.QuotaService.QuotaDecision;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 并发竞争：同一窗口大量并发请求下不超额放行、不丢计数。 */
class QuotaConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaConcurrencyTest.class);

    /** 测试期间时间固定，保证全部请求落在同一窗口。 */
    private static final GatewayClock FIXED_CLOCK = () -> Instant.ofEpochSecond(1_700_000_000L);

    private static TenantProperties properties(int quotaLimit, int concurrencyLimit) {
        TenantProperties props = new TenantProperties();
        TenantProperties.TenantConfig cfg = new TenantProperties.TenantConfig();
        cfg.setWindowSeconds(60);
        cfg.setQuotaLimit(quotaLimit);
        cfg.setConcurrencyLimit(concurrencyLimit);
        Map<String, TenantProperties.TenantConfig> tenants = new LinkedHashMap<>();
        tenants.put("tenant-a", cfg);
        props.setTenants(tenants);
        return props;
    }

    @Test
    @DisplayName("50 并发 / 配额 10：恰好 10 次放行，40 次以 QUOTA_EXCEEDED 拒绝，无超额")
    void noOverAdmitUnderContention() throws InterruptedException {
        int threads = 50;
        QuotaService svc = new QuotaService(FIXED_CLOCK, properties(10, threads));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<QuotaDecision> decisions = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final int seq = i + 1;
            pool.submit(() -> {
                try {
                    start.await();
                    QuotaDecision d = svc.acquire("tenant-a");
                    decisions.add(d);
                    if (d.result() == QuotaResult.ALLOWED) {
                        svc.release("tenant-a");
                    }
                    log.info("并发配额 tenant=tenant-a window=[},{}) 输入=并发请求#{} 判定={} 依据=consumed={}/{}",
                            d.windowStartEpoch(), d.windowEndEpoch(), seq, d.result(),
                            d.consumedInWindow(), d.quotaLimit());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        long allowed = decisions.stream().filter(d -> d.result() == QuotaResult.ALLOWED).count();
        long rejected = decisions.stream().filter(d -> d.result() == QuotaResult.QUOTA_EXCEEDED).count();
        QuotaDecision snapshot = svc.snapshot("tenant-a");
        log.info("并发配额汇总 tenant=tenant-a window=[},{}) 输入={}线程 判定=放行{} 拒绝{} 最终计数={}/{}",
                snapshot.windowStartEpoch(), snapshot.windowEndEpoch(), threads,
                allowed, rejected, snapshot.consumedInWindow(), snapshot.quotaLimit());

        assertThat(allowed).isEqualTo(10L);
        assertThat(rejected).isEqualTo(40L);
        assertThat(allowed + rejected).isEqualTo(threads);
        assertThat(snapshot.consumedInWindow()).isEqualTo(10);
        assertThat(snapshot.inflight()).isZero();
    }

    @Test
    @DisplayName("慢上游制造并发在途：并发上限=2 时仅 2 个在途，其余 CONCURRENCY_EXCEEDED，释放后可再进")
    void concurrencySlotsRespectedWithSlowUpstream() throws InterruptedException {
        int threads = 6;
        int concurrency = 2;
        QuotaService svc = new QuotaService(FIXED_CLOCK, properties(100, concurrency));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch entered = new CountDownLatch(concurrency);
        CountDownLatch release = new CountDownLatch(1);
        ConcurrentLinkedQueue<QuotaResult> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final int seq = i + 1;
            pool.submit(() -> {
                QuotaDecision d = svc.acquire("tenant-a");
                results.add(d.result());
                log.info("并发在途 tenant=tenant-a 输入=请求#{} 判定={} 依据=inflight={}/{}",
                        seq, d.result(), d.inflight(), d.concurrencyLimit());
                if (d.result() == QuotaResult.ALLOWED) {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    svc.release("tenant-a");
                }
            });
        }
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        // 等待所有非在途请求完成判定
        Thread.sleep(300);
        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        long allowed = results.stream().filter(r -> r == QuotaResult.ALLOWED).count();
        long busyRejected = results.stream()
                .filter(r -> r == QuotaResult.CONCURRENCY_EXCEEDED).count();
        log.info("并发在途汇总 tenant=tenant-a 输入={}线程 在途上限={} 判定=放行{} 并发拒绝{}",
                threads, concurrency, allowed, busyRejected);
        assertThat(allowed).isEqualTo(concurrency);
        assertThat(busyRejected).isEqualTo(threads - concurrency);
        assertThat(svc.snapshot("tenant-a").inflight()).isZero();
        // 释放后槽位恢复
        assertThat(svc.acquire("tenant-a").result()).isEqualTo(QuotaResult.ALLOWED);
        svc.release("tenant-a");
    }
}
