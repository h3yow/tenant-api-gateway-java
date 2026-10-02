package com.github.h3yow.tenantapigatewayjava.upstream;

/**
 * 上游调用抽象。生产实现走 HTTP；测试中可注入桩。
 */
@FunctionalInterface
public interface UpstreamInvoker {

    /**
     * 调用上游；失败时抛出异常，由网关归一化处理。
     */
    String invoke(String tenantId, String data) throws Exception;
}
