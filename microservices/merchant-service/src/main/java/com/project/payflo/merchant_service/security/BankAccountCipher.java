package com.project.payflo.merchant_service.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encrypts a merchant's payout account number at rest (AES-GCM, the same key as webhook secrets), so a
 * database dump doesn't hand over where the money goes. A stored value carries a prefix; one without it
 * is read as plain text, which keeps rows written before this existed readable.
 */
@Component
@RequiredArgsConstructor
public class BankAccountCipher {

    private static final String PREFIX = "enc1:";

    private final BytesEncryptor bytesEncryptor;

    public String encrypt(String accountNumber) {
        return PREFIX + Base64.getEncoder().encodeToString(
                bytesEncryptor.encrypt(accountNumber.getBytes(StandardCharsets.UTF_8)));
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        return new String(bytesEncryptor.decrypt(Base64.getDecoder().decode(stored.substring(PREFIX.length()))),
                StandardCharsets.UTF_8);
    }

    /** "XXXXXXXX9012": all but the last four digits hidden. */
    public static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return value;
        }
        return "X".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }
}
