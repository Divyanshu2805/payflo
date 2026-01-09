package com.project.payflo.merchant_service.repository;

import com.project.payflo.merchant_service.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    Optional<Customer> findByMerchant_IdAndEmail(UUID merchantId, String email);
}
