package com.github.h3yow.tenantapigatewayjava.web;

import com.github.h3yow.tenantapigatewayjava.gateway.GatewayService;
import com.github.h3yow.tenantapigatewayjava.model.GatewayRequest;
import com.github.h3yow.tenantapigatewayjava.model.GatewayResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关入口（路径与请求结构保持既有约定）。
 */
@RestController
@RequestMapping("/api/gateway")
public class GatewayController {

    private final GatewayService gatewayService;

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @PostMapping("/invoke")
    public GatewayResponse invoke(@RequestBody GatewayRequest request) {
        return gatewayService.handle(request);
    }
}
