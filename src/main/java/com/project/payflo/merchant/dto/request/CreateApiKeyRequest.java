package com.project.payflo.merchant.dto.request;

import com.project.payflo.common.enums.Environment;

public record CreateApiKeyRequest(
        Environment environment
) {
}
