package com.github.h3yow.tenantapigatewayjava.tenant;

import com.github.h3yow.tenantapigatewayjava.config.TenantProperties;
import com.github.h3yow.tenantapigatewayjava.error.GatewayErrorCode;
import com.github.h3yow.tenantapigatewayjava.error.GatewayException;
import org.springframework.stereotype.Component;

/** 从请求头解析并校验租户。 */
@Component
public class TenantResolver {

    private final TenantProperties tenantProperties;

    public TenantResolver(TenantProperties tenantProperties) {
        this.tenantProperties = tenantProperties;
    }

    /**
     * 校验租户标识。
     *
     * @throws GatewayException MISSING_TENANT / UNKNOWN_TENANT
     */
    public String resolve(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new GatewayException(GatewayErrorCode.MISSING_TENANT);
        }
        String trimmed = tenantId.trim();
        if (tenantProperties.getTenant(trimmed) == null) {
            throw new GatewayException(GatewayErrorCode.UNKNOWN_TENANT,
                    "未知或未开通的租户: " + trimmed, null);
        }
        return trimmed;
    }
}
