package com.project.payflo.payment_service.timeout;

import com.project.payflo.common_lib.enums.OrderStatus;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.repository.OrderRepository;
import com.project.payflo.payment_service.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Nothing else ends a payment the bank never answers, a payment authorized but never captured, or an
 * order nobody paid. Without this they sit in AUTHORIZING / AUTHORIZED / CREATED forever, and a payment
 * stuck in AUTHORIZING also blocks the order from being paid again.
 *
 * <p>Orders are only looked at within a lookback window, so a long history of old unpaid orders isn't
 * swept (and announced to webhooks) all at once.
 */
@Slf4j
@Component
public class PaymentTimeoutSweeper {

    private static final int ORDER_BATCH = 200;
    // Keep one run well inside the ShedLock lease (lockAtMostFor).
    private static final long MAX_RUN_MILLIS = 60_000;

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentTimeoutService timeoutService;
    private final int authorizingMinutes;
    private final int authorizedMinutes;
    private final int orderExpiryLookbackDays;

    public PaymentTimeoutSweeper(PaymentRepository paymentRepository,
                                 OrderRepository orderRepository,
                                 PaymentTimeoutService timeoutService,
                                 @Value("${payment.timeout.authorizing-minutes:15}") int authorizingMinutes,
                                 @Value("${payment.timeout.authorized-minutes:60}") int authorizedMinutes,
                                 @Value("${payment.timeout.order-expiry-lookback-days:7}") int orderExpiryLookbackDays) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.timeoutService = timeoutService;
        this.authorizingMinutes = authorizingMinutes;
        this.authorizedMinutes = authorizedMinutes;
        this.orderExpiryLookbackDays = orderExpiryLookbackDays;
    }

    @Scheduled(fixedDelayString = "${payment.timeout.sweep-interval-ms:60000}")
    @SchedulerLock(name = "payment-service-timeout-sweeper", lockAtMostFor = "5m", lockAtLeastFor = "10s")
    public void sweep() {
        long deadline = System.currentTimeMillis() + MAX_RUN_MILLIS;
        LocalDateTime now = LocalDateTime.now();

        int authorizing = sweepPayments(PaymentStatus.AUTHORIZING, now.minusMinutes(authorizingMinutes),
                timeoutService::timeOutAuthorizing, deadline);
        int authorized = sweepPayments(PaymentStatus.AUTHORIZED, now.minusMinutes(authorizedMinutes),
                timeoutService::timeOutAuthorized, deadline);
        int orders = sweepOrders(now, deadline);

        if (authorizing + authorized + orders > 0) {
            log.info("Timeout sweep: {} authorizing payments failed, {} authorizations lapsed, {} orders expired",
                    authorizing, authorized, orders);
        }
    }

    private int sweepPayments(PaymentStatus status, LocalDateTime createdBefore,
                              BiPredicate<UUID, LocalDateTime> timeOut, long deadline) {
        int done = 0;
        List<Payment> batch;
        do {
            batch = paymentRepository.findTop200ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(status, createdBefore);
            int doneInBatch = 0;
            for (Payment payment : batch) {
                try {
                    if (timeOut.test(payment.getId(), createdBefore)) doneInBatch++;
                } catch (Exception e) {
                    log.error("Could not time out payment {}", payment.getId(), e);
                }
            }
            done += doneInBatch;
            // A full batch of payments that all changed under us would re-read the same rows; stop instead.
            if (doneInBatch == 0) break;
        } while (batch.size() == 200 && System.currentTimeMillis() < deadline);
        return done;
    }

    private int sweepOrders(LocalDateTime now, long deadline) {
        LocalDateTime since = now.minusDays(orderExpiryLookbackDays);
        int done = 0;
        List<UUID> ids;
        do {
            ids = orderRepository.findExpiredOrderIds(List.of(OrderStatus.CREATED, OrderStatus.ATTEMPTED),
                    now, since, PageRequest.of(0, ORDER_BATCH));
            int doneInBatch = 0;
            for (UUID id : ids) {
                try {
                    if (timeoutService.expireOrder(id)) doneInBatch++;
                } catch (Exception e) {
                    log.error("Could not expire order {}", id, e);
                }
            }
            done += doneInBatch;
            // Orders with a payment still in flight are skipped, so a batch of only those would repeat.
            if (doneInBatch == 0) break;
        } while (ids.size() == ORDER_BATCH && System.currentTimeMillis() < deadline);
        return done;
    }
}
