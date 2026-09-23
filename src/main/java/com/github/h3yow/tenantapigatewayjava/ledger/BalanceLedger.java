package com.github.h3yow.tenantapigatewayjava.ledger;

import com.github.h3yow.tenantapigatewayjava.config.TenantProperties;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 本地模拟租户余额台账，验证写操作不重复扣减。
 * 内存态，进程重启后回到配置的初始余额（一致性语义见 README）。
 */
@Component
public class BalanceLedger {

    private final ConcurrentHashMap<String, Long> balances = new ConcurrentHashMap<>();
    private final TenantProperties tenantProperties;

    public BalanceLedger(TenantProperties tenantProperties) {
        this.tenantProperties = tenantProperties;
    }

    private long initial(String tenantId) {
        TenantProperties.TenantConfig cfg = tenantProperties.getTenant(tenantId);
        return cfg == null ? 0L : cfg.getInitialBalance();
    }

    /**
     * CAS 原子扣减；余额不足抛 IllegalArgumentException（由上游归一化为业务错误）。
     *
     * @return 扣减后的余额
     */
    public long debit(String tenantId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        Long current;
        Long next;
        do {
            current = balances.computeIfAbsent(tenantId, this::initial);
            if (current < amount) {
                throw new IllegalArgumentException(
                        "insufficient balance: current=" + current + ", amount=" + amount);
            }
            next = current - amount;
        } while (!balances.replace(tenantId, current, next));
        return next;
    }

    public long getBalance(String tenantId) {
        return balances.computeIfAbsent(tenantId, this::initial);
    }

    /** 测试隔离用。 */
    public void reset() {
        balances.clear();
    }
}
