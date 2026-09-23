package com.github.h3yow.tenantapigatewayjava.core.clock;

import java.time.Instant;

/** 时间源抽象，便于测试窗口滚动。 */
public interface GatewayClock {
    Instant now();
}
