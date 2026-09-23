package com.github.h3yow.tenantapigatewayjava.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.h3yow.tenantapigatewayjava.config.TenantProperties;
import com.github.h3yow.tenantapigatewayjava.quota.QuotaResult;
import com.github.h3yow.tenantapigatewayjava.quota.QuotaService;
import com.github.h3yow.tenantapigatewayjava.quota.QuotaService.QuotaDecision;
import com.github.h3yow.tenantapigatewayjava.tests.support.MutableClock;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 配额边界：窗口上限内放行、第 limit+1 次拒绝、窗口滚动后重新放行。 */
class QuotaServiceTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaServiceTest.class);

    private MutableClock clock;
    private QuotaService quotaService;

    private static TenantProperties properties(int windowSeconds, int quotaLimit, int concurrency) {
        TenantProperties props = new TenantProperties();
        TenantProperties.TenantConfig cfg = new TenantProperties.TenantConfig();
        cfg.setWindowSeconds(windowSeconds);
        cfg.setQuotaLimit(quotaLimit);
        cfg.setConcurrencyLimit(concurrency);
        Map<String, TenantProperties.TenantConfig> tenants = new java.util.LinkedHashMap<>();
        tenants.put("tenant-a", cfg);
        props.setTenants(tenants);
        return props;
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        quotaService = new QuotaService(clock, properties(10, 3, 10));
    }

    @Test
    @DisplayName("窗口内恰好放行 quotaLimit 次，第 limit+1 次以 QUOTA_EXCEEDED 拒绝，计数不丢失")
    void boundary_thenRejectWithDistinctReason() {
        for (int i = 1; i <= 3; i++) {
            QuotaDecision d = quotaService.acquire("tenant-a");
            log.info("配额边界 tenant=tenant-a window=[{},{}) 输入=第{}次请求 判定={} 依据=consumed={}/{}",
                    d.windowStartEpoch(), d.windowEndEpoch(), i, d.result(),
                    d.consumedInWindow(), d.quotaLimit());
            assertThat(d.result()).isEqualTo(QuotaResult.ALLOWED);
            assertThat(d.consumedInWindow()).isEqualTo(i);
            quotaService.release("tenant-a");
        }
        QuotaDecision rejected = quotaService.acquire("tenant-a");
        log.info("配额边界 tenant=tenant-a window=[{},{}) 输入=第4次请求 判定={} 依据=consumed={}/{}",
                rejected.windowStartEpoch(), rejected.windowEndEpoch(), rejected.result(),
                rejected.consumedInWindow(), rejected.quotaLimit());
        assertThat(rejected.result()).isEqualTo(QuotaResult.QUOTA_EXCEEDED);
        // 拒绝不产生计数：仍是 3
        assertThat(rejected.consumedInWindow()).isEqualTo(3);
    }

    @Test
    @DisplayName("窗口滚动后计数归零并重新放行")
    void windowRollsOver() {
        for (int i = 0; i < 3; i++) {
            assertThat(quotaService.acquire("tenant-a").result()).isEqualTo(QuotaResult.ALLOWED);
            quotaService.release("tenant-a");
        }
        clock.advanceSeconds(10);
        QuotaDecision d = quotaService.acquire("tenant-a");
        log.info("窗口滚动 tenant=tenant-a 输入=滚动后首次 判定={} 依据=新窗口[},{}) consumed={}/{}",
                d.result(), d.windowStartEpoch(), d.windowEndEpoch(),
                d.consumedInWindow(), d.quotaLimit());
        assertThat(d.result()).isEqualTo(QuotaResult.ALLOWED);
        assertThat(d.consumedInWindow()).isEqualTo(1);
        quotaService.release("tenant-a");
    }

    @Test
    @DisplayName("在途并发占满时以 CONCURRENCY_EXCEEDED 拒绝，与配额拒绝原因可区分")
    void concurrencyLimitDistinctFromQuota() {
        QuotaService svc = new QuotaService(clock, properties(10, 100, 1));
        QuotaDecision first = svc.acquire("tenant-a");
        assertThat(first.result()).isEqualTo(QuotaResult.ALLOWED);
        QuotaDecision second = svc.acquire("tenant-a");
        log.info("并发边界 tenant=tenant-a 输入=第二个在途请求 判定={} 依据=inflight={}/{} quotaConsumed={}/{}",
                second.result(), second.inflight(), second.concurrencyLimit(),
                second.consumedInWindow(), second.quotaLimit());
        assertThat(second.result()).isEqualTo(QuotaResult.CONCURRENCY_EXCEEDED);
        svc.release("tenant-a");
        QuotaDecision third = svc.acquire("tenant-a");
        assertThat(third.result()).isEqualTo(QuotaResult.ALLOWED);
        svc.release("tenant-a");
    }
}
