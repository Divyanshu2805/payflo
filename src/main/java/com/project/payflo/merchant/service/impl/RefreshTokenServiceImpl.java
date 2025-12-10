package com.project.payflo.merchant.service.impl;

import com.project.payflo.common.exception.InvalidRefreshTokenException;
import com.project.payflo.common.util.HashUtil;
import com.project.payflo.common.util.RandomizerUtil;
import com.project.payflo.merchant.entity.AppUser;
import com.project.payflo.merchant.entity.RefreshToken;
import com.project.payflo.merchant.repository.RefreshTokenRepository;
import com.project.payflo.merchant.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private static final int TOKEN_BYTE_LENGTH = 40;

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.refresh-token-expiry-days}")
    private long refreshTokenExpiryDays;

    @Override
    @Transactional
    public String issue(AppUser appUser) {
        String rawToken = RandomizerUtil.randomBase64(TOKEN_BYTE_LENGTH);

        RefreshToken refreshToken = RefreshToken.builder()
                .appUser(appUser)
                .tokenHash(HashUtil.sha256Hex(rawToken))
                .expiresAt(LocalDateTime.now().plusDays(refreshTokenExpiryDays))
                .build();

        refreshTokenRepository.save(refreshToken);
        return rawToken;
    }

    @Override
    @Transactional
    public AppUser rotate(String rawToken) {
        RefreshToken existing = refreshTokenRepository.findByTokenHash(HashUtil.sha256Hex(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException(
                        "INVALID_REFRESH_TOKEN", "Refresh token not recognized"));

        if (existing.isRevoked()) {
            log.warn("Refresh token reuse detected for appUser {}", existing.getAppUser().getId());
            throw new InvalidRefreshTokenException("INVALID_REFRESH_TOKEN", "Refresh token already used");
        }

        if (existing.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidRefreshTokenException("INVALID_REFRESH_TOKEN", "Refresh token expired");
        }

        existing.setRevoked(true);
        existing.setRevokedAt(LocalDateTime.now());

        return existing.getAppUser();
    }

    @Override
    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(HashUtil.sha256Hex(rawToken))
                .ifPresent(token -> {
                    token.setRevoked(true);
                    token.setRevokedAt(LocalDateTime.now());
                });
    }
}
