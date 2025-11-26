package com.project.payflo.merchant.service;

import com.project.payflo.merchant.dto.request.LoginRequest;
import com.project.payflo.merchant.dto.request.MerchantSignupRequest;
import com.project.payflo.merchant.dto.response.LoginResponse;
import com.project.payflo.merchant.dto.response.MerchantResponse;

public interface AuthService {
    MerchantResponse signup(MerchantSignupRequest request);

    LoginResponse login(LoginRequest request);
}
