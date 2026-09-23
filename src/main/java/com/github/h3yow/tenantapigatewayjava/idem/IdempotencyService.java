package com.github.h3yow.tenantapigatewayjava.idem;

import com.github.h3yow.tenantapigatewayjava.core.clock.GatewayClock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 写操作幂等存储：同一 (租户, Idempotency-Key) 的请求折叠为一次实际执行。
 * 本地内存实现（重启语义见 README）。
 *
 * <p>状态机：首次请求在键上原子占位为 IN_PROGRESS；执行结束（成功或被上游拒绝）
 * 后落为终态。并发重复请求看到 IN_PROGRESS 得到 409 + 稍后重试；
 * 终态后重复请求得到原始终态结果的重放；键被不同请求内容复用则为键冲突。
 */
@Service
public class IdempotencyService {

    private final GatewayClock clock;
    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    public IdempotencyService(GatewayClock clock) {
        this.clock = clock;
    }

    private static String k(String tenantId, String key) {
        return tenantId + '|' + key;
    }

    /**
     * 尝试占用幂等键（每键原子）。
     *
     * <ul>
     *   <li>ABSENT：首次，调用方应执行实际业务（已原子占位 IN_PROGRESS）；</li>
     *   <li>IN_PROGRESS：同一键正在被另一请求处理，调用方应稍后重试；</li>
     *   <li>REPLAY：键已有终态结果且请求指纹一致，调用方直接重放；</li>
     *   <li>CONFLICT：键已被不同请求内容（指纹不同）占用，禁止复用。</li>
     * </ul>
     */
    public IdempotencyOutcome acquire(String tenantId, String key, String requestFingerprint) {
        final long now = clock.now().getEpochSecond();
        final java.util.concurrent.atomic.AtomicReference<IdempotencyOutcome> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        store.compute(k(tenantId, key), (mapKey, existing) -> {
            if (existing == null) {
                Entry fresh = Entry.inProgress(requestFingerprint, now);
                result.set(new IdempotencyOutcome(IdempotencyOutcome.State.ABSENT, Optional.empty()));
                return fresh;
            }
            if (!existing.fingerprint.equals(requestFingerprint)) {
                result.set(new IdempotencyOutcome(IdempotencyOutcome.State.CONFLICT,
                        Optional.ofNullable(existing.record)));
                return existing;
            }
            if (!existing.done) {
                result.set(new IdempotencyOutcome(IdempotencyOutcome.State.IN_PROGRESS, Optional.empty()));
                return existing;
            }
            result.set(new IdempotencyOutcome(IdempotencyOutcome.State.REPLAY,
                    Optional.of(existing.record)));
            return existing;
        });
        return result.get();
    }

    /** 业务执行结束（成功或上游拒绝）后落定终态结果，供后续重复请求重放。 */
    public void complete(String tenantId, String key, IdempotencyRecord record) {
        store.computeIfPresent(k(tenantId, key), (mapKey, existing) -> {
            existing.record = record;
            existing.done = true;
            return existing;
        });
    }

    /** 测试隔离用。 */
    public void reset() {
        store.clear();
    }

    /** 幂等槽位内部状态。 */
    private static final class Entry {
        private final String fingerprint;
        private final long createdAtEpoch;
        private boolean done;
        private IdempotencyRecord record;

        private Entry(String fingerprint, long createdAtEpoch) {
            this.fingerprint = fingerprint;
            this.createdAtEpoch = createdAtEpoch;
            // 占位即处于 IN_PROGRESS；complete() 调用后才进入终态
            this.done = false;
        }

        static Entry inProgress(String fingerprint, long now) {
            return new Entry(fingerprint, now);
        }
    }

    /** 幂等判定详情。 */
    public record IdempotencyOutcome(State state, Optional<IdempotencyRecord> existing) {
        public enum State { ABSENT, REPLAY, IN_PROGRESS, CONFLICT }
    }

    /** 幂等记录终态。upstreamErrorCode 非空表示首次执行被上游拒绝（错误重放）。 */
    public record IdempotencyRecord(String requestFingerprint, String outcomeJson,
                                    String upstreamErrorCode, int httpStatusHint,
                                    long createdAtEpoch, long completedAtEpoch, String requestId) {

        public boolean isError() {
            return upstreamErrorCode != null && !upstreamErrorCode.isBlank();
        }

        public static IdempotencyRecord success(String requestFingerprint, String outcomeJson,
                                                int httpStatusHint, long createdAtEpoch,
                                                long completedAtEpoch, String requestId) {
            return new IdempotencyRecord(requestFingerprint, outcomeJson, null, httpStatusHint,
                    createdAtEpoch, completedAtEpoch, requestId);
        }

        public static IdempotencyRecord error(String requestFingerprint, String outcomeJson,
                                              String upstreamErrorCode, int httpStatusHint,
                                              long createdAtEpoch, long completedAtEpoch,
                                              String requestId) {
            return new IdempotencyRecord(requestFingerprint, outcomeJson, upstreamErrorCode,
                    httpStatusHint, createdAtEpoch, completedAtEpoch, requestId);
        }
    }
}
