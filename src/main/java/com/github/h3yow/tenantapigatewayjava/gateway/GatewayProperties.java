package com.github.h3yow.tenantapigatewayjava.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 网关既有入口约定的可调参数（配额窗口、并发上限、熔断阈值等）。
 */
@Component
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /** 配额窗口长度（秒）。 */
    private int windowSeconds = 60;
    /** 单窗口每租户最大请求数（真正调用上游的请求）。 */
    private int windowLimit = 100;
    /** 每租户最大在途并发。 */
    private int maxConcurrent = 20;
    /** 连续失败多少次后打开熔断。 */
    private int failureThreshold = 5;
    /** 熔断打开后的快速失败时长（毫秒）。 */
    private long openDurationMillis = 30_000L;
    /** 冷静期后同时放行的探测请求数。 */
    private int probePermits = 1;
    /** 探测进行中给被挡请求的等待提示（毫秒）。 */
    private long probeWaitMillis = 5_000L;

    public int getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    public int getWindowLimit() { return windowLimit; }
    public void setWindowLimit(int windowLimit) { this.windowLimit = windowLimit; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
    public long getOpenDurationMillis() { return openDurationMillis; }
    public void setOpenDurationMillis(long openDurationMillis) { this.openDurationMillis = openDurationMillis; }
    public int getProbePermits() { return probePermits; }
    public void setProbePermits(int probePermits) { this.probePermits = probePermits; }
    public long getProbeWaitMillis() { return probeWaitMillis; }
    public void setProbeWaitMillis(long probeWaitMillis) { this.probeWaitMillis = probeWaitMillis; }
}
