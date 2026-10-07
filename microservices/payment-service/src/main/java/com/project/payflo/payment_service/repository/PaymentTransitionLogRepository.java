package com.project.payflo.payment_service.repository;

import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.payment_service.entity.PaymentTransitionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentTransitionLogRepository extends JpaRepository<PaymentTransitionLog, UUID> {

    long countByPayment_IdAndEvent(UUID paymentId, PaymentEvent event);
}
