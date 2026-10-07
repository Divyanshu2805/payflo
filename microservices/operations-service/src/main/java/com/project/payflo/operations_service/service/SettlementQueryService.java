package com.project.payflo.operations_service.service;

import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.enums.SettlementStatus;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.operations_service.dto.SettlementResponse;
import com.project.payflo.operations_service.entity.Settlement;
import com.project.payflo.operations_service.entity.SettlementPayment;
import com.project.payflo.operations_service.entity.SettlementPaymentId;
import com.project.payflo.operations_service.repository.SettlementPaymentRepository;
import com.project.payflo.operations_service.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// A merchant's payouts. Every read is scoped by the merchant, so another merchant's settlement is "not found".
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementQueryService {

    private final SettlementRepository settlementRepository;
    private final SettlementPaymentRepository settlementPaymentRepository;

    public PageResponse<SettlementResponse> list(UUID merchantId, SettlementStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(PageResponse.clampPage(page), PageResponse.clampSize(size));
        Slice<Settlement> settlements = status == null
                ? settlementRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
                : settlementRepository.findByMerchantIdAndStatusOrderByCreatedAtDesc(merchantId, status, pageable);
        return PageResponse.of(settlements, SettlementResponse::from);
    }

    public SettlementResponse get(UUID merchantId, UUID settlementId) {
        return SettlementResponse.from(require(merchantId, settlementId));
    }

    /** The ids of the payments this payout covers. */
    public List<UUID> paymentIds(UUID merchantId, UUID settlementId) {
        Settlement settlement = require(merchantId, settlementId);
        return settlementPaymentRepository.findBySettlement(settlement).stream()
                .map(SettlementPayment::getId)
                .map(SettlementPaymentId::getPaymentId)
                .toList();
    }

    private Settlement require(UUID merchantId, UUID settlementId) {
        return settlementRepository.findByIdAndMerchantId(settlementId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Settlement", settlementId));
    }
}
