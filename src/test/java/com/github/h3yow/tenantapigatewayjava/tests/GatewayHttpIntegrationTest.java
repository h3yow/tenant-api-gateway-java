package com.github.h3yow.tenantapigatewayjava.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.tenantapigatewayjava.tests.support.GatewayStateResetter;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 多入口 HTTP 级行为：放行/拒绝结论稳定、错误可区分、审计可还原。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(GatewayStateResetter.class)
class GatewayHttpIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GatewayHttpIntegrationTest.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders headers(String tenant, String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (tenant != null) {
            h.set("X-Tenant-Id", tenant);
        }
        if (idempotencyKey != null) {
            h.set("Idempotency-Key", idempotencyKey);
        }
        return h;
    }

    @Test
    @DisplayName("支付入口：成功 -> 同键重放；缺租户/未知租户/超额参数返回可区分错误")
    void paymentEntrySuccessReplayAndDistinctErrors() throws Exception {
        // 成功
        HttpHeaders h = headers("tenant-a", "HTTP-KEY-1");
        ResponseEntity<String> ok = rest.postForEntity(url("/gw/payments"),
                new HttpEntity<>(Map.of("orderId", "ORD-H1", "amount", 100, "scenario", "ok"), h),
                String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode okBody = mapper.readTree(ok.getBody());
        log.info("HTTP tenant=tenant-a window=- 输入=pay ORD-H1 100 判定={} replayed={}",
                okBody.path("decision").asText(), okBody.path("replayed").asBoolean());
        assertThat(okBody.path("decision").asText()).isEqualTo("ALLOWED_UPSTREAM_OK");

        // 同键重放
        ResponseEntity<String> replay = rest.postForEntity(url("/gw/payments"),
                new HttpEntity<>(Map.of("orderId", "ORD-H1", "amount", 100, "scenario", "ok"), h),
                String.class);
        JsonNode replayBody = mapper.readTree(replay.getBody());
        log.info("HTTP tenant=tenant-a window=- 输入=同键重放 判定={} replayed={}",
                replayBody.path("decision").asText(), replayBody.path("replayed").asBoolean());
        assertThat(replayBody.path("decision").asText()).isEqualTo("REPLAY_OK");
        assertThat(replayBody.path("replayed").asBoolean()).isTrue();

        // 缺租户
        ResponseEntity<String> noTenant = rest.postForEntity(url("/gw/payments"),
                new HttpEntity<>(Map.of("orderId", "O", "amount", 1, "scenario", "ok"),
                        headers(null, "K")),
                String.class);
        JsonNode nt = mapper.readTree(noTenant.getBody());
        log.info("HTTP tenant=- 输入=缺X-Tenant-Id 判定={} http={}",
                nt.path("code").asText(), noTenant.getStatusCode().value());
        assertThat(noTenant.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(nt.path("code").asText()).isEqualTo("MISSING_TENANT");

        // 未知租户
        ResponseEntity<String> unknown = rest.postForEntity(url("/gw/payments"),
                new HttpEntity<>(Map.of("orderId", "O", "amount", 1, "scenario", "ok"),
                        headers("nobody", "K")),
                String.class);
        JsonNode ub = mapper.readTree(unknown.getBody());
        log.info("HTTP tenant=nobody 输入=未知租户 判定={} http={}",
                ub.path("code").asText(), unknown.getStatusCode().value());
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ub.path("code").asText()).isEqualTo("UNKNOWN_TENANT");

        // 参数错误（金额非正）
        ResponseEntity<String> bad = rest.postForEntity(url("/gw/payments"),
                new HttpEntity<>(Map.of("orderId", "O", "amount", -5, "scenario", "ok"),
                        headers("tenant-a", "HTTP-KEY-BAD")),
                String.class);
        JsonNode bb = mapper.readTree(bad.getBody());
        log.info("HTTP tenant=tenant-a 输入=amount=-5 判定={} retryHint={}",
                bb.path("code").asText(), bb.path("retryHint").isNull());
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bb.path("code").asText()).isEqualTo("INVALID_PARAMETER");
    }

    @Test
    @DisplayName("查询入口：上游超时返回 504 UPSTREAM_TIMEOUT 且带可安全重试提示，错误响应稳定")
    void queryEntryTimeoutNormalized() throws Exception {
        ResponseEntity<String> resp = rest.postForEntity(url("/gw/queries"),
                new HttpEntity<>(Map.of("orderId", "ORD-Q", "scenario", "timeout"),
                        headers("tenant-a", null)),
                String.class);
        JsonNode body = mapper.readTree(resp.getBody());
        log.info("HTTP tenant=tenant-a 输入=query scenario=timeout 判定={} http={} retryable={} hint={}",
                body.path("code").asText(), resp.getStatusCode().value(),
                body.path("retryable").asBoolean(), body.path("retryHint").asText());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(body.path("code").asText()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(body.path("retryable").asBoolean()).isTrue();
        assertThat(body.path("retryHint").asText()).isNotBlank();
        assertThat(body.has("timestamp")).isTrue();
    }

    @Test
    @DisplayName("跨租户隔离：tenant-b 配额(3)耗尽不影响 tenant-a；拒绝原因与窗口独立可区分")
    void tenantQuotasAreIsolated() throws Exception {
        for (int i = 1; i <= 3; i++) {
            ResponseEntity<String> r = rest.postForEntity(url("/gw/queries"),
                    new HttpEntity<>(Map.of("orderId", "QB-" + i, "scenario", "ok"),
                            headers("tenant-b", null)),
                    String.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> rejected = rest.postForEntity(url("/gw/queries"),
                new HttpEntity<>(Map.of("orderId", "QB-4", "scenario", "ok"),
                        headers("tenant-b", null)),
                String.class);
        JsonNode rb = mapper.readTree(rejected.getBody());
        log.info("HTTP tenant=tenant-b 输入=第4次查询(上限3) 判定={} http={} hint={}",
                rb.path("code").asText(), rejected.getStatusCode().value(),
                rb.path("retryHint").asText());
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rb.path("code").asText()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(rb.path("retryHint").asText()).contains("3/3");

        // tenant-a 仍有独立配额可正常放行
        ResponseEntity<String> aOk = rest.postForEntity(url("/gw/queries"),
                new HttpEntity<>(Map.of("orderId", "QA-1", "scenario", "ok"),
                        headers("tenant-a", null)),
                String.class);
        JsonNode ab = mapper.readTree(aOk.getBody());
        log.info("HTTP tenant=tenant-a 输入=tenant-b耗尽后tenant-a查询 判定={} http={}",
                ab.path("decision").asText(), aOk.getStatusCode().value());
        assertThat(aOk.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ab.path("decision").asText()).isEqualTo("ALLOWED_UPSTREAM_OK");
    }

    @Test
    @DisplayName("审计接口可按租户还原：租户、窗口、输入与最终判定齐全")
    void auditTrailIsRecoverable() {
        rest.postForEntity(url("/gw/payments"),
                new HttpEntity<>(Map.of("orderId", "ORD-AUD", "amount", 7, "scenario", "ok"),
                        headers("tenant-a", "AUD-KEY-1")),
                String.class);

        ResponseEntity<JsonNode[]> audit = rest.getForEntity(url("/gw/audit/tenant-a"),
                JsonNode[].class);
        assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode[] rows = audit.getBody();
        assertThat(rows).isNotEmpty();
        JsonNode last = rows[rows.length - 1];
        log.info("AUDIT-HTTP tenant={} entry={} window=[},{}) input={} decision={} reason={}",
                last.path("tenantId").asText(), last.path("entry").asText(),
                last.path("windowStartEpoch").asLong(), last.path("windowEndEpoch").asLong(),
                last.path("requestSummary").asText(), last.path("decision").asText(),
                last.path("reasonCode").asText());
        assertThat(last.path("tenantId").asText()).isEqualTo("tenant-a");
        assertThat(last.path("decision").asText()).isEqualTo("ALLOWED_UPSTREAM_OK");
        assertThat(last.path("windowStartEpoch").asLong()).isPositive();
        assertThat(last.path("windowEndEpoch").asLong())
                .isGreaterThan(last.path("windowStartEpoch").asLong());
        assertThat(last.path("requestSummary").asText()).contains("ORD-AUD");
    }
}
