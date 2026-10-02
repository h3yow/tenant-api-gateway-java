package com.github.h3yow.tenantapigatewayjava.concurrency;

import com.github.h3yow.tenantapigatewayjava.core.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按租户的在途并发控制。
 * <p>
 * 只有真正向上游发起的调用占名额，调用无论成功失败都必须释放。熔断快速失败与幂等
 * 折叠重放的请求不占在途名额。
 */
@Component
public class ConcurrencyLimiter {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyLimiter.class);

    private final TimeSource timeSource;
    private final ConcurrentHashMap<String, AtomicInteger> inflight = new ConcurrentHashMap<>();

    public ConcurrencyLimiter(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    /**
     * 尝试占用一个在途名额。
     *
     * @return true 表示获得名额；false 表示该租户在途并发已达上限
     */
    public boolean tryAcquire(String tenantId, int maxConcurrent) {
        AtomicInteger counter = inflight.computeIfAbsent(tenantId, k -> new AtomicInteger());
        int current;
        int next;
        do {
            current = counter.get();
            if (current >= maxConcurrent) {
                log.info("concurrency tenant={} inflight={}/{} acquired=false",
                        tenantId, current, maxConcurrent);
                return false;
            }
            next = current + 1;
        } while (!counter.compareAndSet(current, next));
        log.info("concurrency tenant={} inflight={}/{} acquired=true", tenantId, next, maxConcurrent);
        return true;
    }

    /**
     * 释放一个在途名额。
     */
    public void release(String tenantId) {
        AtomicInteger counter = inflight.get(tenantId);
        if (counter == null) {
            return;
        }
        int remaining = counter.updateAndGet(v -> Math.max(0, v - 1));
        log.info("concurrency-release tenant={} inflight={}", tenantId, remaining);
    }
}
