package com.project.payflo.merchant_service.repository;

import com.project.payflo.merchant_service.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByEmail(String email);

    List<AppUser> findByMerchant_IdOrderByCreatedAtAsc(UUID merchantId);

    Optional<AppUser> findByIdAndMerchant_Id(UUID id, UUID merchantId);
}
