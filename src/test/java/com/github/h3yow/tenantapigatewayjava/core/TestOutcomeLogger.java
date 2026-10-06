package com.github.h3yow.tenantapigatewayjava.core;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局自动注册的 JUnit 扩展：为每个测试用例输出一条结果日志，
 * 便于在跑测试时直接看出各业务用例的通过情况。
 * 通过 META-INF/services + junit.jupiter.extensions.autodetection.enabled=true 注册。
 */
public class TestOutcomeLogger implements TestWatcher {

    private static final Logger log = LoggerFactory.getLogger(TestOutcomeLogger.class);

    @Override
    public void testSuccessful(ExtensionContext context) {
        log.info("test-outcome case={}#{} result=PASSED",
                context.getRequiredTestClass().getSimpleName(), context.getRequiredTestMethod().getName());
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        log.warn("test-outcome case={}#{} result=FAILED cause={}",
                context.getRequiredTestClass().getSimpleName(), context.getRequiredTestMethod().getName(),
                String.valueOf(cause));
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        log.warn("test-outcome case={}#{} result=ABORTED cause={}",
                context.getRequiredTestClass().getSimpleName(), context.getRequiredTestMethod().getName(),
                String.valueOf(cause));
    }
}
