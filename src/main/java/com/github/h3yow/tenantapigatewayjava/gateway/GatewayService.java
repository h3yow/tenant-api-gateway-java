package com.github.h3yow.tenantapigatewayjava.gateway;

import com.github.h3yow.tenantapigatewayjava.circuit.Admission;
import com.github.h3yow.tenantapigatewayjava.circuit.CircuitBreakerRegistry;
import com.github.h3yow.tenantapigatewayjava.circuit.TenantCircuitBreaker;
import com.github.h3yow.tenantapigatewayjava.concurrency.ConcurrencyLimiter;
import com.github.h3yow.tenantapigatewayjava.idempotency.IdempotencyStore;
import com.github.h3yow.tenantapigatewayjava.model.DecisionCode;
import com.github.h3yow.tenantapigatewayjava.model.GatewayRequest;
import com.github.h3yow.tenantapigatewayjava.model.GatewayResponse;
import com.github.h3yow.tenantapigatewayjava.quota.QuotaLimiter;
import com.github.h3yow.tenantapigatewayjava.upstream.UpstreamExceptionNormalizer;
import com.github.h3yow.tenantapigatewayjava.upstream.UpstreamInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 网关编排。处理顺序（既有入口与调用约定不变）：
 * <pre>
 *   1. 幂等折叠：已落定终态且同键同内容 -> 直接重放，不经过熔断/配额/并发，也不调用上游
 *                同键不同内容 -> 幂等冲突拒绝
 *   2. 租户熔断准入：OPEN/HALF_OPEN 下非探测请求快速失败（不占配额、不在途、不暴露上游异常）
 *   3. 窗口配额：只有真正要调上游的请求才占名额；之后若被并发挡下则归还
 *   4. 在途并发：达上限快速失败并归还配额
 *   5. 调用上游：成功写幂等终态；失败记熔断计数并归一化错误
 * </pre>
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final QuotaLimiter quotaLimiter;
    private final ConcurrencyLimiter concurrencyLimiter;
    private final IdempotencyStore idempotencyStore;
    private final UpstreamInvoker upstreamInvoker;
    private final UpstreamExceptionNormalizer normalizer;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final GatewayProperties properties;

    public GatewayService(QuotaLimiter quotaLimiter,
                          ConcurrencyLimiter concurrencyLimiter,
                          IdempotencyStore idempotencyStore,
                          UpstreamInvoker upstreamInvoker,
                          UpstreamExceptionNormalizer normalizer,
                          CircuitBreakerRegistry circuitBreakerRegistry,
                          GatewayProperties properties) {
        this.quotaLimiter = quotaLimiter;
        this.concurrencyLimiter = concurrencyLimiter;
        this.idempotencyStore = idempotencyStore;
        this.upstreamInvoker = upstreamInvoker;
        this.normalizer = normalizer;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.properties = properties;
    }

    public GatewayResponse handle(GatewayRequest request) {
        String tenantId = request.tenantId();
        String key = request.idempotencyKey();
        String payload = request.payload();
        boolean hasKey = key != null && !key.isBlank();

        // 1. 幂等折叠：终态重放优先于一切（含熔断打开期间）。
        if (hasKey) {
            IdempotencyStore.Lookup lookup = idempotencyStore.lookup(tenantId, key, payload);
            if (lookup.status() == IdempotencyStore.LookupStatus.SETTLED) {
                if (!lookup.samePayload()) {
                    return auditAndReturn(request, DecisionCode.REJECT_IDEMPOTENT_CONFLICT,
                            GatewayResponse.of(DecisionCode.REJECT_IDEMPOTENT_CONFLICT,
                                    "IDEMPOTENT_KEY_PAYLOAD_CONFLICT", false, null, null));
                }
                return auditAndReturn(request, DecisionCode.IDEMPOTENT_REPLAY,
                        GatewayResponse.of(DecisionCode.IDEMPOTENT_REPLAY, "IDEMPOTENT_REPLAY",
                                false, null, lookup.result()));
            }
            if (lookup.status() == IdempotencyStore.LookupStatus.IN_PROGRESS) {
                if (!lookup.samePayload()) {
                    return auditAndReturn(request, DecisionCode.REJECT_IDEMPOTENT_CONFLICT,
                            GatewayResponse.of(DecisionCode.REJECT_IDEMPOTENT_CONFLICT,
                                    "IDEMPOTENT_KEY_PAYLOAD_CONFLICT", false, null, null));
                }
                // 同键同内容已有请求在途：不重复打上游，也不消耗任何网关资源。
                return auditAndReturn(request, DecisionCode.REJECT_IDEMPOTENT_IN_PROGRESS,
                        GatewayResponse.of(DecisionCode.REJECT_IDEMPOTENT_IN_PROGRESS,
                                "IDEMPOTENT_IN_PROGRESS", true, 1L, null));
            }
        }

        TenantCircuitBreaker breaker = circuitBreakerRegistry.forTenant(tenantId);

        // 2. 租户熔断准入。
        Admission admission = breaker.admit();
        if (!admission.permitted()) {
            DecisionCode code = admission.probeFailurePenalty()
                    ? DecisionCode.REJECT_PROBE_FAILED : DecisionCode.REJECT_CIRCUIT_OPEN;
            String reason = admission.probeFailurePenalty()
                    ? "CIRCUIT_PROBE_FAILED" : "CIRCUIT_OPEN";
            long retryAfterSeconds = (admission.retryAfterMillis() + 999L) / 1000L;
            return auditAndReturn(request, code,
                    GatewayResponse.of(code, reason, true, retryAfterSeconds, null));
        }

        // 3. 窗口配额（只有真正要调上游的请求才消耗）。
        if (!quotaLimiter.tryAcquire(tenantId, properties.getWindowSeconds(), properties.getWindowLimit())) {
            return auditAndReturn(request, DecisionCode.REJECT_QUOTA_EXHAUSTED,
                    GatewayResponse.of(DecisionCode.REJECT_QUOTA_EXHAUSTED, "QUOTA_EXHAUSTED",
                            true, (long) properties.getWindowSeconds(), null));
        }

        // 4. 在途并发（拿不到名额要归还刚刚占用的窗口配额）。
        if (!concurrencyLimiter.tryAcquire(tenantId, properties.getMaxConcurrent())) {
            quotaLimiter.release(tenantId, properties.getWindowSeconds());
            return auditAndReturn(request, DecisionCode.REJECT_CONCURRENCY_LIMITED,
                    GatewayResponse.of(DecisionCode.REJECT_CONCURRENCY_LIMITED, "CONCURRENCY_LIMITED",
                            true, 1L, null));
        }

        boolean markedInProgress = false;
        if (hasKey && idempotencyStore.markInProgress(tenantId, key, payload)) {
            markedInProgress = true;
        }

        try {
            String result;
            try {
                result = upstreamInvoker.invoke(tenantId, request.data());
            } catch (Exception error) {
                // 5a. 上游失败：记熔断计数，归一化错误，绝不把原始异常抛给调用方。
                breaker.recordFailure(admission.probe());
                if (markedInProgress) {
                    idempotencyStore.clearInProgress(tenantId, key);
                }
                String normalized = normalizer.normalize(error);
                DecisionCode code = admission.probe()
                        ? DecisionCode.REJECT_PROBE_FAILED : DecisionCode.UPSTREAM_ERROR;
                String reason = admission.probe() ? "CIRCUIT_PROBE_FAILED" : normalized;
                return auditAndReturn(request, code,
                        GatewayResponse.of(code, reason, true,
                                admission.probe() ? (properties.getOpenDurationMillis() + 999L) / 1000L : null,
                                null));
            }

            // 5b. 上游成功：探测成功整体恢复；写幂等终态。
            breaker.recordSuccess(admission.probe());
            if (markedInProgress) {
                idempotencyStore.settle(tenantId, key, payload, result);
            }
            DecisionCode code = admission.probe() ? DecisionCode.PASS_PROBE : DecisionCode.PASS;
            return auditAndReturn(request, code,
                    GatewayResponse.of(code, admission.probe() ? "PASS_PROBE" : "OK",
                            false, null, result));
        } finally {
            concurrencyLimiter.release(tenantId);
        }
    }

    private GatewayResponse auditAndReturn(GatewayRequest request, DecisionCode decision,
                                           GatewayResponse response) {
        log.info("gateway-audit tenant={} windowSeconds={} idempotencyKey={} payload={} data={} decision={} reason={} retryable={} retryAfterSeconds={}",
                request.tenantId(), properties.getWindowSeconds(),
                request.idempotencyKey(), request.payload(), request.data(),
                decision, response.reason(), response.retryable(), response.retryAfterSeconds());
        return response;
    }
}
