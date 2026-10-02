package com.github.h3yow.tenantapigatewayjava.upstream;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UpstreamExceptionNormalizerTest {

    private final UpstreamExceptionNormalizer normalizer = new UpstreamExceptionNormalizer();

    @Test
    void timeoutMapsToUpstreamUnavailable() {
        assertEquals("UPSTREAM_UNAVAILABLE",
                normalizer.normalize(new SocketTimeoutException("read timed out")));
    }

    @Test
    void connectionFailureInCauseMapsToUpstreamUnavailable() {
        Exception wrapped = new RuntimeException("call failed", new IOException("connection refused"));
        assertEquals("UPSTREAM_UNAVAILABLE", normalizer.normalize(wrapped));
    }

    @Test
    void unknownErrorMapsToUpstreamError() {
        assertEquals("UPSTREAM_ERROR", normalizer.normalize(new IllegalStateException("boom")));
    }

    @Test
    void nullErrorIsSafe() {
        assertEquals("UPSTREAM_ERROR", normalizer.normalize(null));
    }

    @Test
    void normalizedReasonNeverContainsRawMessage() {
        String reason = normalizer.normalize(new RuntimeException("secret-internal-host:8080 failed"));
        assertFalse(reason.contains("secret-internal-host"));
    }
}
