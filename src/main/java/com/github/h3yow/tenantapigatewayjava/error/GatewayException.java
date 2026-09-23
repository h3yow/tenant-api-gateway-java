package com.github.h3yow.tenantapigatewayjava.error;

/** 网关业务异常，携带稳定的归一化错误类型与本次请求的 requestId。 */
public class GatewayException extends RuntimeException {

    private final GatewayErrorCode errorCode;
    private final String retryHint;
    private final String requestId;

    public GatewayException(GatewayErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null, null);
    }

    public GatewayException(GatewayErrorCode errorCode, String message, String retryHint) {
        this(errorCode, message, retryHint, null);
    }

    public GatewayException(GatewayErrorCode errorCode, String message, String retryHint,
                            String requestId) {
        super(message);
        this.errorCode = errorCode;
        this.retryHint = retryHint;
        this.requestId = requestId;
    }

    public GatewayErrorCode getErrorCode() { return errorCode; }
    public String getRetryHint() { return retryHint; }
    public String getRequestId() { return requestId; }

    /** 复制异常并附带网关生成的 requestId（用于把内部生成的请求号透传到错误响应）。 */
    public GatewayException withRequestId(String id) {
        return new GatewayException(errorCode, getMessage(), retryHint, id);
    }
}
