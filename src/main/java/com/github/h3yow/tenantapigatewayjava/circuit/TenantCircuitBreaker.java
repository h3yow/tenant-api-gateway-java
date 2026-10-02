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
                boolean cooledDown = now >= openedAtMillis + config.openDurationMillis();
                if (cooledDown && probePermitsLeft > 0) {
                    probePermitsLeft--;
                    state = CircuitState.HALF_OPEN;
                    log.info("circuit tenant={} state={} -> HALF_OPEN probeAllowed remainingProbes={}",
                            tenantId, CircuitState.OPEN, probePermitsLeft);
                    return Admission.passProbe();
                }
                long wait = cooledDown
                        ? config.probeWaitMillis()
                        : Math.max(0L, openedAtMillis + config.openDurationMillis() - now);
                log.info("circuit-reject tenant={} state=OPEN cooledDown={} retryAfterMillis={}",
                        tenantId, cooledDown, wait);
                return Admission.reject(openedByProbeFailure, wait);
            }
            case HALF_OPEN -> {
                if (probePermitsLeft > 0) {
                    probePermitsLeft--;
                    log.info("circuit tenant={} state=HALF_OPEN probeAllowed remainingProbes={}",
                            tenantId, probePermitsLeft);
                    return Admission.passProbe();
                }
                // 探测名额已放完、探测仍在进行（或已失败重置中）：普通请求继续快速失败，
                // 避免“半开”状态下大量请求穿透。
                long wait = now >= openedAtMillis + config.openDurationMillis()
                        ? config.probeWaitMillis()
                        : Math.max(0L, openedAtMillis + config.openDurationMillis() - now);
                log.info("circuit-reject tenant={} state=HALF_OPEN retryAfterMillis={}", tenantId, wait);
                return Admission.reject(openedByProbeFailure, wait);
            }
            default -> {
                return Admission.pass();
            }
        }
    }

    /**
     * 一次真正的上游调用成功。只有熔断器仍处于 HALF_OPEN（即本轮探测尚未出现失败）时，
     * 探测成功才让该租户整体恢复到 CLOSED；若已有探测失败导致重新 OPEN，迟到的探测
     * 成功不得把它恢复，避免状态来回抖动。
     */
    public synchronized void recordSuccess(boolean probe) {
        if (probe && state == CircuitState.HALF_OPEN) {
            log.info("circuit tenant={} state=HALF_OPEN -> CLOSED reason=probe-success consecutiveFailures=0",
                    tenantId);
            state = CircuitState.CLOSED;
            consecutiveFailures = 0;
            probePermitsLeft = config.probePermits();
            openedByProbeFailure = false;
        } else if (!probe && state == CircuitState.CLOSED) {
            consecutiveFailures = 0;
        }
    }

    /**
     * 一次真正的上游调用失败。CLOSED 下累计连续失败，达阈值则打开；HALF_OPEN 下的
     * 探测失败立即重新打开并重新计时。
     */
    public synchronized void recordFailure(boolean probe) {
        consecutiveFailures++;
        if (state == CircuitState.HALF_OPEN || probe) {
            tripOpen(true);
            log.warn("circuit tenant={} state=OPEN reason=probe-failure openedAtMillis={}",
                    tenantId, openedAtMillis);
            return;
        }
        if (consecutiveFailures >= config.failureThreshold()) {
            tripOpen(false);
            log.warn("circuit tenant={} state=OPEN reason=threshold consecutiveFailures={} openedAtMillis={}",
                    tenantId, consecutiveFailures, openedAtMillis);
        } else {
            log.info("circuit tenant={} state=CLOSED consecutiveFailures={}/{}",
                    tenantId, consecutiveFailures, config.failureThreshold());
        }
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
