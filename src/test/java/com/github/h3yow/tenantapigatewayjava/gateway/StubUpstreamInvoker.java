package com.github.h3yow.tenantapigatewayjava.gateway;

import com.github.h3yow.tenantapigatewayjava.upstream.UpstreamInvoker;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试上游桩：可切换成功/失败，并记录真正打到上游的调用次数。
 */
class StubUpstreamInvoker implements UpstreamInvoker {

    private volatile boolean healthy = true;
    private final AtomicInteger calls = new AtomicInteger();

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    public int callCount() {
        return calls.get();
    }

    @Override
    public String invoke(String tenantId, String data) throws Exception {
        calls.incrementAndGet();
        if (!healthy) {
            throw new java.net.SocketTimeoutException("simulated upstream timeout");
        }
        return "ok:" + tenantId + ":" + data;
    }
}
