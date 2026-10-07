package com.project.payflo.payment_service.api;


import com.project.payflo.common_lib.dto.PaymentSettlementView;
import com.project.payflo.payment_service.entity.Payment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PaymentLookupService {

    List<PaymentSettlementView> findUnsettledCapturedPayments(UUID merchantId, LocalDateTime capturedBefore, int page, int size);

    void markSettled(List<UUID> paymentList);

}
