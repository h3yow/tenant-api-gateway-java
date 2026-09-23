package com.github.h3yow.tenantapigatewayjava.error;

import com.github.h3yow.tenantapigatewayjava.core.clock.GatewayClock;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 全局异常归一化处理：对外只暴露稳定错误码，不暴露上游原始异常堆栈。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final GatewayClock clock;

    public GlobalExceptionHandler(GatewayClock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGateway(GatewayException ex, HttpServletRequest request) {
        GatewayErrorCode ec = ex.getErrorCode();
        String requestId = firstNonBlank(ex.getRequestId(), responseHeader(request, "X-Request-Id"));
        ErrorResponse body = new ErrorResponse(
                ec.getCode(),
                ex.getMessage(),
                ec.isRetryable(),
                ex.getRetryHint(),
                requestId,
                responseHeader(request, "X-Tenant-Id"),
                clock.now());
        // 不打印上游堆栈；错误码本身即判定依据
        log.warn("gateway rejection code={} retryable={} path={} requestId={}",
                ec.getCode(), ec.isRetryable(), request.getRequestURI(), body.requestId());
        return ResponseEntity.status(ec.getHttpStatus()).body(body);
    }

    /** 兜底：任何未归一化异常都转为稳定的 INTERNAL_ERROR，不泄漏内部细节。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unexpected internal error path={}", request.getRequestURI(), ex);
        GatewayErrorCode ec = GatewayErrorCode.INTERNAL_ERROR;
        ErrorResponse body = new ErrorResponse(
                ec.getCode(),
                ec.getMessage(),
                ec.isRetryable(),
                "请使用新的请求稍后重试；如持续失败请联系网关管理员",
                responseHeader(request, "X-Request-Id"),
                responseHeader(request, "X-Tenant-Id"),
                clock.now());
        return ResponseEntity.status(ec.getHttpStatus()).body(body);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private static String responseHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? null : value.trim();
    }
}
