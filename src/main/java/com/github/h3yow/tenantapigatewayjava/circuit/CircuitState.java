package com.github.h3yow.tenantapigatewayjava.circuit;

/**
 * 单租户熔断器状态：
 * CLOSED 正常放行；OPEN 快速失败；HALF_OPEN 允许探测请求试探。
 */
public enum CircuitState {
    CLOSED, OPEN, HALF_OPEN
}
