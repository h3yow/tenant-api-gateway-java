package com.github.h3yow.tenantapigatewayjava.web.dto;

/** 支付请求体。scenario 用于本地模拟上游各类响应。 */
public record PaymentRequest(String orderId, Long amount, String scenario) {
}
