package com.project.payflo.payment.service.impl;

import com.project.payflo.common.enums.*;
import com.project.payflo.common.exception.ConflictException;
import com.project.payflo.common.exception.ResourceNotFoundException;
import com.project.payflo.payment.dto.request.PaymentInitRequest;
import com.project.payflo.payment.dto.response.PaymentResponse;
import com.project.payflo.payment.entity.OrderRecord;
import com.project.payflo.payment.entity.Payment;
import com.project.payflo.payment.gateway.PaymentGatewayRouter;
import com.project.payflo.payment.gateway.dto.PaymentRequest;
import com.project.payflo.payment.mapper.PaymentMapper;
import com.project.payflo.payment.repository.OrderRepository;
import com.project.payflo.payment.repository.PaymentRepository;
import com.project.payflo.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGatewayRouter paymentGatewayRouter;
    private final PaymentMapper paymentMapper;

    @Override
    @Transactional
    public PaymentResponse initiate(UUID merchantId, PaymentInitRequest request) {
        OrderRecord order = orderRepository.findByIdAndMerchantIdForUpdate(request.orderId(), merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", request.orderId()));

        if(order.getOrderStatus() != OrderStatus.CREATED && order.getOrderStatus() != OrderStatus.ATTEMPTED) {
            throw new ConflictException("ORDER_NOT_PAYABLE",
                    "Order cannot accept payment in status: "+order.getOrderStatus());
        }

        order.setOrderStatus(OrderStatus.ATTEMPTED);
        order.setAttempts(order.getAttempts()+1);

        Payment payment = Payment.builder()
                .order(order)
                .merchantId(merchantId)
                .amount(order.getAmount())
                .status(PaymentStatus.CREATED)
                .method(request.method())
                .idempotencyKey(UUID.randomUUID().toString()) //TODO: idempotency
                .methodDetails(request.methodDetails())
                .build();
        payment = paymentRepository.save(payment);

        PaymentRequest paymentRequest = new PaymentRequest(payment.getId(),
                request.orderId(), merchantId,
                order.getAmount(), request.method(),
                request.methodDetails());

        // TODO: use the PaymentResult to update payment/order status once adapters are implemented
        paymentGatewayRouter.initiate(paymentRequest);

        return paymentMapper.toResponse(payment);
    }
}











