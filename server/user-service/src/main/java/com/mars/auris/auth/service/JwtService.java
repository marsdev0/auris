package com.mars.auris.auth.service;

import com.mars.auris.auth.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * @author geyan
 * @date 2026/9/6
 */
@Service
@Slf4j
public class JwtService {

    @Autowired
    private JwtProperties jwtProperties;

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


    private SecretKey getKey(boolean isRefresh) {
        String secret = isRefresh
                ? jwtProperties.getRefreshTokenSecret()
                : jwtProperties.getAccessTokenSecret();
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

}
