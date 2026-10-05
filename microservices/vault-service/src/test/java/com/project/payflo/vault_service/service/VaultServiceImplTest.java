package com.project.payflo.vault_service.service;

import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.vault_service.processor.CardPaymentProcessor;
import com.project.payflo.vault_service.repository.CardTokenRepository;
import com.project.payflo.vault_service.repository.VaultCardRepository;
import com.project.payflo.vault_service.service.impl.VaultServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.BytesEncryptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VaultServiceImplTest {

    private final CardTokenRepository cardTokenRepository = mock(CardTokenRepository.class);
    private final BytesEncryptor dekEncrypter = mock(BytesEncryptor.class);
    private final CardPaymentProcessor processor = mock(CardPaymentProcessor.class);
    private final VaultServiceImpl service = new VaultServiceImpl(
            cardTokenRepository, mock(VaultCardRepository.class), dekEncrypter, processor);

    private final String token = "tok_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";
    private final UUID owner = UUID.randomUUID();
    private final UUID otherMerchant = UUID.randomUUID();

    @Test
    void aTokenOfAnotherMerchantIsNotFoundAndNothingIsDecrypted() {
        when(cardTokenRepository.findByTokenAndMerchantAndRevokedAtIsNull(token, otherMerchant))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.charge(UUID.randomUUID(), otherMerchant, token, Money.inr(1000), Map.of()))
                .isInstanceOf(ResourceNotFoundException.class);

        // the lookup was scoped to the paying merchant, and no card was decrypted or charged
        verify(cardTokenRepository).findByTokenAndMerchantAndRevokedAtIsNull(token, otherMerchant);
        verify(dekEncrypter, never()).decrypt(org.mockito.ArgumentMatchers.any());
        verify(processor, never()).charge(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aMissingMerchantIdMatchesNothing() {
        when(cardTokenRepository.findByTokenAndMerchantAndRevokedAtIsNull(token, null)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.charge(UUID.randomUUID(), null, token, Money.inr(1000), Map.of()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theNotFoundMessageDoesNotRepeatTheWholeToken() {
        when(cardTokenRepository.findByTokenAndMerchantAndRevokedAtIsNull(token, owner)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.charge(UUID.randomUUID(), owner, token, Money.inr(1000), Map.of()))
                .hasMessageNotContaining(token)
                .hasMessageContaining("tok_");
    }
}
