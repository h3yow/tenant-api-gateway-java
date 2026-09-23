package com.github.h3yow.tenantapigatewayjava.web;

import com.github.h3yow.tenantapigatewayjava.audit.AuditRecord;
import com.github.h3yow.tenantapigatewayjava.audit.AuditService;
import com.github.h3yow.tenantapigatewayjava.gateway.GatewayResponse;
import com.github.h3yow.tenantapigatewayjava.gateway.GatewayService;
import com.github.h3yow.tenantapigatewayjava.web.dto.PaymentRequest;
import com.github.h3yow.tenantapigatewayjava.web.dto.QueryRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多入口网关 REST 接口。
 * <ul>
 *   <li>POST /gw/payments：写入口，要求 Idempotency-Key</li>
 *   <li>POST /gw/queries ：读入口，仅受配额/并发闸口约束</li>
 *   <li>GET  /gw/audit[/{tenantId}]：查看本地审计记录</li>
 * </ul>
 */
@RestController
@RequestMapping("/gw")
public class GatewayController {

    private final GatewayService gatewayService;
    private final AuditService auditService;

    public GatewayController(GatewayService gatewayService, AuditService auditService) {
        this.gatewayService = gatewayService;
        this.auditService = auditService;
    }

    @PostMapping("/payments")
    public ResponseEntity<GatewayResponse> pay(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestBody PaymentRequest body) {
        GatewayResponse resp = gatewayService.pay(tenantId, idempotencyKey,
                body.orderId(), body.amount(), body.scenario(), requestId);
        return ResponseEntity.status(HttpStatus.OK).body(resp);
    }

    @PostMapping("/queries")
    public ResponseEntity<GatewayResponse> query(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestBody QueryRequest body) {
        GatewayResponse resp = gatewayService.query(tenantId, body.orderId(),
                body.scenario(), requestId);
        return ResponseEntity.status(HttpStatus.OK).body(resp);
    }

    /** 审计查看：还原租户、请求输入、窗口与最终判定。 */
    @GetMapping("/audit")
    public List<AuditRecord> allAudit() {
        return auditService.all();
    }

    @GetMapping("/audit/{tenantId}")
    public List<AuditRecord> tenantAudit(@PathVariable String tenantId) {
        return auditService.findByTenant(tenantId);
    }
}
