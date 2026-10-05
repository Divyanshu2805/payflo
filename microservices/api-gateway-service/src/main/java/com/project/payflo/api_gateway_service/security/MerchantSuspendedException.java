package com.project.payflo.api_gateway_service.security;

public class MerchantSuspendedException extends RuntimeException {

    public MerchantSuspendedException() {
        super("This merchant account is suspended");
    }
}
