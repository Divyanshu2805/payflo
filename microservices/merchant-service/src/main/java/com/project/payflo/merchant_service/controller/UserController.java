package com.project.payflo.merchant_service.controller;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.merchant_service.dto.request.MerchantProfileRequests.CreateUserRequest;
import com.project.payflo.merchant_service.dto.request.MerchantProfileRequests.UpdateUserRoleRequest;
import com.project.payflo.merchant_service.dto.response.UserResponse;
import com.project.payflo.merchant_service.service.UserManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/merchants/users")
@RequiredArgsConstructor
public class UserController {

    private final UserManagementService userManagementService;
    private final MerchantContext merchantContext;

    @GetMapping
    public ResponseEntity<List<UserResponse>> list() {
        return ResponseEntity.ok(userManagementService.list(merchantContext.getMerchantId()));
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userManagementService.create(merchantContext.getMerchantId(), request));
    }

    @PutMapping("/{userId}/role")
    public ResponseEntity<UserResponse> changeRole(@PathVariable UUID userId,
                                                   @Valid @RequestBody UpdateUserRoleRequest request) {
        return ResponseEntity.ok(userManagementService.changeRole(merchantContext.getMerchantId(), userId, request.role()));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> remove(@PathVariable UUID userId) {
        userManagementService.remove(merchantContext.getMerchantId(), userId);
        return ResponseEntity.noContent().build();
    }
}
