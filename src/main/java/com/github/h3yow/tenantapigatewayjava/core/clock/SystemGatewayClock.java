package com.github.h3yow.tenantapigatewayjava.core.clock;

import java.time.Instant;
import org.springframework.stereotype.Component;

/** 基于系统时间的默认时间源。 */
@Component
public class SystemGatewayClock implements GatewayClock {
    @Override
    public Instant now() {
        return Instant.now();
    }
}
