package com.github.h3yow.tenantapigatewayjava.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 租户与配额的本地静态配置（无外部服务依赖）。 */
@Component
@ConfigurationProperties(prefix = "gateway")
public class TenantProperties {

    private Map<String, TenantConfig> tenants = new LinkedHashMap<>();

    private Upstream upstream = new Upstream();

    public Map<String, TenantConfig> getTenants() {
        return tenants;
    }

    public void setTenants(Map<String, TenantConfig> tenants) {
        this.tenants = tenants;
    }

    public Upstream getUpstream() {
        return upstream;
    }

    public void setUpstream(Upstream upstream) {
        this.upstream = upstream;
    }

    /** 本地模拟上游参数。 */
    public static class Upstream {
        /** 模拟上游成功路径耗时（毫秒），用于制造在途并发竞争。 */
        private long delayMillis = 300L;

        public long getDelayMillis() { return delayMillis; }
        public void setDelayMillis(long delayMillis) { this.delayMillis = delayMillis; }
    }

    public TenantConfig getTenant(String tenantId) {
        return tenants.get(tenantId);
    }

    public static class TenantConfig {
        private int windowSeconds = 60;
        private int quotaLimit = 10;
        private int concurrencyLimit = 5;
        private long initialBalance = 100_000L;

        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
        public int getQuotaLimit() { return quotaLimit; }
        public void setQuotaLimit(int quotaLimit) { this.quotaLimit = quotaLimit; }
        public int getConcurrencyLimit() { return concurrencyLimit; }
        public void setConcurrencyLimit(int concurrencyLimit) { this.concurrencyLimit = concurrencyLimit; }
        public long getInitialBalance() { return initialBalance; }
        public void setInitialBalance(long initialBalance) { this.initialBalance = initialBalance; }
    }
}
