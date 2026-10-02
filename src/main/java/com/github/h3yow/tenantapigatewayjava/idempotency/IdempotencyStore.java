package com.github.h3yow.tenantapigatewayjava.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等记录存储：记录每个租户下幂等键对应的终态结果与请求内容指纹。
 * <p>
 * 终态一旦写定，之后携带相同幂等标识和相同内容的请求（无论该租户熔断器是否打开）
 * 都直接折叠重放；幂等键相同但内容不同的请求被拒绝。上游失败不写终态，仅留下
 * “进行中”占位，请求结束后清除，允许同键重试。
 */
@Component
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    /** 幂等键尚无任何记录。 */
    public enum LookupStatus { ABSENT, IN_PROGRESS, SETTLED }

    /**
     * 幂等查询结果。
     *
     * @param status      记录状态
     * @param samePayload 已有记录与本次内容是否一致
     * @param result      已落定的结果（仅 SETTLED 时有值）
     */
    public record Lookup(LookupStatus status, boolean samePayload, String result) {
    }

    private record Entry(String payload, String result, boolean settled) {
    }

    /** key 为 租户 + 幂等键，租户之间互不影响。 */
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    private String key(String tenantId, String idempotencyKey) {
        return tenantId + "#" + idempotencyKey;
    }

    /**
     * 查询幂等键当前状态，不做任何写入。
     */
    public Lookup lookup(String tenantId, String idempotencyKey, String payload) {
        Entry entry = entries.get(key(tenantId, idempotencyKey));
        if (entry == null) {
            return new Lookup(LookupStatus.ABSENT, true, null);
        }
        boolean same = entry.payload().equals(payload);
        if (entry.settled()) {
            log.info("idempotent-hit tenant={} key={} samePayload={} replay=true",
                    tenantId, idempotencyKey, same);
            return new Lookup(LookupStatus.SETTLED, same, entry.result());
        }
        log.info("idempotent-in-progress tenant={} key={} samePayload={}", tenantId, idempotencyKey, same);
        return new Lookup(LookupStatus.IN_PROGRESS, same, null);
    }

    /**
     * 为幂等键放置“进行中”占位；若已被其他请求占位则返回 false。
     */
    public boolean markInProgress(String tenantId, String idempotencyKey, String payload) {
        Entry fresh = new Entry(payload, null, false);
        Entry existing = entries.putIfAbsent(key(tenantId, idempotencyKey), fresh);
        return existing == null;
    }

    /**
     * 上游成功后写入终态结果。
     */
    public void settle(String tenantId, String idempotencyKey, String payload, String result) {
        entries.put(key(tenantId, idempotencyKey), new Entry(payload, result, true));
        log.info("idempotent-settle tenant={} key={}", tenantId, idempotencyKey);
    }

    /**
     * 清除“进行中”占位（上游失败或请求被挡下时使用），允许同键重试。
     * 仅清除仍是“进行中”的占位，绝不清掉已落定终态。
     */
    public void clearInProgress(String tenantId, String idempotencyKey) {
        String k = key(tenantId, idempotencyKey);
        entries.computeIfPresent(k, (id, entry) -> entry.settled() ? entry : null);
        log.info("idempotent-clear-in-progress tenant={} key={}", tenantId, idempotencyKey);
    }

    public Optional<String> settledResult(String tenantId, String idempotencyKey) {
        Entry entry = entries.get(key(tenantId, idempotencyKey));
        return entry != null && entry.settled() ? Optional.ofNullable(entry.result()) : Optional.empty();
    }
}
