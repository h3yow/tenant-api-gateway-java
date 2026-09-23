package com.github.h3yow.tenantapigatewayjava.tests.support;

import com.github.h3yow.tenantapigatewayjava.core.clock.GatewayClock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** 测试用可控时间源（毫秒精度 epoch 毫秒内部存储，按秒读取）。 */
public class MutableClock implements GatewayClock {

    private final AtomicLong epochMillis;

    public MutableClock() {
        this(1_700_000_000_000L);
    }

    public MutableClock(long epochMillis) {
        this.epochMillis = new AtomicLong(epochMillis);
    }

    @Override
    public Instant now() {
        return Instant.ofEpochMilli(epochMillis.get());
    }

    public void advanceSeconds(long seconds) {
        epochMillis.addAndGet(seconds * 1000L);
    }
}
