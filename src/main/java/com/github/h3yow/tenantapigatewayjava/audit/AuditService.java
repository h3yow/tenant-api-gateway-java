package com.github.h3yow.tenantapigatewayjava.audit;

import com.github.h3yow.tenantapigatewayjava.core.clock.GatewayClock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 审计服务：内存保存每次请求的判定记录并打印结构化日志。
 * 每条记录都可还原租户、入口、请求输入、窗口、最终判定与依据。
 * 内存态，进程重启后审计历史清空（语义见 README）。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger("gateway.audit");

    private final GatewayClock clock;
    private final CopyOnWriteArrayList<AuditRecord> records = new CopyOnWriteArrayList<>();

    public AuditService(GatewayClock clock) {
        this.clock = clock;
    }

    public void record(AuditRecord auditRecord) {
        records.add(auditRecord);
        // 结构化单行日志：测试输出中可直接检索租户/窗口/输入/判定依据
        log.info("AUDIT tenant={} entry={} requestId={} idempotencyKey={} window=[{},{}) "
                        + "input={} decision={} reasonCode={} upstreamFailureType={} "
                        + "upstreamTxnId={} durationMs={}",
                auditRecord.tenantId(),
                auditRecord.entry(),
                auditRecord.requestId(),
                auditRecord.idempotencyKey(),
                auditRecord.windowStartEpoch(),
                auditRecord.windowEndEpoch(),
                auditRecord.requestSummary(),
                auditRecord.decision(),
                auditRecord.reasonCode(),
                auditRecord.upstreamFailureType(),
                auditRecord.upstreamTxnId(),
                auditRecord.durationMillis());
    }

    public List<AuditRecord> findByTenant(String tenantId) {
        return records.stream().filter(r -> r.tenantId().equals(tenantId)).toList();
    }

    public List<AuditRecord> all() {
        return List.copyOf(records);
    }

    /** 测试隔离用。 */
    public void reset() {
        records.clear();
    }

    public long nowEpoch() {
        return clock.now().getEpochSecond();
    }
}
