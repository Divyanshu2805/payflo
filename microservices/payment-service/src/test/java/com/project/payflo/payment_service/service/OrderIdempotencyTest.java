package com.project.payflo.payment_service.service;

import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.OrderStatus;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.IdempotencyKeyReusedException;
import com.project.payflo.payment_service.client.CustomerServiceClient;
import com.project.payflo.payment_service.dto.request.CreateOrderRequest;
import com.project.payflo.payment_service.dto.response.OrderResponse;
import com.project.payflo.payment_service.entity.OrderRecord;
import com.project.payflo.payment_service.mapper.OrderMapper;
import com.project.payflo.payment_service.mapper.PaymentMapper;
import com.project.payflo.payment_service.outbox.OutboxEventPublisher;
import com.project.payflo.payment_service.repository.OrderRepository;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.service.impl.OrderPersistenceService;
import com.project.payflo.payment_service.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** An order created with an idempotency key: the replay, the refusal of a changed request, and the race. */
class OrderIdempotencyTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderPersistenceService persistence = mock(OrderPersistenceService.class);
    private final OrderPersistenceService realPersistence = new OrderPersistenceService(orderRepository, mock(OutboxEventPublisher.class), orderMapper);
    private final OrderServiceImpl service = new OrderServiceImpl(orderRepository, mock(PaymentRepository.class), mock(PaymentMapper.class),
            orderMapper, mock(CustomerServiceClient.class), mock(OutboxEventPublisher.class), persistence);

    private final UUID merchant = UUID.randomUUID();
    private final CreateOrderRequest request = new CreateOrderRequest(Money.inr(5_000), "rcpt-1", Map.of("ref", "a"), null, null);
    private final OrderResponse response = mock(OrderResponse.class);

    private OrderRecord existing(Money amount, String receipt, Map<String, Object> notes) {
        return OrderRecord.builder().id(UUID.randomUUID()).merchantId(merchant).amount(amount).receipt(receipt).notes(notes)
                .orderStatus(OrderStatus.CREATED).idempotencyKey("key-1").build();
    }

    // ---- replay, in the persistence step

    @Test
    void aRetryOfTheSameRequestFindsTheOrderAlreadyCreated() {
        OrderRecord order = existing(Money.inr(5_000), "rcpt-1", Map.of("ref", "a"));
        when(orderRepository.findByMerchantIdAndIdempotencyKey(merchant, "key-1")).thenReturn(Optional.of(order));
        when(orderMapper.toResponse(order)).thenReturn(response);

        assertThat(realPersistence.findReplay(merchant, request, "key-1")).contains(response);
    }

    @Test
    void aNewKeyFindsNothing() {
        when(orderRepository.findByMerchantIdAndIdempotencyKey(merchant, "key-1")).thenReturn(Optional.empty());

        assertThat(realPersistence.findReplay(merchant, request, "key-1")).isEmpty();
    }

    @Test
    void theSameKeyForAnotherAmountIsRefusedNotReplayed() {
        when(orderRepository.findByMerchantIdAndIdempotencyKey(merchant, "key-1"))
                .thenReturn(Optional.of(existing(Money.inr(9_999), "rcpt-1", Map.of("ref", "a"))));

        assertThatThrownBy(() -> realPersistence.findReplay(merchant, request, "key-1")).isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void theSameKeyForAnotherReceiptOrNotesIsRefused() {
        when(orderRepository.findByMerchantIdAndIdempotencyKey(merchant, "key-1"))
                .thenReturn(Optional.of(existing(Money.inr(5_000), "rcpt-OTHER", Map.of("ref", "a"))));
        assertThatThrownBy(() -> realPersistence.findReplay(merchant, request, "key-1")).isInstanceOf(IdempotencyKeyReusedException.class);

        when(orderRepository.findByMerchantIdAndIdempotencyKey(merchant, "key-1"))
                .thenReturn(Optional.of(existing(Money.inr(5_000), "rcpt-1", Map.of("ref", "different"))));
        assertThatThrownBy(() -> realPersistence.findReplay(merchant, request, "key-1")).isInstanceOf(IdempotencyKeyReusedException.class);
    }

    // ---- the race, in the service

    @Test
    void twoRequestsWithOneKeyAtOnceTheSlowerAnswersWithTheWinnersOrder() {
        when(persistence.persist(eq(merchant), eq(request), any(), anyInt(), eq("key-1"))).thenThrow(new DataIntegrityViolationException("unique"));
        when(persistence.findReplay(merchant, request, "key-1")).thenReturn(Optional.of(response));

        assertThat(service.create(merchant, request, "key-1")).isSameAs(response);
    }

    @Test
    void aUniquenessViolationWithoutAKeyIsStillAnError() {
        when(persistence.persist(eq(merchant), eq(request), any(), anyInt(), eq(null))).thenThrow(new DataIntegrityViolationException("receipt"));

        assertThatThrownBy(() -> service.create(merchant, request, null)).isInstanceOf(DataIntegrityViolationException.class);
        verify(persistence, never()).findReplay(any(), any(), any());
    }

    @Test
    void aViolationThatIsNotTheKeyIsStillAnErrorEvenWithAKey() {
        when(persistence.persist(eq(merchant), eq(request), any(), anyInt(), eq("key-1"))).thenThrow(new DataIntegrityViolationException("receipt"));
        when(persistence.findReplay(merchant, request, "key-1")).thenReturn(Optional.empty()); // no order holds this key

        assertThatThrownBy(() -> service.create(merchant, request, "key-1")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aKeyLongerThanTheColumnIsRefusedBeforeAnythingIsWritten() {
        String tooLong = "k".repeat(101);

        assertThatThrownBy(() -> service.create(merchant, request, tooLong))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.getErrorCode()).isEqualTo("IDEMPOTENCY_KEY_TOO_LONG"));
        verify(persistence, never()).persist(any(), any(), any(), anyInt(), any());
    }
}
