package com.github.h3yow.tenantapigatewayjava.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.tenantapigatewayjava.audit.AuditRecord;
import com.github.h3yow.tenantapigatewayjava.audit.AuditService;
import com.github.h3yow.tenantapigatewayjava.config.TenantProperties;
import com.github.h3yow.tenantapigatewayjava.error.GatewayErrorCode;
import com.github.h3yow.tenantapigatewayjava.error.GatewayException;
import com.github.h3yow.tenantapigatewayjava.gateway.GatewayService;
import com.github.h3yow.tenantapigatewayjava.idem.IdempotencyService;
import com.github.h3yow.tenantapigatewayjava.ledger.BalanceLedger;
import com.github.h3yow.tenantapigatewayjava.quota.QuotaService;
import com.github.h3yow.tenantapigatewayjava.tenant.TenantResolver;
import com.github.h3yow.tenantapigatewayjava.tests.support.MutableClock;
import com.github.h3yow.tenantapigatewayjava.upstream.MockUpstreamClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 上游异常归一化：超时/连接失败/畸形响应/业务错误映射稳定、可区分，原始异常不透出。 */
class UpstreamNormalizationTest {

    private static final Logger log = LoggerFactory.getLogger(UpstreamNormalizationTest.class);

    private MutableClock clock;
    private GatewayService gateway;
    private AuditService auditService;
    private BalanceLedger ledger;

    private static TenantProperties properties() {
        TenantProperties props = new TenantProperties();
        TenantProperties.TenantConfig cfg = new TenantProperties.TenantConfig();
        cfg.setWindowSeconds(60);
        cfg.setQuotaLimit(1000);
        cfg.setConcurrencyLimit(1000);
        cfg.setInitialBalance(10_000L);
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
    @DisplayName("四类上游异常映射为四种稳定可区分的错误码与 HTTP 状态码，且响应不含上游原始信息")
    void upstreamFailuresMapToStableCodes() {
        assertMapping("timeout", GatewayErrorCode.UPSTREAM_TIMEOUT, 504, true);
        assertMapping("connection_failed", GatewayErrorCode.UPSTREAM_UNAVAILABLE, 502, true);
        assertMapping("malformed", GatewayErrorCode.UPSTREAM_BAD_RESPONSE, 502, false);
        assertMapping("business_error", GatewayErrorCode.UPSTREAM_BUSINESS_REJECTED, 422, false);

        // 余额不足走业务错误：不扣款
        long before = ledger.getBalance("tenant-a");
        Throwable over = catchThrowable(() ->
                gateway.pay("tenant-a", "KEY-OVER", "ORD-OVER", 1_000_000L, "ok", "R-X"));
        GatewayErrorCode overCode = ((GatewayException) over).getErrorCode();
        log.info("归一化 tenant=tenant-a 输入=超额支付 amount=1000000 判定={} http={} 余额保持={}",
                overCode.getCode(), overCode.getHttpStatus(), ledger.getBalance("tenant-a"));
        assertThat(overCode).isEqualTo(GatewayErrorCode.UPSTREAM_BUSINESS_REJECTED);
        assertThat(ledger.getBalance("tenant-a")).isEqualTo(before);
    }

    private void assertMapping(String scenario, GatewayErrorCode expected, int http,
                               boolean retryable) {
        Throwable t = catchThrowable(() -> gateway.pay("tenant-a",
                "KEY-" + scenario, "ORD-" + scenario, 100L, scenario, "R-" + scenario));
        assertThat(t).isInstanceOf(GatewayException.class);
        GatewayException ge = (GatewayException) t;
        GatewayErrorCode code = ge.getErrorCode();
        AuditRecord rec = auditService.all().stream()
                .filter(r -> scenario.equals(r.requestSummary()
                        .replaceAll(".*scenario=([a-z_]+).*", "$1")))
                .reduce((a, b) -> b).orElseThrow();
        log.info("归一化 tenant=tenant-a 输入=scenario={} 判定={} http={} 可重试={} 审计上游类型={} 原始消息是否透出={}",
                scenario, code.getCode(), code.getHttpStatus(), code.isRetryable(),
                rec.upstreamFailureType(), String.valueOf(ge.getMessage()).contains("UP_"));
        assertThat(code).isEqualTo(expected);
        assertThat(code.getHttpStatus()).isEqualTo(http);
        assertThat(code.isRetryable()).isEqualTo(retryable);
        // 原始上游 code（UP_xxx）不得出现在对外消息中
        assertThat(ge.getMessage()).doesNotContain("UP_");
        // 审计中保留归一化前的故障类别，可还原判定依据
        assertThat(rec.upstreamFailureType()).isNotNull();
        assertThat(rec.reasonCode()).isEqualTo(code.getCode());
    }

    @Test
    @DisplayName("首次被上游拒绝后，同键重试折叠为错误重放，不再次调用上游、不重复尝试扣减")
    void upstreamErrorIsFoldedByReplay() {
        long before = ledger.getBalance("tenant-a");
        Throwable first = catchThrowable(() ->
                gateway.pay("tenant-a", "KEY-T", "ORD-T", 100L, "timeout", "R-1"));
        assertThat(((GatewayException) first).getErrorCode())
                .isEqualTo(GatewayErrorCode.UPSTREAM_TIMEOUT);

        Throwable replay = catchThrowable(() ->
                gateway.pay("tenant-a", "KEY-T", "ORD-T", 100L, "timeout", "R-2"));
        GatewayErrorCode replayCode = ((GatewayException) replay).getErrorCode();
        log.info("错误重放 tenant=tenant-a 输入=同键再次提交 判定={} http={} 余额={}",
                replayCode.getCode(), replayCode.getHttpStatus(), ledger.getBalance("tenant-a"));
        assertThat(replayCode).isEqualTo(GatewayErrorCode.UPSTREAM_REJECTED_BY_IDEMPOTENT_RETRY);
        assertThat(((GatewayException) replay).getMessage()).contains("UPSTREAM_TIMEOUT");
        assertThat(ledger.getBalance("tenant-a")).isEqualTo(before);
        long timeouts = auditService.all().stream()
                .filter(r -> "TIMEOUT".equals(r.upstreamFailureType())).count();
        assertThat(timeouts).isEqualTo(1L); // 上游只被实际调用一次
    }

    @Test
    @DisplayName("读入口异常同样归一化，且不消耗幂等键")
    void queryPathAlsoNormalized() {
        Throwable t = catchThrowable(() ->
                gateway.query("tenant-a", "ORD-Q", "connection_failed", "R-Q"));
        GatewayErrorCode code = ((GatewayException) t).getErrorCode();
        log.info("归一化(读) tenant=tenant-a 输入=query/scenario=connection_failed 判定={} http={}",
                code.getCode(), code.getHttpStatus());
        assertThat(code).isEqualTo(GatewayErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(code.getHttpStatus()).isEqualTo(502);
    }
}
