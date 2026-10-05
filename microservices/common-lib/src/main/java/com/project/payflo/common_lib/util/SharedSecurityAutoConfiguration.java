package com.project.payflo.common_lib.util;

import com.project.payflo.common_lib.config.AesEncryptionConfig;
import com.project.payflo.common_lib.config.SecretConfigurationChecker;
import com.project.payflo.common_lib.context.MerchantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.web.context.annotation.RequestScope;

@AutoConfiguration
public class SharedSecurityAutoConfiguration {

    @Bean
    public SignerUtil signerUtil() {
        return new SignerUtil();
    }

    // Refuses (or, in development, warns about) the secrets committed to this repository.
    @Bean(initMethod = "check")
    public SecretConfigurationChecker secretConfigurationChecker(Environment environment) {
        return new SecretConfigurationChecker(environment::getProperty,
                environment.getProperty("app.security.enforce-strong-secrets", Boolean.class, false));
    }

    // Secure unless a development setting opts in (see config-repo/application.yaml).
    @Bean
    public WebhookUrlValidator webhookUrlValidator(
            @Value("${webhook.allow-private-targets:false}") boolean allowPrivateTargets) {
        return new WebhookUrlValidator(allowPrivateTargets);
    }

    @Bean
    @ConditionalOnProperty(name = "vault.master-key")
    public BytesEncryptor masterKeyEncryptor(@Value("${vault.master-key}") String masterKey) {
        return new AesEncryptionConfig().masterKeyEncryptor(masterKey);
    }

    @Bean
    @ConditionalOnProperty(name = "webhook.secret-encryption-key")
    public BytesEncryptor webhookSecretEncryptor(@Value("${webhook.secret-encryption-key}") String masterKey) {
        return new AesEncryptionConfig().masterKeyEncryptor(masterKey);
    }

    @Bean
    @RequestScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
    public MerchantContext merchantContext() {
        return new MerchantContext();
    }

}
