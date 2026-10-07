package com.project.payflo.payment_service.service;

import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.payment_service.dto.response.PaymentResponse;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.mapper.PaymentMapper;
import com.project.payflo.payment_service.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.SliceImpl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentQueryServiceTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentMapper paymentMapper = mock(PaymentMapper.class);
    private final PaymentQueryService service = new PaymentQueryService(paymentRepository, paymentMapper);

    private final UUID merchantId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private Payment payment;

    @BeforeEach
    void aPayment() {
        payment = Payment.builder().id(UUID.randomUUID()).merchantId(merchantId).amount(Money.inr(1000))
                .method(PaymentMethod.UPI).status(PaymentStatus.CAPTURED).build();
        when(paymentMapper.toResponse(payment)).thenReturn(mock(PaymentResponse.class));
    }

    @Test
    void aPaymentIsReadScopedByTheMerchant() {
        when(paymentRepository.findByIdAndMerchantId(payment.getId(), merchantId)).thenReturn(Optional.of(payment));

        assertThat(service.getById(merchantId, payment.getId())).isNotNull();
        assertThatThrownBy(() -> service.getById(UUID.randomUUID(), payment.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void withNoFilterItListsEverythingNewestFirst() {
        when(paymentRepository.findByMerchantIdOrderByCreatedAtDesc(eq(merchantId), any()))
                .thenReturn(new SliceImpl<>(List.of(payment)));

        assertThat(service.list(merchantId, null, null, 0, 20).items()).hasSize(1);
    }

    @Test
    void theStatusAndOrderFiltersPickTheMatchingQuery() {
        when(paymentRepository.findByMerchantIdAndStatusOrderByCreatedAtDesc(eq(merchantId), eq(PaymentStatus.CAPTURED), any()))
                .thenReturn(new SliceImpl<>(List.of(payment)));
        when(paymentRepository.findByMerchantIdAndOrder_IdOrderByCreatedAtDesc(eq(merchantId), eq(orderId), any()))
                .thenReturn(new SliceImpl<>(List.of(payment)));
        when(paymentRepository.findByMerchantIdAndOrder_IdAndStatusOrderByCreatedAtDesc(eq(merchantId), eq(orderId), eq(PaymentStatus.CAPTURED), any()))
                .thenReturn(new SliceImpl<>(List.of(payment)));

        assertThat(service.list(merchantId, PaymentStatus.CAPTURED, null, 0, 20).items()).hasSize(1);
        assertThat(service.list(merchantId, null, orderId, 0, 20).items()).hasSize(1);
        assertThat(service.list(merchantId, PaymentStatus.CAPTURED, orderId, 0, 20).items()).hasSize(1);
        verify(paymentRepository, never()).findByMerchantIdOrderByCreatedAtDesc(any(), any());
    }
}
