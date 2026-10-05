package com.project.payflo.merchant_service.service.impl;

import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.merchant_service.entity.Customer;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.repository.CustomerRepository;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import com.project.payflo.merchant_service.service.CustomerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customerRepository;
    private final MerchantRepository merchantRepository;

    // Deliberately not @Transactional: a unique-index violation aborts a PostgreSQL transaction, so the
    // read that follows it has to run in a new one. Each repository call is its own short transaction.
    @Override
    public UUID findOrCreate(UUID merchantId, String email, String name, String phone) {

        if (email == null || email.isBlank()) {
            return null;
        }
        // "A@x.com" and "a@x.com" are the same customer.
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        return customerRepository.findByMerchant_IdAndEmail(merchantId, normalizedEmail)
                .map(Customer::getId)
                .orElseGet(() -> createNew(merchantId, normalizedEmail, name, phone));
    }


    private UUID createNew(UUID merchantId, String email, String name, String phone) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", merchantId));

        Customer customer = Customer.builder()
                .merchant(merchant)
                .email(email)
                .name(name)
                .phone(phone)
                .build();

        try {
            customer = customerRepository.save(customer);
        } catch (DataIntegrityViolationException lostTheRace) {
            // A simultaneous request created this customer between our read and our insert: use theirs.
            return customerRepository.findByMerchant_IdAndEmail(merchantId, email)
                    .map(Customer::getId)
                    .orElseThrow(() -> lostTheRace);
        }
        log.info("Customer created via findOrCreate id={} merchantId={}", customer.getId(), merchantId);
        return customer.getId();
    }

}
