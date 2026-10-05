package com.project.payflo.common_lib.context;

import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.UUID;

@Getter
@Setter
public class MerchantContext {

    private UUID merchantId;
    private String keyId;
    // Set for a dashboard (JWT) caller, null for an API key: who is acting, and their role in the merchant.
    private String userEmail;
    private String userRole;
    // True only for a request the gateway authenticated with the platform admin key (the /v1/admin API): no
    // merchant is acting, the platform operator is.
    private boolean platformAdmin;
    // The caller's address as the gateway saw it, for the audit log.
    private String clientIp;
}
