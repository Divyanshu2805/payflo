package com.project.payflo.payment.config;

import com.project.payflo.common.enums.PaymentMethod;
import com.project.payflo.payment.processor.PaymentProcessor;
import com.project.payflo.payment.processor.strategy.UpiPaymentProcessor;
import com.project.payflo.payment.processor.strategy.NetBankingPaymentProcessor;
import com.project.payflo.payment.processor.strategy.CardPaymentProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class PaymentProcessorConfig {

    private final CardPaymentProcessor cardPaymentProcessor;
    private final NetBankingPaymentProcessor netBankingPaymentProcessor;
    private final UpiPaymentProcessor upiPaymentProcessor;

    @Bean
    public Map<PaymentMethod, PaymentProcessor> paymentProcessorMap() {
        return Map.of(
                PaymentMethod.CARD, cardPaymentProcessor,
                PaymentMethod.NETBANKING, netBankingPaymentProcessor,
                PaymentMethod.UPI, upiPaymentProcessor
        );
    }
}
