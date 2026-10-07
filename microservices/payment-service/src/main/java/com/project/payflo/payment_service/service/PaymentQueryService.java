package com.project.payflo.payment_service.service;

import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.payment_service.dto.response.PaymentResponse;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.mapper.PaymentMapper;
import com.project.payflo.payment_service.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Reads of a merchant's payments. Every query is scoped by the merchant, so another merchant's payment is
// "not found", never "forbidden".
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentQueryService {

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;

    public PaymentResponse getById(UUID merchantId, UUID paymentId) {
        return paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .map(paymentMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
    }

    public PageResponse<PaymentResponse> list(UUID merchantId, PaymentStatus status, UUID orderId, int page, int size) {
        Pageable pageable = PageRequest.of(PageResponse.clampPage(page), PageResponse.clampSize(size));

        Slice<Payment> payments;
        if (orderId != null && status != null) {
            payments = paymentRepository.findByMerchantIdAndOrder_IdAndStatusOrderByCreatedAtDesc(merchantId, orderId, status, pageable);
        } else if (orderId != null) {
            payments = paymentRepository.findByMerchantIdAndOrder_IdOrderByCreatedAtDesc(merchantId, orderId, pageable);
        } else if (status != null) {
            payments = paymentRepository.findByMerchantIdAndStatusOrderByCreatedAtDesc(merchantId, status, pageable);
        } else {
            payments = paymentRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
        }
        return PageResponse.of(payments, paymentMapper::toResponse);
    }
}
