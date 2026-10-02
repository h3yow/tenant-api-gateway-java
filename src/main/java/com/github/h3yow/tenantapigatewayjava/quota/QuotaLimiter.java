package com.github.h3yow.tenantapigatewayjava.quota;

import com.github.h3yow.tenantapigatewayjava.core.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按租户 + 固定时间窗口的配额控制。
 * <p>
 * 只有真正会调用上游的请求才在通过准入后占用名额；请求在占用名额之后、发起上游调用之前
 * 被其他环节挡下时，必须调用 {@link #release} 归还名额。熔断快速失败与幂等折叠重放的
 * 请求不消耗窗口配额。
 */
@Component
public class QuotaLimiter {

    private static final Logger log = LoggerFactory.getLogger(QuotaLimiter.class);

    private final TimeSource timeSource;
    private final ConcurrentHashMap<String, WindowState> states = new ConcurrentHashMap<>();

    public QuotaLimiter(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    private static final class WindowState {
        private long windowStart;
        private int used;
    }

    private String key(String tenantId, int windowSeconds) {
        return tenantId + "#" + windowSeconds;
    }

    /**
     * 尝试占用一个窗口名额。
     *
     * @return true 表示获得名额；false 表示本窗口配额已用尽
     */
    public boolean tryAcquire(String tenantId, int windowSeconds, int windowLimit) {
        long now = timeSource.nowMillis();
        long windowStart = (now / 1000L / windowSeconds) * windowSeconds * 1000L;
        WindowState state = states.computeIfAbsent(key(tenantId, windowSeconds), k -> new WindowState());
        boolean acquired;
        synchronized (state) {
            if (state.windowStart != windowStart) {
                state.windowStart = windowStart;
                state.used = 0;
            }
            acquired = state.used < windowLimit;
            if (acquired) {
                state.used++;
            }
            log.info("quota tenant={} windowStart={} windowSeconds={} used={}/{} acquired={}",
                    tenantId, state.windowStart, windowSeconds, state.used, windowLimit, acquired);
        }
        return acquired;
    }

    /**
     * 回退此前占用的名额（请求在真正发起上游调用前被挡下时使用）。
     * 仅当名额属于当前窗口时归还，避免污染新窗口计数。
     */
    public void release(String tenantId, int windowSeconds) {
        long now = timeSource.nowMillis();
        long windowStart = (now / 1000L / windowSeconds) * windowSeconds * 1000L;
        WindowState state = states.get(key(tenantId, windowSeconds));
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.windowStart == windowStart && state.used > 0) {
                state.used--;
                log.info("quota-release tenant={} windowStart={} windowSeconds={} used={}",
                        tenantId, state.windowStart, windowSeconds, state.used);
            }
        }
    }
}
