package com.project.payflo.api_gateway_service.security;

public class RoleForbiddenException extends RuntimeException {

    public RoleForbiddenException(String message) {
        super(message);
    }
}
