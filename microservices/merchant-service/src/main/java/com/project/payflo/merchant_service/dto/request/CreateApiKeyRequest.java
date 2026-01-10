package com.project.payflo.merchant_service.dto.request;


import com.project.payflo.common_lib.enums.Environment;

public record CreateApiKeyRequest(
        Environment environment
) {
}
