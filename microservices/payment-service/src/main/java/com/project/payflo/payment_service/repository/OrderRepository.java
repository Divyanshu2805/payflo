package com.project.payflo.payment_service.repository;

import com.project.payflo.common_lib.enums.OrderStatus;
import com.project.payflo.payment_service.entity.OrderRecord;
import jakarta.persistence.LockModeType;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<OrderRecord, UUID> {
    boolean existsByMerchantIdAndReceipt(UUID merchantId, @Size(max = 100) String receipt);

    Optional<OrderRecord> findByIdAndMerchantId(UUID orderId, UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderRecord o where o.id = :id")
    Optional<OrderRecord> findByIdForUpdate(UUID id);

    // Ids only, oldest first, within a lookback window: the sweeper then locks and re-checks each order.
    @Query("select o.id from OrderRecord o where o.orderStatus in :statuses "
            + "and o.expiresAt < :now and o.expiresAt > :since order by o.expiresAt asc")
    List<UUID> findExpiredOrderIds(Collection<OrderStatus> statuses, LocalDateTime now, LocalDateTime since,
                                   Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderRecord o where o.id = :uuid and o.merchantId = :merchantId")
    Optional<OrderRecord> findByIdAndMerchantIdForUpdate(UUID uuid, UUID merchantId);
}
