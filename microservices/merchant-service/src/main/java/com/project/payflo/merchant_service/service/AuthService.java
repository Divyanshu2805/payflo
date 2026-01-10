package com.project.payflo.merchant_service.service;


import com.project.payflo.merchant_service.dto.request.LoginRequest;
import com.project.payflo.merchant_service.dto.request.MerchantSignupRequest;
import com.project.payflo.merchant_service.dto.response.LoginResponse;
import com.project.payflo.merchant_service.dto.response.MerchantResponse;

public interface AuthService {
    MerchantResponse signup(MerchantSignupRequest request);

    LoginResponse login(LoginRequest request);
}
