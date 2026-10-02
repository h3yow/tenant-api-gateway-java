package com.github.h3yow.tenantapigatewayjava.web;

import com.github.h3yow.tenantapigatewayjava.circuit.CircuitBreakerRegistry;
import com.github.h3yow.tenantapigatewayjava.concurrency.ConcurrencyLimiter;
import com.github.h3yow.tenantapigatewayjava.core.MutableTimeSource;
import com.github.h3yow.tenantapigatewayjava.gateway.GatewayProperties;
import com.github.h3yow.tenantapigatewayjava.gateway.GatewayService;
import com.github.h3yow.tenantapigatewayjava.idempotency.IdempotencyStore;
import com.github.h3yow.tenantapigatewayjava.quota.QuotaLimiter;
import com.github.h3yow.tenantapigatewayjava.upstream.UpstreamExceptionNormalizer;
import com.github.h3yow.tenantapigatewayjava.upstream.UpstreamInvoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GatewayControllerTest {

    private MutableTimeSource clock;
    private GatewayProperties properties;
    private CircuitBreakerRegistry registry;
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clock = new MutableTimeSource();
        properties = new GatewayProperties();
        properties.setFailureThreshold(2);
        properties.setOpenDurationMillis(10_000L);
        properties.setProbePermits(1);
        properties.setProbeWaitMillis(2_000L);
        registry = new CircuitBreakerRegistry(clock, properties);

        UpstreamInvoker invoker = (tenant, data) -> {
            if (!healthy.get()) {
                throw new java.net.SocketTimeoutException("boom");
            }
            return "http-ok";
        };
        GatewayService service = new GatewayService(
                new QuotaLimiter(clock), new ConcurrencyLimiter(clock), new IdempotencyStore(),
                invoker, new UpstreamExceptionNormalizer(), registry, properties);
        mockMvc = MockMvcBuilders.standaloneSetup(new GatewayController(service)).build();
    }

    private String body(String tenant, String key, String payload, String data) {
        String keyJson = key == null ? "null" : ("\"" + key + "\"");
        return "{\"tenantId\":\"" + tenant + "\",\"idempotencyKey\":" + keyJson
                + ",\"payload\":\"" + payload + "\",\"data\":\"" + data + "\"}";
    }

    @Test
    void passResponseShape() throws Exception {
        mockMvc.perform(post("/api/gateway/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", null, "p", "d")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PASS"))
                .andExpect(jsonPath("$.reason").value("OK"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.result").value("http-ok"));
    }

    @Test
    void circuitOpenFastFailShapeAndProbeRecovery() throws Exception {
        healthy.set(false);
        mockMvc.perform(post("/api/gateway/invoke").contentType(MediaType.APPLICATION_JSON)
                .content(body("t1", null, "p", "d"))).andExpect(status().isOk());
        mockMvc.perform(post("/api/gateway/invoke").contentType(MediaType.APPLICATION_JSON)
                .content(body("t1", null, "p", "d"))).andExpect(status().isOk());

        // 快速失败：独立原因码 + 重试提示，且不含原始异常
        mockMvc.perform(post("/api/gateway/invoke").contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", null, "p", "d")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REJECT_CIRCUIT_OPEN"))
                .andExpect(jsonPath("$.reason").value("CIRCUIT_OPEN"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.retryAfterSeconds").value(10))
                .andExpect(jsonPath("$.result").doesNotExist());

        // 冷静期结束、上游恢复：探测放行
        clock.advance(10_000L);
        healthy.set(true);
        mockMvc.perform(post("/api/gateway/invoke").contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", null, "p", "d")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PASS_PROBE"));

        mockMvc.perform(post("/api/gateway/invoke").contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", null, "p", "d")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PASS"));
    }

    @Test
    void idempotentReplayDuringOpenSucceeds() throws Exception {
        // 先落定
        mockMvc.perform(post("/api/gateway/invoke").contentType(MediaType.APPLICATION_JSON)
                .content(body("t1", "k1", "body", "d")))
                .andExpect(jsonPath("$.decision").value("PASS"));

        healthy.set(false);
        mockMvc.perform(post("/api/gateway/invoke").contentType(MediaType.APPLICATION_JSON)
                .content(body("t1", null, "p", "d"))).andExpect(status().isOk());
        mockMvc.perform(post("/api/gateway/invoke").contentType(MediaType.APPLICATION_JSON)
                .content(body("t1", null, "p", "d"))).andExpect(status().isOk());

        // 熔断打开期间，同键同内容仍然重放
        mockMvc.perform(post("/api/gateway/invoke").contentType(MediaType.APPLICATION_JSON)
                        .content(body("t1", "k1", "body", "d")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("IDEMPOTENT_REPLAY"))
                .andExpect(jsonPath("$.result").value("http-ok"));
    }
}
