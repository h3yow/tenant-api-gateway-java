package com.github.h3yow.tenantapigatewayjava.core;

import com.github.h3yow.tenantapigatewayjava.core.TimeSource;

/**
 * 测试用可控时钟，可精确推进窗口与冷静期。
 */
public class MutableTimeSource implements TimeSource {

    private long now;

    public MutableTimeSource() {
        this(0L);
    }

    public MutableTimeSource(long startMillis) {
        this.now = startMillis;
    }

    @Override
    public long nowMillis() {
        return now;
    }

    public void advance(long deltaMillis) {
        this.now += deltaMillis;
    }

    public void set(long millis) {
        this.now = millis;
    }
}
