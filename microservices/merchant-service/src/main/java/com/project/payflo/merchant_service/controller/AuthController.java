package com.project.payflo.merchant_service.controller;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.merchant_service.dto.request.LoginRequest;
import com.project.payflo.merchant_service.dto.request.MerchantSignupRequest;
import com.project.payflo.merchant_service.dto.request.SessionRequests.ChangePasswordRequest;
import com.project.payflo.merchant_service.dto.request.SessionRequests.LogoutRequest;
import com.project.payflo.merchant_service.dto.request.SessionRequests.RefreshRequest;
import com.project.payflo.merchant_service.dto.response.LoginResponse;
import com.project.payflo.merchant_service.dto.response.MerchantResponse;
import com.project.payflo.merchant_service.security.CallerPolicy;
import com.project.payflo.merchant_service.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CallerPolicy callerPolicy;

    @PostMapping("/signup")
    public ResponseEntity<MerchantResponse> signup(@RequestBody @Valid MerchantSignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                authService.signup(request)
        );
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return ResponseEntity.status(HttpStatus.OK).body(
                authService.login(request)
        );
    }

    // A public route: the whole point is that the access token has expired.
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@RequestBody @Valid RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody(required = false) LogoutRequest request) {
        callerPolicy.requireDashboardUser();
        authService.logout(authorization, request != null ? request.refreshToken() : null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@RequestBody @Valid ChangePasswordRequest request) {
        String email = callerPolicy.requireDashboardUser();
        authService.changePassword(email, request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
