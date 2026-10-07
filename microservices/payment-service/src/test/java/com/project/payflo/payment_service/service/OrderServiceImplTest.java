package com.project.payflo.payment_service.service;

import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.OrderStatus;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.payment_service.client.CustomerServiceClient;
import com.project.payflo.payment_service.dto.response.OrderResponse;
import com.project.payflo.payment_service.entity.OrderRecord;
import com.project.payflo.payment_service.mapper.OrderMapper;
import com.project.payflo.payment_service.mapper.PaymentMapper;
import com.project.payflo.payment_service.outbox.OutboxEventPublisher;
import com.project.payflo.payment_service.repository.OrderRepository;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.service.impl.OrderPersistenceService;
import com.project.payflo.payment_service.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceImplTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OutboxEventPublisher events = mock(OutboxEventPublisher.class);

    private final OrderServiceImpl service = new OrderServiceImpl(orderRepository, paymentRepository,
            mock(PaymentMapper.class), orderMapper, mock(CustomerServiceClient.class), events,
            mock(OrderPersistenceService.class));

    private final UUID merchantId = UUID.randomUUID();
    private OrderRecord order;

    @BeforeEach
    void anUnpaidOrder() {
        order = OrderRecord.builder().id(UUID.randomUUID()).merchantId(merchantId).amount(Money.inr(1000))
                .orderStatus(OrderStatus.CREATED).expiresAt(LocalDateTime.now().plusMinutes(30)).build();
        when(orderRepository.findByIdAndMerchantIdForUpdate(order.getId(), merchantId)).thenReturn(Optional.of(order));
        when(orderRepository.findByIdAndMerchantId(order.getId(), merchantId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(OrderRecord.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void assertCannotCancel() {
        OrderStatus before = order.getOrderStatus();
        assertThatThrownBy(() -> service.cancel(merchantId, order.getId()))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("ORDER_CANNOT_CANCEL"));
        assertThat(order.getOrderStatus()).isEqualTo(before); // unchanged
        verify(events, never()).publish(any(), any(), any(), anyMap());
    }

    // ---- cancel

    @Test
    void anUnpaidOrderWithNoPaymentInProgressCanBeCancelled() {
        when(paymentRepository.existsByOrder_IdAndStatusIn(eq(order.getId()), anyCollection())).thenReturn(false);

        service.cancel(merchantId, order.getId());

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(events).publish(any(), eq(order.getId()), eq("ORDER_CANCELLED"), anyMap());
    }

    @Test
    void anAttemptedOrderWhosePaymentFailedCanStillBeCancelled() {
        order.setOrderStatus(OrderStatus.ATTEMPTED);

        service.cancel(merchantId, order.getId());

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void anOrderWithAPaymentInProgressCannotBeCancelled() {
        order.setOrderStatus(OrderStatus.ATTEMPTED);
        when(paymentRepository.existsByOrder_IdAndStatusIn(eq(order.getId()), anyCollection())).thenReturn(true);

        assertCannotCancel();
    }

    @Test
    void paidCancelledAndExpiredOrdersCannotBeCancelled() {
        for (OrderStatus status : new OrderStatus[]{OrderStatus.PAID, OrderStatus.CANCELLED, OrderStatus.EXPIRED}) {
            order.setOrderStatus(status);
            assertCannotCancel();
        }
    }

    @Test
    void anotherMerchantsOrderIsNotFound() {
        assertThatThrownBy(() -> service.cancel(UUID.randomUUID(), order.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- listing

    @Test
    void aMerchantsPaymentsForAnOrderAreReadScopedByTheMerchant() {
        when(paymentRepository.findByOrder_IdAndMerchantIdOrderByCreatedAtAsc(order.getId(), merchantId)).thenReturn(List.of());

        service.listPayments(merchantId, order.getId());

        verify(paymentRepository).findByOrder_IdAndMerchantIdOrderByCreatedAtAsc(order.getId(), merchantId);
    }

    @Test
    void listingFiltersByStatusWhenGivenAndNewestFirst() {
        when(orderRepository.findByMerchantIdAndOrderStatusOrderByCreatedAtDesc(eq(merchantId), eq(OrderStatus.PAID), any()))
                .thenReturn(new SliceImpl<>(List.of(order)));
        when(orderMapper.toResponse(order)).thenReturn(mock(OrderResponse.class));

        PageResponse<OrderResponse> page = service.list(merchantId, OrderStatus.PAID, 0, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.hasNext()).isFalse();
        verify(orderRepository, never()).findByMerchantIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void thePageSizeAndNumberAreCappedSoNobodyAsksForEverything() {
        when(orderRepository.findByMerchantIdOrderByCreatedAtDesc(eq(merchantId), any())).thenReturn(new SliceImpl<>(List.of()));

        service.list(merchantId, null, 999_999, 5_000);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findByMerchantIdOrderByCreatedAtDesc(eq(merchantId), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(PageResponse.MAX_SIZE);
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(PageResponse.MAX_PAGE);
    }

    @Test
    void aNegativePageStartsAtTheFirst() {
        when(orderRepository.findByMerchantIdOrderByCreatedAtDesc(eq(merchantId), any())).thenReturn(new SliceImpl<>(List.of()));

        service.list(merchantId, null, -3, 0);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findByMerchantIdOrderByCreatedAtDesc(eq(merchantId), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
    }
}
