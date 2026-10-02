package com.github.h3yow.tenantapigatewayjava.circuit;

/**
 * 熔断参数。
 *
 * @param failureThreshold   连续失败达到该次数后打开熔断
 * @param openDurationMillis 打开后的快速失败时长（冷静期）
 * @param probePermits       冷静期后允许同时放行的探测请求数
 * @param probeWaitMillis    探测仍在进行时，后续请求快速失败的等待提示
 */
public record CircuitBreakerConfig(int failureThreshold, long openDurationMillis,
                                   int probePermits, long probeWaitMillis) {

    public static CircuitBreakerConfig defaults() {
        return new CircuitBreakerConfig(5, 30_000L, 1, 5_000L);
    }
}
