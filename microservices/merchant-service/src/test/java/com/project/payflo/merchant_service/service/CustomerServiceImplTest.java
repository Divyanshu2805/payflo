package com.project.payflo.merchant_service.service;

import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.merchant_service.entity.Customer;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.repository.CustomerRepository;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import com.project.payflo.merchant_service.service.impl.CustomerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerServiceImplTest {

    private final CustomerRepository customerRepository = mock(CustomerRepository.class);
    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final CustomerServiceImpl service = new CustomerServiceImpl(customerRepository, merchantRepository);

    private final UUID merchantId = UUID.randomUUID();
    private Customer existing;

    @BeforeEach
    void aMerchantWithOneCustomer() {
        Merchant merchant = Merchant.builder().id(merchantId).build();
        existing = Customer.builder().id(UUID.randomUUID()).merchant(merchant).email("a@example.com").build();
        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(merchant));
        when(customerRepository.findByMerchant_IdAndEmail(merchantId, "a@example.com")).thenReturn(Optional.of(existing));
    }

    @Test
    void anExistingCustomerIsFoundNotDuplicated() {
        assertThat(service.findOrCreate(merchantId, "a@example.com", "A", null)).isEqualTo(existing.getId());
        verify(customerRepository, never()).save(any());
    }

    @Test
    void emailCaseAndSpacesDoNotMakeANewCustomer() {
        assertThat(service.findOrCreate(merchantId, "  A@Example.COM ", "A", null)).isEqualTo(existing.getId());
        verify(customerRepository, never()).save(any());
    }

    @Test
    void aNewCustomerIsStoredWithTheNormalizedEmail() {
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> {
            Customer c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        UUID id = service.findOrCreate(merchantId, "New@Example.com", "New", "999");

        ArgumentCaptor<Customer> saved = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("new@example.com");
        assertThat(id).isNotNull();
    }

    @Test
    void losingARaceReturnsTheCustomerTheOtherRequestCreated() {
        UUID winner = UUID.randomUUID();
        Customer theirs = Customer.builder().id(winner).email("race@example.com").build();
        // not there when we look, there once our insert hits the unique index
        when(customerRepository.findByMerchant_IdAndEmail(merchantId, "race@example.com"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(theirs));
        when(customerRepository.save(any(Customer.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThat(service.findOrCreate(merchantId, "race@example.com", "R", null)).isEqualTo(winner);
        verify(customerRepository, times(2)).findByMerchant_IdAndEmail(merchantId, "race@example.com");
    }

    @Test
    void aViolationThatIsNotARaceIsNotHidden() {
        when(customerRepository.findByMerchant_IdAndEmail(merchantId, "x@example.com")).thenReturn(Optional.empty());
        when(customerRepository.save(any(Customer.class))).thenThrow(new DataIntegrityViolationException("value too long"));

        assertThatThrownBy(() -> service.findOrCreate(merchantId, "x@example.com", "X", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void noEmailMeansNoCustomer() {
        assertThat(service.findOrCreate(merchantId, null, "A", null)).isNull();
        assertThat(service.findOrCreate(merchantId, "  ", "A", null)).isNull();
    }

    @Test
    void anUnknownMerchantIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(customerRepository.findByMerchant_IdAndEmail(unknown, "a@example.com")).thenReturn(Optional.empty());
        when(merchantRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findOrCreate(unknown, "a@example.com", "A", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
