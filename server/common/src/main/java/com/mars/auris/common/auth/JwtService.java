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
     * 校验并解析 accessToken;无效/过期/类型不符返回 null。
     * secret 选择、issuer、type=access、subject 取数全在这,调用方不得自己手搓 claims
     */
    public UserPrincipal parseAccessToken(String token) {
        Claims claims = parseToken(token, false);
        if (claims == null || !"access".equals(claims.get("type"))) {
            return null;
        }
        // subject 是签发时的字符串,parseLong 稳定;
        // claims.get("userId", Long.class) 在小数值时拿到 Integer 会抛 RequiredTypeException
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
