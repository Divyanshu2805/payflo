package com.project.payflo.operations_service.client;

import com.project.payflo.common_lib.dto.PaymentSettlementView;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@FeignClient(name = "payment-service", path = "/internal/payments", url = "${PAYMENT_SERVICE_URI:}")
public interface PaymentServiceClient {

    @GetMapping("/unsettled-captured")
    List<PaymentSettlementView> findUnsettledCaptured(@RequestParam UUID merchantId,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime capturedBefore,
                                                      @RequestParam int page,
                                                      @RequestParam int size);

    @PostMapping("/mark-settled")
    void markSettled(@RequestBody List<UUID> paymentIds);

}
