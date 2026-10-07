package com.project.payflo.operations_service.repository;

import com.project.payflo.common_lib.enums.SettlementStatus;
import com.project.payflo.operations_service.entity.Settlement;
import com.project.payflo.operations_service.entity.SettlementPayment;
import com.project.payflo.operations_service.entity.SettlementPaymentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface SettlementPaymentRepository extends JpaRepository<SettlementPayment, SettlementPaymentId> {
    List<SettlementPayment> findBySettlement(Settlement settlement);

    // The payments already part of one of this merchant's payouts that is still under way: in one of the given
    // states, or paid out but not yet reported to payment-service (which still shows its payments as unsettled).
    @Query("select sp.id.paymentId from SettlementPayment sp " +
            "where sp.settlement.merchantId = :merchantId and (sp.settlement.status in :statuses " +
            "or (sp.settlement.status = com.project.payflo.common_lib.enums.SettlementStatus.PROCESSED " +
            "and sp.settlement.paymentsSettledAt is null))")
    Set<UUID> findPaymentIdsInSettlements(UUID merchantId, Collection<SettlementStatus> statuses);
}
