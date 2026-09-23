package com.github.h3yow.tenantapigatewayjava.error;

/** 网关归一化错误类型（稳定错误码 + 稳定 HTTP 状态码 + 重试提示）。 */
public enum GatewayErrorCode {

    // 请求侧错误
    MISSING_TENANT(400, "MISSING_TENANT", "缺少租户标识请求头 X-Tenant-Id", false),
    MISSING_IDEMPOTENCY_KEY(400, "MISSING_IDEMPOTENCY_KEY", "写操作缺少 Idempotency-Key 请求头", false),
    INVALID_PARAMETER(400, "INVALID_PARAMETER", "请求参数校验未通过", false),
    UNKNOWN_TENANT(403, "UNKNOWN_TENANT", "未知或未开通的租户", false),

    // 闸口拒绝（拒绝原因可区分）
    QUOTA_EXCEEDED(429, "QUOTA_EXCEEDED", "当前时间窗口调用配额已用尽", true),
    CONCURRENCY_EXCEEDED(429, "CONCURRENCY_EXCEEDED", "该租户在途并发请求数已达上限", true),

    // 幂等
    IDEMPOTENCY_IN_PROGRESS(409, "IDEMPOTENCY_IN_PROGRESS", "同一幂等键的请求正在处理中", true),
    IDEMPOTENCY_KEY_CONFLICT(409, "IDEMPOTENCY_KEY_CONFLICT",
            "幂等键已被用于不同的请求内容，禁止复用", false),

    // 上游归一化错误（不透出来源细节）
    UPSTREAM_TIMEOUT(504, "UPSTREAM_TIMEOUT", "上游服务处理超时", true),
    UPSTREAM_UNAVAILABLE(502, "UPSTREAM_UNAVAILABLE", "上游服务连接失败", true),
    UPSTREAM_BAD_RESPONSE(502, "UPSTREAM_BAD_RESPONSE", "上游服务返回了无法解析的响应", false),
    UPSTREAM_BUSINESS_REJECTED(422, "UPSTREAM_BUSINESS_REJECTED", "上游业务处理被拒绝", false),
    UPSTREAM_REJECTED_BY_IDEMPOTENT_RETRY(422, "UPSTREAM_REJECTED_BY_IDEMPOTENT_RETRY",
            "该幂等键此前执行已被上游拒绝（结果重放）", false),

    // 兜底
    INTERNAL_ERROR(500, "INTERNAL_ERROR", "网关内部错误", true);

    private final int httpStatus;
    private final String code;
    private final String message;
    private final boolean retryable;

    GatewayErrorCode(int httpStatus, String code, String message, boolean retryable) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
        this.retryable = retryable;
    }

    public int getHttpStatus() { return httpStatus; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public boolean isRetryable() { return retryable; }
}
