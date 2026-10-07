package com.project.payflo.api_gateway_service.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Component
public class JwtVerifier {

    @Value("${jwt.secret-key}")
    private String secretKey;

    private SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }

    public Claims verify(String accessToken) {
        return Jwts.parser()
                .verifyWith(getSecretKey())
                .build()
                .parseSignedClaims(accessToken)
                .getPayload();
    }

    public String extractMerchantId(Claims claims) {
        return claims.get("merchant_id", String.class);
    }

    public String extractRole(Claims claims) {
        return claims.get("role", String.class);
    }

    /** The user the token was issued to (the JWT subject). */
    public String extractEmail(Claims claims) {
        return claims.getSubject();
    }

    /** The token's own id, used to revoke it on logout. Null for tokens issued before ids existed. */
    public String extractTokenId(Claims claims) {
        return claims.getId();
    }

    /** When the token was issued, in epoch milliseconds (0 if it carries no issue time). */
    public long extractIssuedAtMillis(Claims claims) {
        return claims.getIssuedAt() != null ? claims.getIssuedAt().getTime() : 0L;
    }
}
