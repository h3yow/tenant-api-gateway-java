package com.github.h3yow.tenantapigatewayjava.quota;

import com.github.h3yow.tenantapigatewayjava.config.TenantProperties;
import com.github.h3yow.tenantapigatewayjava.core.clock.GatewayClock;
import com.github.h3yow.tenantapigatewayjava.error.GatewayErrorCode;
import com.github.h3yow.tenantapigatewayjava.error.GatewayException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 按租户 + 固定时间窗口的调用配额与在途并发控制（本地内存实现）。
 *
 * <p>并发正确性：每个租户一个独立状态对象，"窗口滚动判定 + 配额检查 + 计数 + 在途占位"
 * 在该对象的内置锁上一次完成，保证同一窗口内的并发请求不会出现超额放行或计数丢失；
 * 不同租户使用不同锁，互不阻塞。
 */
@Service
public class QuotaService {

    private final GatewayClock clock;
    private final TenantProperties tenantProperties;
    private final ConcurrentHashMap<String, TenantWindowState> states = new ConcurrentHashMap<>();

    public QuotaService(GatewayClock clock, TenantProperties tenantProperties) {
        this.clock = clock;
        this.tenantProperties = tenantProperties;
    }

    private TenantWindowState stateFor(String tenantId) {
        return states.computeIfAbsent(tenantId, k -> new TenantWindowState());
    }

    /**
     * 进入闸口：窗口配额计数 +1 且在途并发 +1。
     * 被拒绝时不会留下任何计数（配额拒绝不动计数，并发拒绝不动在途数）。
     */
    public QuotaDecision acquire(String tenantId) {
        TenantProperties.TenantConfig cfg = tenantProperties.getTenant(tenantId);
        if (cfg == null) {
            throw new GatewayException(GatewayErrorCode.UNKNOWN_TENANT);
        }
        long now = clock.now().getEpochSecond();
        TenantWindowState state = stateFor(tenantId);
        synchronized (state) {
            // 窗口滚动（在锁内完成，避免并发重复滚动 / 计数丢失）
            if (state.windowStart == 0L || now >= state.windowEnd) {
                state.windowStart = now;
                state.windowEnd = now + cfg.getWindowSeconds();
                state.consumedInWindow = 0;
            }
            // 1) 窗口配额检查
            if (state.consumedInWindow >= cfg.getQuotaLimit()) {
                return new QuotaDecision(QuotaResult.QUOTA_EXCEEDED,
                        state.windowStart, state.windowEnd,
                        state.consumedInWindow, cfg.getQuotaLimit(),
                        state.inflight, cfg.getConcurrencyLimit());
            }
            // 2) 在途并发检查
            if (state.inflight >= cfg.getConcurrencyLimit()) {
                return new QuotaDecision(QuotaResult.CONCURRENCY_EXCEEDED,
                        state.windowStart, state.windowEnd,
                        state.consumedInWindow, cfg.getQuotaLimit(),
                        state.inflight, cfg.getConcurrencyLimit());
            }
            // 3) 原子占位：只有走到这里才计数，拒绝路径不产生计数
            state.consumedInWindow++;
            state.inflight++;
            return new QuotaDecision(QuotaResult.ALLOWED,
                    state.windowStart, state.windowEnd,
                    state.consumedInWindow, cfg.getQuotaLimit(),
                    state.inflight, cfg.getConcurrencyLimit());
        }
    }

    /** 请求结束（成功或失败）后释放在途并发占位。 */
    public void release(String tenantId) {
        TenantWindowState state = states.get(tenantId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.inflight > 0) {
                state.inflight--;
            }
        }
    }

    /** 只读快照（测试与审计用）。 */
    public QuotaDecision snapshot(String tenantId) {
        TenantProperties.TenantConfig cfg = tenantProperties.getTenant(tenantId);
        TenantWindowState state = stateFor(tenantId);
        synchronized (state) {
            return new QuotaDecision(QuotaResult.ALLOWED,
                    state.windowStart, state.windowEnd, state.consumedInWindow,
                    cfg == null ? -1 : cfg.getQuotaLimit(),
                    state.inflight, cfg == null ? -1 : cfg.getConcurrencyLimit());
        }
    }

    /** 清空所有租户状态（测试隔离用）。 */
    public void reset() {
        states.clear();
    }

    /** 单个租户的窗口状态。 */
    private static final class TenantWindowState {
        private long windowStart;
        private long windowEnd;
        private int consumedInWindow;
        private int inflight;
    }

    /** 配额闸口判定详情（用于审计与日志）。 */
    public record QuotaDecision(QuotaResult result, long windowStartEpoch, long windowEndEpoch,
                                int consumedInWindow, int quotaLimit, int inflight,
                                int concurrencyLimit) {
    }
}
