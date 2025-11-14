package com.project.payflo.vault.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.encrypt.AesBytesEncryptor;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.keygen.KeyGenerators;

import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;

@Configuration
public class VaultEncryptionConfig {

    public static BytesEncryptor panEncrypter(byte[] dek) {
        SecretKeySpec decKey = new SecretKeySpec(dek, "AES");
        return new AesBytesEncryptor(decKey, KeyGenerators.secureRandom(12),
                AesBytesEncryptor.CipherAlgorithm.GCM);
    }

    @Bean
    public BytesEncryptor dekEncrypter(@Value("${vault.encryption.master-key}") String masterKeyBase64) {
        byte[] masterKey = Base64.getDecoder().decode(masterKeyBase64);
        SecretKeySpec keySpec = new SecretKeySpec(masterKey, "AES");
        return new AesBytesEncryptor(keySpec, KeyGenerators.secureRandom(12),
                AesBytesEncryptor.CipherAlgorithm.GCM);
    }

}
