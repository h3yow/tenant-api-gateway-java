package com.github.h3yow.tenantapigatewayjava.error;

import java.time.Instant;

/** 对外稳定错误响应体，不包含上游原始异常信息。 */
public record ErrorResponse(String code, String message, boolean retryable, String retryHint,
                            String requestId, String tenantId, Instant timestamp) {
}
