package com.github.h3yow.tenantapigatewayjava.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.tenantapigatewayjava.audit.AuditService;
import com.github.h3yow.tenantapigatewayjava.config.TenantProperties;
import com.github.h3yow.tenantapigatewayjava.error.GatewayErrorCode;
import com.github.h3yow.tenantapigatewayjava.error.GatewayException;
import com.github.h3yow.tenantapigatewayjava.gateway.GatewayResponse;
import com.github.h3yow.tenantapigatewayjava.gateway.GatewayService;
import com.github.h3yow.tenantapigatewayjava.idem.IdempotencyService;
import com.github.h3yow.tenantapigatewayjava.ledger.BalanceLedger;
import com.github.h3yow.tenantapigatewayjava.quota.QuotaService;
import com.github.h3yow.tenantapigatewayjava.tenant.TenantResolver;
import com.github.h3yow.tenantapigatewayjava.tests.support.MutableClock;
import com.github.h3yow.tenantapigatewayjava.upstream.MockUpstreamClient;
import com.github.h3yow.tenantapigatewayjava.upstream.UpstreamResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 幂等语义：重复提交折叠、键冲突/处理中/参数错误可区分、不重复扣减。 */
class IdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTest.class);

    private MutableClock clock;
    private BalanceLedger ledger;
    private GatewayService gateway;
    private AuditService auditService;

    private static TenantProperties properties() {
        TenantProperties props = new TenantProperties();
        TenantProperties.TenantConfig cfg = new TenantProperties.TenantConfig();
        cfg.setWindowSeconds(60);
        cfg.setQuotaLimit(1000);
        cfg.setConcurrencyLimit(1000);
        cfg.setInitialBalance(10_000L);
        props.getUpstream().setDelayMillis(400L);
        Map<String, TenantProperties.TenantConfig> tenants = new LinkedHashMap<>();
        tenants.put("tenant-a", cfg);
        props.setTenants(tenants);
        return props;
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        TenantProperties props = properties();
        ledger = new BalanceLedger(props);
        auditService = new AuditService(clock);
        QuotaService quotaService = new QuotaService(clock, props);
        IdempotencyService idempotencyService = new IdempotencyService(clock);
        TenantResolver resolver = new TenantResolver(props);
        MockUpstreamClient upstream = new MockUpstreamClient(props, ledger);
        gateway = new GatewayService(resolver, idempotencyService, quotaService, upstream,
                auditService, new ObjectMapper());
    }

    @Test
    @DisplayName("相同幂等键的重复提交折叠为一次生效：只扣一次款，第二次为 REPLAY")
    void duplicateSubmissionFolds() {
        long before = ledger.getBalance("tenant-a");
        GatewayResponse first = gateway.pay("tenant-a", "KEY-1", "ORD-1", 1_000L, "ok", "R-1");
        GatewayResponse second = gateway.pay("tenant-a", "KEY-1", "ORD-1", 1_000L, "ok", "R-2");
        GatewayResponse third = gateway.pay("tenant-a", "KEY-1", "ORD-1", 1_000L, "ok", "R-3");

        log.info("幂等折叠 tenant=tenant-a 输入=同键3次提交 KEY-1 判定1={} 判定2={} 判定3={} 余额 {}->{}（应只扣1000）",
                first.decision(), second.decision(), third.decision(), before,
                ledger.getBalance("tenant-a"));

        assertThat(first.decision()).isEqualTo("ALLOWED_UPSTREAM_OK");
        assertThat(first.replayed()).isFalse();
        assertThat(second.decision()).isEqualTo("REPLAY_OK");
        assertThat(second.replayed()).isTrue();
        assertThat(third.decision()).isEqualTo("REPLAY_OK");
        assertThat(ledger.getBalance("tenant-a")).isEqualTo(before - 1_000L);
        long executions = auditService.findByTenant("tenant-a").stream()
                .filter(r -> "ALLOWED_UPSTREAM_OK".equals(r.decision())).count();
        assertThat(executions).isEqualTo(1L);
    }

    @Test
    @DisplayName("幂等键复用于不同内容 -> KEY_CONFLICT；非法金额 -> INVALID_PARAMETER；缺键 -> MISSING_KEY，错误可区分")
    void conflictAndParamErrorsAreDistinct() {
        gateway.pay("tenant-a", "KEY-2", "ORD-2", 500L, "ok", "R-1");

        Throwable conflict = catchThrowable(() ->
                gateway.pay("tenant-a", "KEY-2", "ORD-2", 999L, "ok", "R-2"));
        Throwable badParam = catchThrowable(() ->
                gateway.pay("tenant-a", "KEY-3", "ORD-3", 0L, "ok", "R-3"));
        Throwable missingKey = catchThrowable(() ->
                gateway.pay("tenant-a", null, "ORD-4", 10L, "ok", "R-4"));

        log.info("错误区分 tenant=tenant-a 输入=同键不同金额 判定={} http={}",
                ((GatewayException) conflict).getErrorCode().getCode(),
                ((GatewayException) conflict).getErrorCode().getHttpStatus());
        log.info("错误区分 tenant=tenant-a 输入=amount=0 判定={} http={}",
                ((GatewayException) badParam).getErrorCode().getCode(),
                ((GatewayException) badParam).getErrorCode().getHttpStatus());
        log.info("错误区分 tenant=tenant-a 输入=缺少Idempotency-Key 判定={} http={}",
                ((GatewayException) missingKey).getErrorCode().getCode(),
                ((GatewayException) missingKey).getErrorCode().getHttpStatus());

        assertThat(((GatewayException) conflict).getErrorCode())
                .isEqualTo(GatewayErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(((GatewayException) badParam).getErrorCode())
                .isEqualTo(GatewayErrorCode.INVALID_PARAMETER);
        assertThat(((GatewayException) missingKey).getErrorCode())
                .isEqualTo(GatewayErrorCode.MISSING_IDEMPOTENCY_KEY);
        // 键冲突不产生第二次扣减
        assertThat(ledger.getBalance("tenant-a")).isEqualTo(10_000L - 500L);
    }

    /** 首个请求进入上游后阻塞，直到测试显式放行，保证同键并发时首个确定在途。 */
    private static final class BlockingUpstream extends MockUpstreamClient {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        BlockingUpstream(TenantProperties props, BalanceLedger ledger,
                         CountDownLatch entered, CountDownLatch release) {
            super(props, ledger);
            this.entered = entered;
            this.release = release;
        }

        @Override
        public UpstreamResponse pay(String tenantId, String orderId, long amount, String scenario) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.pay(tenantId, orderId, amount, MockUpstreamClient.SCENARIO_OK);
        }
    }

    @Test
    @DisplayName("同键并发重复：首个执行中，另一个得到 IDEMPOTENCY_IN_PROGRESS，之后再提交得到 REPLAY")
    void concurrentDuplicateSeesInProgressThenReplay() throws Exception {
        // 重建一套带阻塞上游的网关
        TenantProperties props = properties();
        QuotaService quotaService = new QuotaService(clock, props);
        IdempotencyService idempotencyService = new IdempotencyService(clock);
        TenantResolver resolver = new TenantResolver(props);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MockUpstreamClient blocking = new BlockingUpstream(props, ledger, entered, release);
        AuditService blockingAudit = new AuditService(clock);
        GatewayService blockingGateway = new GatewayService(resolver, idempotencyService,
                quotaService, blocking, blockingAudit, new ObjectMapper());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> secondError = new AtomicReference<>();

        pool.submit(() -> blockingGateway.pay("tenant-a", "KEY-4", "ORD-4", 2_000L, "ok", "R-1"));
        // 等待首个请求确定进入上游（幂等键已占位、在途执行中）
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        pool.submit(() -> secondError.set(catchThrowable(() ->
                blockingGateway.pay("tenant-a", "KEY-4", "ORD-4", 2_000L, "ok", "R-2"))));

        Throwable captured = null;
        long deadline = System.currentTimeMillis() + 3_000L;
        while (captured == null && System.currentTimeMillis() < deadline) {
            captured = secondError.get();
            if (captured == null) {
                Thread.sleep(20);
            }
        }
        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(captured).isInstanceOf(GatewayException.class);
        GatewayErrorCode concurrentCode = ((GatewayException) captured).getErrorCode();
        log.info("并发重复 tenant=tenant-a 输入=同键并发第二次 判定={} http={} 可重试={}",
                concurrentCode.getCode(), concurrentCode.getHttpStatus(), concurrentCode.isRetryable());
        assertThat(concurrentCode).isEqualTo(GatewayErrorCode.IDEMPOTENCY_IN_PROGRESS);

        GatewayResponse after = blockingGateway.pay("tenant-a", "KEY-4", "ORD-4", 2_000L, "ok", "R-3");
        log.info("并发重复 tenant=tenant-a 输入=完成后同键重提 判定={} replayed={} 余额={}",
                after.decision(), after.replayed(), ledger.getBalance("tenant-a"));
        assertThat(after.decision()).isEqualTo("REPLAY_OK");
        assertThat(ledger.getBalance("tenant-a")).isEqualTo(10_000L - 2_000L);
    }
}
