package com.project.payflo.vault_service.service.impl;

import com.project.payflo.common_lib.dto.PaymentProcessorRequest;
import com.project.payflo.common_lib.dto.PaymentProcessorResponse;
import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.CardBrand;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.common_lib.util.RandomizerUtil;
import com.project.payflo.vault_service.config.VaultEncryptionConfig;
import com.project.payflo.vault_service.dto.request.TokenizeRequest;
import com.project.payflo.vault_service.dto.response.TokenizeResponse;
import com.project.payflo.vault_service.entity.CardToken;
import com.project.payflo.vault_service.entity.VaultCard;
import com.project.payflo.vault_service.processor.CardPaymentProcessor;
import com.project.payflo.vault_service.repository.CardTokenRepository;
import com.project.payflo.vault_service.repository.VaultCardRepository;
import com.project.payflo.vault_service.service.VaultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class VaultServiceImpl implements VaultService {

    private final CardTokenRepository cardTokenRepository;
    private final VaultCardRepository vaultCardRepository;
    private final BytesEncryptor dekEncrypter;
    private final CardPaymentProcessor cardPaymentProcessor;

    @Override
    @Transactional
    public TokenizeResponse tokenize(TokenizeRequest request, UUID merchantId) {

        String lastFour = request.pan().substring(request.pan().length() - 4);
        String bin = request.pan().substring(0, 6);
        CardBrand cardBrand = detectBrand(request.pan());

        byte[] dek = KeyGenerators.secureRandom(32).generateKey();
        byte[] encryptedPan = VaultEncryptionConfig.panEncrypter(dek)
                .encrypt(request.pan().getBytes(StandardCharsets.UTF_8));
        byte[] encryptedDek = dekEncrypter.encrypt(dek);

        VaultCard vaultCard = vaultCardRepository.save(VaultCard.builder()
                .brand(cardBrand)
                .expiryYear(request.expiryYear().toString())
                .expiryMonth(request.expiryMonth().toString())
                .bin(bin)
                .lastFour(lastFour)
                .encryptedDek(encryptedDek)
                .encryptedPan(encryptedPan)
                .cardHolderName(request.cardHolderName())
                .build());

        String token = "tok_" + RandomizerUtil.randomBase64(32);

        cardTokenRepository.save(CardToken.builder()
                .vaultCard(vaultCard)
                .token(token)
                .customer(request.customerId())
                .merchant(merchantId)
                .build());

        return new TokenizeResponse(token, lastFour, cardBrand, request.expiryMonth(), request.expiryYear());
    }

    @Override
    @Transactional
    public PaymentProcessorResponse charge(UUID paymentId, UUID merchantId, String token,
                                           Money amount, Map<String, Object> methodDetails) {
        // Scoped by merchant: another merchant's token is indistinguishable from an unknown one.
        // A missing merchant id matches nothing (the column is NOT NULL), so it is refused too.
        CardToken cardToken = cardTokenRepository.findByTokenAndMerchantAndRevokedAtIsNull(token, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("CardToken", token.substring(0, Math.min(4, token.length())) + "****"));

        VaultCard vaultCard = cardToken.getVaultCard();
        byte[] panBytes = null;

        try {
            byte[] dek = dekEncrypter.decrypt(vaultCard.getEncryptedDek());
            panBytes = VaultEncryptionConfig.panEncrypter(dek).decrypt(vaultCard.getEncryptedPan());

            String pan = new String(panBytes, StandardCharsets.UTF_8);
            String expiry = vaultCard.getExpiryMonth() + "/" + vaultCard.getExpiryYear();

            PaymentProcessorRequest paymentProcessorRequest = PaymentProcessorRequest
                    .card(paymentId, pan, expiry, amount, methodDetails);

            PaymentProcessorResponse response = cardPaymentProcessor.charge(paymentProcessorRequest)
                    .get(5, TimeUnit.SECONDS);

            log.info("Vault charge registered, token={}****", token.substring(0, 4));

            return response;
        } catch (Exception e) {
            // The cause is logged, not returned: the description travels on to the merchant.
            log.warn("Vault charge failed, token={}****", token.substring(0, 4), e);
            return new PaymentProcessorResponse.Failure("VAULT_CHARGE_FAILED", "The card charge could not be completed");
        } finally {
            if (panBytes != null) Arrays.fill(panBytes, (byte) 0);
        }
    }

    private CardBrand detectBrand(String pan) {
        if (pan.startsWith("4")) return CardBrand.VISA;
        if (pan.startsWith("5") || pan.startsWith("2")) return CardBrand.MASTERCARD;
        if (pan.startsWith("37") || pan.startsWith("34")) return CardBrand.AMEX;
        return CardBrand.RUPAY;
    }
}



























