package com.github.h3yow.tenantapigatewayjava.upstream;

import org.springframework.stereotype.Component;

/**
 * 默认上游调用实现（占位：真实环境替换为 HTTP 客户端）。
 */
@Component
public class DefaultUpstreamInvoker implements UpstreamInvoker {
    @Override
    public String invoke(String tenantId, String data) {
        return "upstream-ok:" + tenantId + ":" + data;
    }
}
