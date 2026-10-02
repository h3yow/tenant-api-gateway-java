package com.github.h3yow.tenantapigatewayjava.core;

/**
 * 时间来源抽象，便于测试中驱动窗口与冷静期。
 */
public interface TimeSource {
    long nowMillis();
}
