package com.github.h3yow.tenantapigatewayjava.circuit;

import com.github.h3yow.tenantapigatewayjava.core.TimeSource;
import com.github.h3yow.tenantapigatewayjava.gateway.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按租户持有各自独立的熔断器实例，故障彼此隔离：一个租户上游不稳定不会拖垮其他租户。
 */
@Component
public class CircuitBreakerRegistry {

    private final TimeSource timeSource;
    private final GatewayProperties properties;
    private final ConcurrentHashMap<String, TenantCircuitBreaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakerRegistry(TimeSource timeSource, GatewayProperties properties) {
        this.timeSource = timeSource;
        this.properties = properties;
    }

    /**
     * 获取（不存在则创建）指定租户的熔断器。每个租户独立计数、独立冷静期。
     */
    public TenantCircuitBreaker forTenant(String tenantId) {
        return breakers.computeIfAbsent(tenantId, id -> new TenantCircuitBreaker(
                id,
                new CircuitBreakerConfig(properties.getFailureThreshold(),
                        properties.getOpenDurationMillis(),
                        properties.getProbePermits(),
                        properties.getProbeWaitMillis()),
                timeSource));
    }
}
