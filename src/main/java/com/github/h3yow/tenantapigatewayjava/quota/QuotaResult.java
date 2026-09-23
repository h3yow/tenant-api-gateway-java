package com.github.h3yow.tenantapigatewayjava.quota;

/** 配额/并发闸口的判定结果。 */
public enum QuotaResult {
    ALLOWED,
    QUOTA_EXCEEDED,
    CONCURRENCY_EXCEEDED
}
