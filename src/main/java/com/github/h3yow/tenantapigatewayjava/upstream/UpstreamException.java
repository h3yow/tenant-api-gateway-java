package com.github.h3yow.tenantapigatewayjava.upstream;

/** 上游调用异常（归一化前的内部表示，绝不直接透出给客户端）。 */
public class UpstreamException extends RuntimeException {

    private final UpstreamFailureType failureType;
    private final String upstreamCode;
    private final boolean upstreamRetryable;

    public UpstreamException(UpstreamFailureType failureType, String upstreamCode,
                             String message, boolean upstreamRetryable) {
        super(message);
        this.failureType = failureType;
        this.upstreamCode = upstreamCode;
        this.upstreamRetryable = upstreamRetryable;
    }

    public UpstreamFailureType getFailureType() { return failureType; }
    public String getUpstreamCode() { return upstreamCode; }
    public boolean isUpstreamRetryable() { return upstreamRetryable; }
}
