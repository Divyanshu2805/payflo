package com.project.payflo.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security is only on the classpath for {@code spring-security-crypto} (card encryption in
 * {@code vault/config/VaultEncryptionConfig}) — without this bean, Spring Boot's default
 * autoconfiguration would lock every endpoint behind HTTP Basic with a randomly-generated
 * per-restart password. No real authentication (API key/JWT) exists yet, so every request is
 * permitted; replace this once auth is built.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
