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
import com.project.payflo.payment.gateway.dto.PaymentResult;
import com.project.payflo.payment.mapper.PaymentMapper;
import com.project.payflo.payment.repository.OrderRepository;
import com.project.payflo.payment.repository.PaymentRepository;
import com.project.payflo.payment.service.PaymentService;
import com.project.payflo.payment.statemachine.PaymentTransitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGatewayRouter paymentGatewayRouter;
    private final PaymentMapper paymentMapper;
    private final PaymentTransitionService paymentTransitionService;

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

        paymentTransitionService.apply(payment, PaymentEvent.AUTHORIZE_ATTEMPT);

        PaymentResult result = paymentGatewayRouter.initiate(paymentRequest);

        switch (result) {
            case null -> log.warn("Payment adapter for method {} returned no result (not yet implemented)", request.method());
            case PaymentResult.Pending pending -> payment.setProcessorReference(pending.registrationRef());
            case PaymentResult.Failure failure -> {
                paymentTransitionService.apply(payment, PaymentEvent.AUTHORIZE_FAIL);
                payment.setErrorCode(failure.errorCode());
                payment.setErrorDescription(failure.errorDescription());
            }
            case PaymentResult.Success success -> {
                log.warn("Invalid state");
                return null;
            }
        }

        payment = paymentRepository.save(payment);
        orderRepository.save(order);

        return paymentMapper.toResponse(payment);
    }

    @Override
    @Transactional
    public PaymentResponse capture(UUID merchantId, UUID paymentId) {

        Payment payment = paymentRepository.findByIdAndMerchantIdForUpdate(paymentId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_REQUEST);

        PaymentResult paymentResult = paymentGatewayRouter.capture(payment.getMethod(), paymentId);

        switch (paymentResult) {
            case PaymentResult.Success success -> {
                paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_SUCCESS);
                payment.setCapturedAt(LocalDateTime.now());
                log.info("Payment captured, paymentID: {}", paymentId);
            }
            case PaymentResult.Failure failure -> {
                paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_FAIL);
                payment.setErrorCode(failure.errorCode());
                payment.setErrorDescription(failure.errorDescription());
                log.warn("Payment capture failed, paymentID: {}", paymentId);
            }
            case PaymentResult.Pending pending -> {
                paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_PENDING);
                payment.setProcessorReference(pending.registrationRef());
                log.warn("Payment capture still pending, paymentID: {}", paymentId);
            }
            case null -> {
                payment.setStatus(PaymentStatus.AUTHORIZED);
                log.warn("Payment adapter for method {} returned no capture result (not yet implemented), paymentID: {}",
                        payment.getMethod(), paymentId);
            }
        }

        payment = paymentRepository.save(payment);

        return paymentMapper.toResponse(payment);
    }

    @Override
    @Transactional
    public void resolveAuthorization(UUID paymentId, boolean approve,
                                     String bankRef, String errorCode, String errorDescription) {

        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        if (payment.getStatus() != PaymentStatus.AUTHORIZING) {
            log.warn("Payment is not in Authorizing state, paymentID: {}, status: {}", paymentId, payment.getStatus());
            return;
        }

        OrderRecord orderRecord = payment.getOrder();

        if (approve) {
            paymentTransitionService.apply(payment, PaymentEvent.AUTHORIZE_SUCCESS);
            payment.setBankReference(bankRef);
            payment.setAuthorizedAt(LocalDateTime.now());

            // Auto-capture
            paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_REQUEST);
            PaymentResult captureResult = paymentGatewayRouter.capture(payment.getMethod(), paymentId);

            switch (captureResult) {
                case PaymentResult.Success success -> {
                    paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_SUCCESS);
                    payment.setCapturedAt(LocalDateTime.now());
                    orderRecord.setOrderStatus(OrderStatus.PAID);
                }
                case PaymentResult.Failure failure -> {
                    paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_FAIL);
                    payment.setErrorCode(failure.errorCode());
                    payment.setErrorDescription(failure.errorDescription());
                }
                case PaymentResult.Pending pending -> {
                    paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_PENDING);
                    payment.setProcessorReference(pending.registrationRef());
                }
                case null -> payment.setStatus(PaymentStatus.AUTHORIZED);
            }
        } else {
            paymentTransitionService.apply(payment, PaymentEvent.AUTHORIZE_FAIL);
            payment.setErrorCode(errorCode);
            payment.setErrorDescription(errorDescription);
        }

        paymentRepository.save(payment);
        orderRepository.save(orderRecord);

    }
}











