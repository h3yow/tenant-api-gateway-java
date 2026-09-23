package com.github.h3yow.tenantapigatewayjava.upstream;

/** 模拟上游可能出现的故障类别。 */
public enum UpstreamFailureType {
    TIMEOUT,
    CONNECTION_FAILED,
    MALFORMED_RESPONSE,
    BUSINESS_ERROR
}
