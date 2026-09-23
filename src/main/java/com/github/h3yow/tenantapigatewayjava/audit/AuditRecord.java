package com.github.h3yow.tenantapigatewayjava.audit;

/** 单请求审计记录：可还原租户、请求与最终判定。 */
public record AuditRecord(
        String requestId,
        String tenantId,
        String entry,
        String idempotencyKey,
        String requestFingerprint,
        long windowStartEpoch,
        long windowEndEpoch,
        String decision,
        String reasonCode,
        String upstreamFailureType,
        String upstreamTxnId,
        long durationMillis,
        String requestSummary,
        long timestampEpoch) {
}
