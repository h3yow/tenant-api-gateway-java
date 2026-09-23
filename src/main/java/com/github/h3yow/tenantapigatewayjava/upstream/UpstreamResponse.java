package com.github.h3yow.tenantapigatewayjava.upstream;

/** 上游成功响应。 */
public record UpstreamResponse(String upstreamTxnId, String status, String bodyJson) {
}
