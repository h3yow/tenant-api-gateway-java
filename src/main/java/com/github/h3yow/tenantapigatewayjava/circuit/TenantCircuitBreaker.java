package com.github.h3yow.tenantapigatewayjava.circuit;

import com.github.h3yow.tenantapigatewayjava.core.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个租户的熔断器：连续失败计数 + 冷静期 + 限量探测。
 * <p>
 * 状态流转（每个租户独立，互不影响）：
 * <pre>
 *   CLOSED --连续失败达阈值--> OPEN
 *   OPEN   --冷静期到期且仍有探测名额--> HALF_OPEN（仅放少量探测请求）
 *   HALF_OPEN --探测成功--> CLOSED（整体恢复）
 *   HALF_OPEN --探测失败--> OPEN（重新计时，防止抖动）
 * </pre>
 * HALF_OPEN 期间非探测请求一律快速失败；冷静期刚到的一瞬间，探测名额之外的并发请求
 * 也被挡下，避免大量请求同时穿透。
 * <p>
 * 防抖动约定：探测失败立即重新 OPEN 后，仍在途的其他探测回报的任何结果（成功或失败）
 * 都视为过期结果而忽略——只有下一轮冷静期后放行的新探测才能再次改变状态，避免状态
 * 在 OPEN/CLOSED 之间来回横跳误伤正常请求。
 */
public class TenantCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(TenantCircuitBreaker.class);

    private final String tenantId;
    private final CircuitBreakerConfig config;
    private final TimeSource timeSource;

    private CircuitState state = CircuitState.CLOSED;
    private int consecutiveFailures;
    private long openedAtMillis = Long.MIN_VALUE;
    private int probePermitsLeft;
    /** 当前 OPEN 窗口是否由探测失败触发（用于审计区分 REJECT_CIRCUIT_OPEN / REJECT_PROBE_FAILED）。 */
    private boolean openedByProbeFailure;

    public TenantCircuitBreaker(String tenantId, CircuitBreakerConfig config, TimeSource timeSource) {
        this.tenantId = tenantId;
        this.config = config;
        this.timeSource = timeSource;
        this.probePermitsLeft = config.probePermits();
    }

    /**
     * 请求进入时的准入判定。幂等折叠重放的请求不经过这里，由网关编排层先行处理。
     */
    public synchronized Admission admit() {
        long now = timeSource.nowMillis();
        switch (state) {
            case CLOSED -> {
                return Admission.pass();
            }
            case OPEN -> {
                long remaining = Math.max(0L, openedAtMillis + config.openDurationMillis() - now);
                if (remaining == 0L && probePermitsLeft > 0) {
                    probePermitsLeft--;
                    state = CircuitState.HALF_OPEN;
                    log.info("circuit tenant={} state=OPEN -> HALF_OPEN reason=cooldown-elapsed remainingProbes={}",
                            tenantId, probePermitsLeft);
                    return Admission.passProbe();
                }
                long wait = remaining == 0L ? config.probeWaitMillis() : remaining;
                log.info("circuit-reject tenant={} state=OPEN byProbeFailure={} retryAfterMillis={}",
                        tenantId, openedByProbeFailure, wait);
                return Admission.reject(openedByProbeFailure, wait);
            }
            case HALF_OPEN -> {
                if (probePermitsLeft > 0) {
                    probePermitsLeft--;
                    log.info("circuit tenant={} state=HALF_OPEN probeAllowed remainingProbes={}",
                            tenantId, probePermitsLeft);
                    return Admission.passProbe();
                }
                // 探测名额已放完、探测仍在进行：普通请求继续快速失败，避免半开瞬间大量穿透。
                log.info("circuit-reject tenant={} state=HALF_OPEN probeInProgress=true retryAfterMillis={}",
                        tenantId, config.probeWaitMillis());
                return Admission.reject(false, config.probeWaitMillis());
            }
            default -> {
                return Admission.pass();
            }
        }
    }

    /**
     * 一次真正的上游调用成功。HALF_OPEN 下的探测成功让该租户整体恢复到 CLOSED；
     * 已经因别的探测失败重新 OPEN 后才回报的成功属于过期结果，忽略。
     */
    public synchronized void recordSuccess(boolean probe) {
        if (state == CircuitState.HALF_OPEN) {
            log.info("circuit tenant={} state=HALF_OPEN -> CLOSED reason=probe-success consecutiveFailures=0",
                    tenantId);
            resetToClosed();
            return;
        }
        if (state == CircuitState.CLOSED) {
            consecutiveFailures = 0;
        }
        // state == OPEN：过期探测结果，忽略，不能打断重新计时的冷静期。
    }

    /**
     * 一次真正的上游调用失败。CLOSED 下累计连续失败，达阈值则打开；HALF_OPEN 下的
     * 探测失败立即重新打开并重新计时；OPEN 状态下回报的失败属于过期结果，忽略。
     */
    public synchronized void recordFailure(boolean probe) {
        if (state == CircuitState.HALF_OPEN) {
            tripOpen(true);
            log.warn("circuit tenant={} state=OPEN reason=probe-failure openedAtMillis={}",
                    tenantId, openedAtMillis);
            return;
        }
        if (state == CircuitState.OPEN) {
            // 过期探测结果，忽略。
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= config.failureThreshold()) {
            tripOpen(false);
            log.warn("circuit tenant={} state=CLOSED -> OPEN reason=threshold consecutiveFailures={} openedAtMillis={}",
                    tenantId, consecutiveFailures, openedAtMillis);
        } else {
            log.info("circuit tenant={} state=CLOSED consecutiveFailures={}/{}",
                    tenantId, consecutiveFailures, config.failureThreshold());
        }
    }

    private void resetToClosed() {
        state = CircuitState.CLOSED;
        consecutiveFailures = 0;
        probePermitsLeft = config.probePermits();
        openedByProbeFailure = false;
    }

    private void tripOpen(boolean byProbeFailure) {
        state = CircuitState.OPEN;
        openedAtMillis = timeSource.nowMillis();
        probePermitsLeft = config.probePermits();
        openedByProbeFailure = byProbeFailure;
    }

    public synchronized CircuitState currentState() {
        return state;
    }
}
