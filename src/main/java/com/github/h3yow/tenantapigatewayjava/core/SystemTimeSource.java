package com.github.h3yow.tenantapigatewayjava.core;

import org.springframework.stereotype.Component;

/**
 * 基于系统时钟的时间来源。
 */
@Component
public class SystemTimeSource implements TimeSource {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
