package com.github.h3yow.tenantapigatewayjava.upstream;

import com.github.h3yow.tenantapigatewayjava.config.TenantProperties;
import com.github.h3yow.tenantapigatewayjava.ledger.BalanceLedger;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 本地模拟上游：根据 scenario 参数返回成功或抛出各类归一化上游异常。
 *
 * <p>支持的 scenario：
 * <ul>
 *   <li>{@code ok} 或空：正常处理（支付会实际扣减本地台账余额）</li>
 *   <li>{@code slow}：先睡眠配置毫秒数再成功，用于制造在途并发竞争</li>
 *   <li>{@code timeout}：抛出 TIMEOUT（可重试）</li>
 *   <li>{@code connection_failed}：抛出 CONNECTION_FAILED（可重试）</li>
 *   <li>{@code malformed}：抛出 MALFORMED_RESPONSE（不可重试）</li>
 *   <li>{@code business_error}：抛出 BUSINESS_ERROR / 或余额不足时同样触发</li>
 * </ul>
 * 这些异常仅在网关内部使用，绝不直接透出给调用方。
 */
@Component
public class MockUpstreamClient {

    public static final String SCENARIO_OK = "ok";
    public static final String SCENARIO_SLOW = "slow";
    public static final String SCENARIO_TIMEOUT = "timeout";
    public static final String SCENARIO_CONNECTION_FAILED = "connection_failed";
    public static final String SCENARIO_MALFORMED = "malformed";
    public static final String SCENARIO_BUSINESS_ERROR = "business_error";

    private final TenantProperties tenantProperties;
    private final BalanceLedger balanceLedger;

    public MockUpstreamClient(TenantProperties tenantProperties, BalanceLedger balanceLedger) {
        this.tenantProperties = tenantProperties;
        this.balanceLedger = balanceLedger;
    }

    /** 模拟支付写接口：成功时原子扣减余额并返回上游交易号。 */
    public UpstreamResponse pay(String tenantId, String orderId, long amount, String scenario) {
        applyScenarioFailure(scenario);
        sleepIfSlow(scenario);
        // 余额扣减发生在"上游"内部；台账 CAS 保证幂等折叠下不重复扣款
        long remaining;
        try {
            remaining = balanceLedger.debit(tenantId, amount);
        } catch (IllegalArgumentException e) {
            // 内部原始异常不直接透出，归一化为业务错误
            throw new UpstreamException(UpstreamFailureType.BUSINESS_ERROR, "UP_INSUFFICIENT_BALANCE",
                    "upstream rejected payment: " + e.getMessage(), false);
        }
        String txnId = "TXN-" + UUID.randomUUID();
        String body = "{\"orderId\":\"" + orderId + "\",\"amount\":" + amount
                + ",\"remainingBalance\":" + remaining + "}";
        return new UpstreamResponse(txnId, "CAPTURED", body);
    }

    /** 模拟查询读接口。 */
    public UpstreamResponse query(String tenantId, String orderId, String scenario) {
        applyScenarioFailure(scenario);
        sleepIfSlow(scenario);
        String txnId = "QRY-" + UUID.randomUUID();
        String body = "{\"orderId\":\"" + orderId + "\",\"balance\":"
                + balanceLedger.getBalance(tenantId) + "}";
        return new UpstreamResponse(txnId, "FOUND", body);
    }

    private void applyScenarioFailure(String scenario) {
        String s = scenario == null || scenario.isBlank() ? SCENARIO_OK : scenario.trim();
        switch (s) {
            case SCENARIO_TIMEOUT -> throw new UpstreamException(UpstreamFailureType.TIMEOUT,
                    "UP_DEADLINE_EXCEEDED", "simulated upstream read timeout after 1000ms", true);
            case SCENARIO_CONNECTION_FAILED -> throw new UpstreamException(
                    UpstreamFailureType.CONNECTION_FAILED, "UP_CONNECT_REFUSED",
                    "simulated upstream connection refused: 10.x.x.x:9443", true);
            case SCENARIO_MALFORMED -> throw new UpstreamException(UpstreamFailureType.MALFORMED_RESPONSE,
                    "UP_BAD_PAYLOAD", "simulated upstream returned \\x00\\xff malformed bytes", false);
            case SCENARIO_BUSINESS_ERROR -> throw new UpstreamException(UpstreamFailureType.BUSINESS_ERROR,
                    "UP_ORDER_REJECTED", "simulated upstream business rule rejected the order", false);
            default -> { /* ok / slow: no failure */ }
        }
    }

    private void sleepIfSlow(String scenario) {
        if (SCENARIO_SLOW.equalsIgnoreCase(scenario)) {
            try {
                Thread.sleep(tenantProperties.getUpstream().getDelayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpstreamException(UpstreamFailureType.TIMEOUT,
                        "UP_INTERRUPTED", "upstream wait interrupted", true);
            }
        }
    }
}
