package com.project.payflo.merchant_service.service;

import com.project.payflo.merchant_service.dto.request.LoginRequest;
import com.project.payflo.merchant_service.dto.request.MerchantSignupRequest;
import com.project.payflo.merchant_service.dto.response.LoginResponse;
import com.project.payflo.merchant_service.dto.response.MerchantResponse;

public interface AuthService {

    MerchantResponse signup(MerchantSignupRequest request);

    LoginResponse login(LoginRequest request);

    /** Exchanges a refresh token for a new access token and refresh token; the old refresh token is used up. */
    LoginResponse refresh(String refreshToken);

    /** Ends the session of the access token (given as the Authorization header) and, if given, a refresh token. */
    void logout(String authorizationHeader, String refreshToken);

    /** Changes a user's password and ends all their sessions. */
    void changePassword(String email, String currentPassword, String newPassword);
}
