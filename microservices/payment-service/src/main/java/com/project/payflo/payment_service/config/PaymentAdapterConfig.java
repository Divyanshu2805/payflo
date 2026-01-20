package com.project.payflo.payment_service.config;

import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.payment_service.gateway.PaymentAdapter;
import com.project.payflo.payment_service.gateway.adapter.CardPaymentAdapter;
import com.project.payflo.payment_service.gateway.adapter.NetBankingAdapter;
import com.project.payflo.payment_service.gateway.adapter.UpiPaymentAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@RequiredArgsConstructor
@Configuration
public class PaymentAdapterConfig {

    private final NetBankingAdapter netBankingAdapter;
    private final CardPaymentAdapter cardPaymentAdapter;
    private final UpiPaymentAdapter upiPaymentAdapter;

    @Bean
    public Map<PaymentMethod, PaymentAdapter> paymentAdapterMap() {
        return Map.of(
                PaymentMethod.CARD, cardPaymentAdapter,
                PaymentMethod.NETBANKING, netBankingAdapter,
                PaymentMethod.UPI, upiPaymentAdapter
        );
    }
}
