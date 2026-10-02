package com.github.h3yow.tenantapigatewayjava.model;

/**
 * 网关对一次请求的最终判定类别。审计日志与响应体中的 reason 使用同一套编码。
 */
public enum DecisionCode {
    /** 正常放行（非探测）。 */
    PASS,
    /** 作为冷静期后的探测请求放行。 */
    PASS_PROBE,
    /** 熔断器打开，请求被快速失败挡下。 */
    REJECT_CIRCUIT_OPEN,
    /** 探测请求调用上游失败，继续保持熔断。 */
    REJECT_PROBE_FAILED,
    /** 窗口配额已用尽。 */
    REJECT_QUOTA_EXHAUSTED,
    /** 该租户在途并发已达上限。 */
    REJECT_CONCURRENCY_LIMITED,
    /** 幂等命中且终态可重放，直接折叠返回。 */
    IDEMPOTENT_REPLAY,
    /** 幂等键相同但请求体不一致。 */
    REJECT_IDEMPOTENT_CONFLICT,
    /** 同一幂等键已有请求在途，本次请求不重复调用上游。 */
    REJECT_IDEMPOTENT_IN_PROGRESS,
    /** 上游调用失败，异常已归一化，不暴露原始错误。 */
    UPSTREAM_ERROR
}
