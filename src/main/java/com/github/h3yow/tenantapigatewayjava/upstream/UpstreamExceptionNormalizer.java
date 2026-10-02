package com.github.h3yow.tenantapigatewayjava.upstream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 将上游抛出的各类异常归一化为统一的可机读原因，不向前端泄露内部细节。
 * <ul>
 *   <li>连接/超时/不可用类问题 -> UPSTREAM_UNAVAILABLE（可重试）</li>
 *   <li>其余异常 -> UPSTREAM_ERROR（可重试）</li>
 * </ul>
 * 无论哪一类，原始异常信息只进入服务端日志，不出现在响应里。
 */
@Component
public class UpstreamExceptionNormalizer {

    private static final Logger log = LoggerFactory.getLogger(UpstreamExceptionNormalizer.class);

    public String normalize(Throwable error) {
        if (error == null) {
            return "UPSTREAM_ERROR";
        }
        String code = classify(error);
        log.warn("upstream-error-normalized type={} normalized={} message={}",
                error.getClass().getSimpleName(), code, String.valueOf(error.getMessage()));
        return code;
    }

    private String classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String name = t.getClass().getName().toLowerCase();
            if (name.contains("timeout") || name.contains("connect") || name.contains("unavailable")) {
                return "UPSTREAM_UNAVAILABLE";
            }
            String message = t.getMessage();
            if (message != null) {
                String m = message.toLowerCase();
                if (m.contains("timeout") || m.contains("connect") || m.contains("unavailable")) {
                    return "UPSTREAM_UNAVAILABLE";
                }
            }
        }
        return "UPSTREAM_ERROR";
    }
}
