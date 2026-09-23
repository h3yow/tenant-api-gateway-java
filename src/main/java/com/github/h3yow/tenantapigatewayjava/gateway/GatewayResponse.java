package com.github.h3yow.tenantapigatewayjava.gateway;

/** 网关成功响应。 */
public record GatewayResponse(String requestId, String tenantId, String entry, String decision,
                              boolean replayed, String upstreamTxnId, String status,
                              Object data, String windowInfo) {
}
