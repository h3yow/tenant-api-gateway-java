package com.github.h3yow.tenantapigatewayjava.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.tenantapigatewayjava.audit.AuditRecord;
import com.github.h3yow.tenantapigatewayjava.audit.AuditService;
import com.github.h3yow.tenantapigatewayjava.error.GatewayErrorCode;
import com.github.h3yow.tenantapigatewayjava.error.GatewayException;
import com.github.h3yow.tenantapigatewayjava.idem.IdempotencyService;
import com.github.h3yow.tenantapigatewayjava.idem.IdempotencyService.IdempotencyOutcome;
import com.github.h3yow.tenantapigatewayjava.idem.IdempotencyService.IdempotencyRecord;
import com.github.h3yow.tenantapigatewayjava.quota.QuotaResult;
import com.github.h3yow.tenantapigatewayjava.quota.QuotaService;
import com.github.h3yow.tenantapigatewayjava.quota.QuotaService.QuotaDecision;
import com.github.h3yow.tenantapigatewayjava.tenant.TenantResolver;
import com.github.h3yow.tenantapigatewayjava.upstream.MockUpstreamClient;
import com.github.h3yow.tenantapigatewayjava.upstream.UpstreamException;
import com.github.h3yow.tenantapigatewayjava.upstream.UpstreamFailureType;
import com.github.h3yow.tenantapigatewayjava.upstream.UpstreamResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 网关编排：
 * 租户解析 -> 幂等折叠（写） -> 配额/并发闸口 -> 模拟上游 -> 异常归一化 -> 审计。
 *
 * <p>写操作顺序保证：幂等判定先于配额计数，因此同键重复请求在窗口配额耗尽后仍能
 * 折叠重放；只有首次请求才实际调用上游并扣减余额。
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    static final String ENTRY_PAYMENT = "payment";
    static final String ENTRY_QUERY = "query";

    private final TenantResolver tenantResolver;
    private final IdempotencyService idempotencyService;
    private final QuotaService quotaService;
    private final MockUpstreamClient upstreamClient;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public GatewayService(TenantResolver tenantResolver,
                          IdempotencyService idempotencyService,
                          QuotaService quotaService,
                          MockUpstreamClient upstreamClient,
                          AuditService auditService,
                          ObjectMapper objectMapper) {
        this.tenantResolver = tenantResolver;
        this.idempotencyService = idempotencyService;
        this.quotaService = quotaService;
        this.upstreamClient = upstreamClient;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** 写入口：支付（安全重试 + 幂等折叠）。 */
    public GatewayResponse pay(String tenantHeader, String idempotencyKey, String orderId,
                               Long amount, String scenario, String requestIdHeader) {
        String requestId = requestIdHeader == null || requestIdHeader.isBlank()
                ? "REQ-" + UUID.randomUUID() : requestIdHeader.trim();
        long start = System.currentTimeMillis();
        String tenantId;
        String windowInfo = null;
        String key = idempotencyKey == null ? null : idempotencyKey.trim();
        String input = "pay{orderId=" + orderId + ",amount=" + amount + ",scenario=" + scenario + "}";

        try {
            // 1) 租户解析与参数校验（先于一切计数）
            tenantId = tenantResolver.resolve(tenantHeader);
            validatePayment(orderId, amount, key);
            String fingerprint = fingerprint(orderId, amount);

            // 2) 幂等折叠：重复提交不实际执行、不重复扣减
            IdempotencyOutcome outcome = idempotencyService.acquire(tenantId, key, fingerprint);
            switch (outcome.state()) {
                case CONFLICT -> {
                    audit(tenantId, ENTRY_PAYMENT, key, fingerprint, 0L, 0L, "REJECTED",
                            GatewayErrorCode.IDEMPOTENCY_KEY_CONFLICT.getCode(),
                            null, null, input, requestId, start);
                    throw new GatewayException(GatewayErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                            "幂等键 " + key + " 已用于不同的订单/金额，请更换新的幂等键", null);
                }
                case IN_PROGRESS -> {
                    audit(tenantId, ENTRY_PAYMENT, key, fingerprint, 0L, 0L, "REJECTED",
                            GatewayErrorCode.IDEMPOTENCY_IN_PROGRESS.getCode(),
                            null, null, input, requestId, start);
                    throw new GatewayException(GatewayErrorCode.IDEMPOTENCY_IN_PROGRESS,
                            "幂等键 " + key + " 的首次请求仍在处理中，本次未执行",
                            "请稍后（建议退避 ≥300ms）携带相同 Idempotency-Key 重试以获取终态结果");
                }
                case REPLAY -> {
                    IdempotencyRecord rec = outcome.existing().orElseThrow();
                    audit(tenantId, ENTRY_PAYMENT, key, fingerprint, 0L, 0L,
                            rec.isError() ? "REPLAY_ERROR" : "REPLAY_OK",
                            rec.isError() ? GatewayErrorCode.UPSTREAM_REJECTED_BY_IDEMPOTENT_RETRY.getCode()
                                    : "REPLAY",
                            rec.upstreamErrorCode(), null, input, requestId, start);
                    return replayResponse(tenantId, key, rec, requestId);
                }
                case ABSENT -> { /* 首次，继续走闸口 */ }
            }

            // 3) 配额 / 在途并发闸口
            QuotaDecision qd = quotaService.acquire(tenantId);
            windowInfo = windowInfo(qd);
            if (qd.result() != QuotaResult.ALLOWED) {
                GatewayErrorCode ec = qd.result() == QuotaResult.QUOTA_EXCEEDED
                        ? GatewayErrorCode.QUOTA_EXCEEDED
                        : GatewayErrorCode.CONCURRENCY_EXCEEDED;
                long retryAfter = Math.max(qd.windowEndEpoch() - System.currentTimeMillis() / 1000L, 0L);
                String hint = ec == GatewayErrorCode.QUOTA_EXCEEDED
                        ? ("窗口在 " + retryAfter + " 秒后重置，之后可重试；"
                                + "已消耗 " + qd.consumedInWindow() + "/" + qd.quotaLimit())
                        : ("请稍后重试（建议退避 ≥300ms）；当前在途 "
                                + qd.inflight() + "/" + qd.concurrencyLimit());
                audit(tenantId, ENTRY_PAYMENT, key, fingerprint, qd.windowStartEpoch(),
                        qd.windowEndEpoch(), "REJECTED", ec.getCode(), null, null,
                        input, requestId, start);
                throw new GatewayException(ec, ec.getMessage(), hint);
            }

            // 4) 调用模拟上游；无论成功失败都释放并发、落定幂等终态、写审计
            long createdAt = qd.windowStartEpoch();
            try {
                UpstreamResponse upstream = upstreamClient.pay(tenantId, orderId, amount, scenario);
                Object data = parseBody(upstream.bodyJson());
                IdempotencyRecord rec = IdempotencyRecord.success(
                        fingerprint, upstream.bodyJson(), 200,
                        createdAt, System.currentTimeMillis() / 1000L, requestId);
                idempotencyService.complete(tenantId, key, rec);
                audit(tenantId, ENTRY_PAYMENT, key, fingerprint, qd.windowStartEpoch(),
                        qd.windowEndEpoch(), "ALLOWED_UPSTREAM_OK", "ALLOWED",
                        null, upstream.upstreamTxnId(), input, requestId, start);
                return new GatewayResponse(requestId, tenantId, ENTRY_PAYMENT, "ALLOWED_UPSTREAM_OK",
                        false, upstream.upstreamTxnId(), upstream.status(), data, windowInfo);
            } catch (UpstreamException ue) {
                GatewayErrorCode ec = mapUpstream(ue.getFailureType());
                IdempotencyRecord rec = IdempotencyRecord.error(
                        fingerprint, "{}", ec.getCode(), ec.getHttpStatus(),
                        createdAt, System.currentTimeMillis() / 1000L, requestId);
                idempotencyService.complete(tenantId, key, rec);
                audit(tenantId, ENTRY_PAYMENT, key, fingerprint, qd.windowStartEpoch(),
                        qd.windowEndEpoch(), "REJECTED", ec.getCode(),
                        ue.getFailureType().name(), null, input, requestId, start);
                throw new GatewayException(ec, ec.getMessage(), upstreamRetryHint(ec));
            } finally {
                quotaService.release(tenantId);
            }
        } catch (GatewayException ge) {
            throw ge.withRequestId(requestId);
        } catch (RuntimeException re) {
            log.error("unexpected orchestration error requestId={}", requestId, re);
            String tid = safeResolve(tenantHeader);
            audit(tid, ENTRY_PAYMENT, key, null, 0L, 0L, "REJECTED",
                    GatewayErrorCode.INTERNAL_ERROR.getCode(), null, null,
                    input, requestId, start);
            throw new GatewayException(GatewayErrorCode.INTERNAL_ERROR,
                    GatewayErrorCode.INTERNAL_ERROR.getMessage(),
                    "请使用新的请求稍后重试；如持续失败请联系网关管理员", requestId);
        }
    }

    /** 读入口：查询（仅过配额闸口，不做幂等）。 */
    public GatewayResponse query(String tenantHeader, String orderId, String scenario,
                                 String requestIdHeader) {
        String requestId = requestIdHeader == null || requestIdHeader.isBlank()
                ? "REQ-" + UUID.randomUUID() : requestIdHeader.trim();
        long start = System.currentTimeMillis();
        String input = "query{orderId=" + orderId + ",scenario=" + scenario + "}";
        try {
            String tenantId = tenantResolver.resolve(tenantHeader);
            if (orderId == null || orderId.isBlank()) {
                throw new GatewayException(GatewayErrorCode.INVALID_PARAMETER,
                        "orderId 不能为空", null);
            }
            QuotaDecision qd = quotaService.acquire(tenantId);
            String windowInfo = windowInfo(qd);
            if (qd.result() != QuotaResult.ALLOWED) {
                GatewayErrorCode ec = qd.result() == QuotaResult.QUOTA_EXCEEDED
                        ? GatewayErrorCode.QUOTA_EXCEEDED
                        : GatewayErrorCode.CONCURRENCY_EXCEEDED;
                long retryAfter = Math.max(qd.windowEndEpoch() - System.currentTimeMillis() / 1000L, 0L);
                String hint = ec == GatewayErrorCode.QUOTA_EXCEEDED
                        ? ("窗口在 " + Math.max(retryAfter, 0) + " 秒后重置，之后可重试；已消耗 "
                                + qd.consumedInWindow() + "/" + qd.quotaLimit())
                        : ("请稍后重试（建议退避 ≥300ms）；当前在途 "
                                + qd.inflight() + "/" + qd.concurrencyLimit());
                audit(tenantId, ENTRY_QUERY, null, null, qd.windowStartEpoch(),
                        qd.windowEndEpoch(), "REJECTED", ec.getCode(), null, null,
                        input, requestId, start);
                throw new GatewayException(ec, ec.getMessage(), hint);
            }
            try {
                UpstreamResponse upstream = upstreamClient.query(tenantId, orderId, scenario);
                Object data = parseBody(upstream.bodyJson());
                audit(tenantId, ENTRY_QUERY, null, null, qd.windowStartEpoch(),
                        qd.windowEndEpoch(), "ALLOWED_UPSTREAM_OK", "ALLOWED",
                        null, upstream.upstreamTxnId(), input, requestId, start);
                return new GatewayResponse(requestId, tenantId, ENTRY_QUERY, "ALLOWED_UPSTREAM_OK",
                        false, upstream.upstreamTxnId(), upstream.status(), data, windowInfo);
            } catch (UpstreamException ue) {
                GatewayErrorCode ec = mapUpstream(ue.getFailureType());
                audit(tenantId, ENTRY_QUERY, null, null, qd.windowStartEpoch(),
                        qd.windowEndEpoch(), "REJECTED", ec.getCode(),
                        ue.getFailureType().name(), null, input, requestId, start);
                throw new GatewayException(ec, ec.getMessage(), upstreamRetryHint(ec));
            } finally {
                quotaService.release(tenantId);
            }
        } catch (GatewayException ge) {
            throw ge.withRequestId(requestId);
        } catch (RuntimeException re) {
            log.error("unexpected orchestration error requestId={}", requestId, re);
            audit(safeResolve(tenantHeader), ENTRY_QUERY, null, null, 0L, 0L, "REJECTED",
                    GatewayErrorCode.INTERNAL_ERROR.getCode(), null, null,
                    input, requestId, start);
            throw new GatewayException(GatewayErrorCode.INTERNAL_ERROR,
                    GatewayErrorCode.INTERNAL_ERROR.getMessage(),
                    "请使用新的请求稍后重试；如持续失败请联系网关管理员", requestId);
        }
    }

    private static void validatePayment(String orderId, Long amount, String key) {
        if (key == null || key.isBlank()) {
            throw new GatewayException(GatewayErrorCode.MISSING_IDEMPOTENCY_KEY,
                    "写操作必须携带 Idempotency-Key 请求头", null);
        }
        if (orderId == null || orderId.isBlank()) {
            throw new GatewayException(GatewayErrorCode.INVALID_PARAMETER,
                    "orderId 不能为空", null);
        }
        if (amount == null || amount <= 0) {
            throw new GatewayException(GatewayErrorCode.INVALID_PARAMETER,
                    "amount 必须为正数（实际: " + amount + "）", null);
        }
    }

    private static String fingerprint(String orderId, long amount) {
        return "pay:" + orderId + ":" + amount;
    }

    private static GatewayErrorCode mapUpstream(UpstreamFailureType type) {
        return switch (type) {
            case TIMEOUT -> GatewayErrorCode.UPSTREAM_TIMEOUT;
            case CONNECTION_FAILED -> GatewayErrorCode.UPSTREAM_UNAVAILABLE;
            case MALFORMED_RESPONSE -> GatewayErrorCode.UPSTREAM_BAD_RESPONSE;
            case BUSINESS_ERROR -> GatewayErrorCode.UPSTREAM_BUSINESS_REJECTED;
        };
    }

    private static String upstreamRetryHint(GatewayErrorCode ec) {
        return switch (ec) {
            case UPSTREAM_TIMEOUT -> "上游超时，可安全重试写操作：请携带相同的 Idempotency-Key";
            case UPSTREAM_UNAVAILABLE -> "上游暂不可用，可携带相同 Idempotency-Key 稍后重试";
            case UPSTREAM_BAD_RESPONSE -> "上游响应无法解析，请勿使用原键盲目重试，请核实订单状态后换新键";
            case UPSTREAM_BUSINESS_REJECTED -> "业务被拒绝，请修正请求参数后使用新的 Idempotency-Key";
            default -> null;
        };
    }

    private GatewayResponse replayResponse(String tenantId, String key, IdempotencyRecord rec,
                                           String requestId) {
        if (rec.isError()) {
            // 错误重放：给出可区分错误，不再次调用上游
            GatewayErrorCode ec = GatewayErrorCode.UPSTREAM_REJECTED_BY_IDEMPOTENT_RETRY;
            throw new GatewayException(ec,
                    "幂等键 " + key + " 的首次请求已被上游拒绝（归一化原因: "
                            + rec.upstreamErrorCode() + "），不会重复执行；"
                            + "修正参数后请改用新的幂等键",
                    null);
        }
        Object data = parseBody(rec.outcomeJson());
        return new GatewayResponse(requestId, tenantId, ENTRY_PAYMENT, "REPLAY_OK",
                true, null, "CAPTURED", data, "replayed");
    }

    private Object parseBody(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return objectMapper.convertValue(node, Object.class);
        } catch (Exception e) {
            // 本地模拟上游的成功响应本身应可解析；解析不到时归一化为畸形响应
            throw new GatewayException(GatewayErrorCode.UPSTREAM_BAD_RESPONSE,
                    "上游成功响应无法解析",
                    upstreamRetryHint(GatewayErrorCode.UPSTREAM_BAD_RESPONSE));
        }
    }

    private static String windowInfo(QuotaDecision qd) {
        return "window=[" + qd.windowStartEpoch() + "," + qd.windowEndEpoch() + ") consumed="
                + qd.consumedInWindow() + "/" + qd.quotaLimit() + " inflight="
                + qd.inflight() + "/" + qd.concurrencyLimit();
    }

    private void audit(String tenantId, String entry, String key, String fingerprint,
                       long windowStart, long windowEnd, String decision, String reasonCode,
                       String upstreamFailureType, String upstreamTxnId, String input,
                       String requestId, long startMillis) {
        auditService.record(new AuditRecord(
                requestId,
                tenantId == null ? "-" : tenantId,
                entry,
                key,
                fingerprint,
                windowStart,
                windowEnd,
                decision,
                reasonCode,
                upstreamFailureType,
                upstreamTxnId,
                System.currentTimeMillis() - startMillis,
                input,
                System.currentTimeMillis() / 1000L));
    }

    private static String safeResolve(String tenantHeader) {
        return tenantHeader == null ? "-" : tenantHeader.trim();
    }
}
