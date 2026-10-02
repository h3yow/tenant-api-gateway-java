package com.github.h3yow.tenantapigatewayjava.circuit;

/**
 * 熔断器对一次请求的准入结论。
 *
 * @param permitted           是否允许真正调用上游
 * @param probe               是否为探测请求
 * @param probeFailurePenalty 被挡下时，当前快速失败窗口是否由探测失败重新计时导致
 * @param retryAfterMillis    被挡下时建议的重试等待
 */
public record Admission(boolean permitted, boolean probe, boolean probeFailurePenalty,
                        long retryAfterMillis) {

    public static Admission pass() {
        return new Admission(true, false, false, 0L);
    }

    public static Admission passProbe() {
        return new Admission(true, true, false, 0L);
    }

    public static Admission reject(boolean probeFailurePenalty, long retryAfterMillis) {
        return new Admission(false, false, probeFailurePenalty, retryAfterMillis);
    }
}
