package com.github.h3yow.tenantapigatewayjava.model;

/**
 * 进入网关的一次请求（既有入口约定保持不变）。
 *
 * @param tenantId      租户标识
 * @param idempotencyKey 幂等标识，可为空（空表示不参与幂等折叠）
 * @param payload       请求内容，用于幂等一致性比对
 * @param data          需要转发给上游的业务数据
 */
public record GatewayRequest(String tenantId, String idempotencyKey, String payload, String data) {
}
