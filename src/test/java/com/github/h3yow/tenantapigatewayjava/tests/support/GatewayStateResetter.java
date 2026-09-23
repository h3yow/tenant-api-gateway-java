package com.github.h3yow.tenantapigatewayjava.tests.support;

import com.github.h3yow.tenantapigatewayjava.audit.AuditService;
import com.github.h3yow.tenantapigatewayjava.idem.IdempotencyService;
import com.github.h3yow.tenantapigatewayjava.ledger.BalanceLedger;
import com.github.h3yow.tenantapigatewayjava.quota.QuotaService;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * 集成测试状态隔离：每个测试方法前从 Spring 容器取本地组件并清空
 * 配额/幂等/台账/审计状态。仅存在于测试类路径，不影响打包产物。
 */
public class GatewayStateResetter implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        ApplicationContext ctx = SpringExtension.getApplicationContext(context);
        ctx.getBean(QuotaService.class).reset();
        ctx.getBean(IdempotencyService.class).reset();
        ctx.getBean(BalanceLedger.class).reset();
        ctx.getBean(AuditService.class).reset();
    }
}
