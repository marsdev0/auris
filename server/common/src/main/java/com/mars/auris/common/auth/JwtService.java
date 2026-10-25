package com.mars.auris.common.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * @author geyan
 * @date 2026/9/6
 */
@Slf4j
public class JwtService {


    private final JwtProperties jwtProperties;

    public JwtService(@Autowired JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    public String generateAccessToken(Long userId, String username) {
        Date now = new Date();
        Date expire = new Date(now.getTime() + jwtProperties.getAccessTokenExpire() * 1000);

        return Jwts.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(String.valueOf(userId))
                .claim("userId", userId)
                .claim("username", username)
                .claim("type", "access")
                .claim("jti", UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expire)
                .signWith(getKey(false))
                .compact();
    }

    public String generateRefreshToken(Long userId) {
        Date now = new Date();
        Date expire = new Date(now.getTime() + jwtProperties.getRefreshTokenExpire() * 1000);

        return Jwts.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(String.valueOf(userId))
                .claim("userId", userId)
                .claim("type", "refresh")
                .claim("jti", UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expire)
                .signWith(getKey(true))
                .compact();

    }

    public Claims parseToken(String token, boolean isRefresh) {
        try {
            return Jwts.parser()
                    .requireIssuer(jwtProperties.getIssuer())
                    .verifyWith(getKey(isRefresh))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            log.error("Parse token failed", e);
        }
        return null;
    }

    /**
     * Validate and parse the accessToken; returns null on invalid/expired/type mismatch.
     * Secret selection, issuer, type=access, subject extraction — all here; callers must not hand-roll claims
     */
    public UserPrincipal parseAccessToken(String token) {
        Claims claims = parseToken(token, false);
        if (claims == null || !"access".equals(claims.get("type"))) {
            return null;
        }
        // The subject is a string at signing time; parseLong is stable.
        // claims.get("userId", Long.class) yields Integer for small values and throws RequiredTypeException
        return new UserPrincipal(Long.parseLong(claims.getSubject()),
                claims.get("username", String.class));
    }


    private SecretKey getKey(boolean isRefresh) {
        String secret = isRefresh
                ? jwtProperties.getRefreshTokenSecret()
                : jwtProperties.getAccessTokenSecret();
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

}
