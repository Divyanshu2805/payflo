package com.project.payflo.payment.config;

import com.project.payflo.common.enums.PaymentMethod;
import com.project.payflo.payment.gateway.PaymentAdapter;
import com.project.payflo.payment.gateway.adapter.CardPaymentAdapter;
import com.project.payflo.payment.gateway.adapter.NetBankingAdapter;
import com.project.payflo.payment.gateway.adapter.UpiPaymentAdapter;
import com.project.payflo.payment.gateway.adapter.WalletPaymentAdapter;
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
    private final WalletPaymentAdapter walletPaymentAdapter;

    @Bean
    public Map<PaymentMethod, PaymentAdapter> paymentAdapterMap() {
        return Map.of(
                PaymentMethod.CARD, cardPaymentAdapter,
                PaymentMethod.NETBANKING, netBankingAdapter,
                PaymentMethod.UPI, upiPaymentAdapter,
                PaymentMethod.WALLET, walletPaymentAdapter
        );
    }
}
