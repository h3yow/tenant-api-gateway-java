package com.github.h3yow.tenantapigatewayjava.model;

/**
 * 网关对一次请求的统一响应。被快速失败/限流挡下时也返回该结构，不暴露上游原始异常。
 *
 * @param decision    最终判定类别
 * @param reason       可机读原因编码（与 decision 对应）
 * @param retryable    是否建议客户端重试
 * @param retryAfterSeconds 建议的重试等待秒数（可空）
 * @param result       上游或幂等重放的业务结果（被挡下时为空）
 */
public record GatewayResponse(DecisionCode decision, String reason, boolean retryable,
                              Long retryAfterSeconds, String result) {

    public static GatewayResponse of(DecisionCode decision, String reason, boolean retryable,
                                     Long retryAfterSeconds, String result) {
        return new GatewayResponse(decision, reason, retryable, retryAfterSeconds, result);
    }
}
