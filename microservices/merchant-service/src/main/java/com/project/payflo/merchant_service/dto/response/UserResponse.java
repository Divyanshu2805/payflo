package com.project.payflo.merchant_service.dto.response;

import com.project.payflo.common_lib.enums.UserRole;
import com.project.payflo.merchant_service.entity.AppUser;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserResponse(UUID id, String email, UserRole role, LocalDateTime createdAt) {

    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
