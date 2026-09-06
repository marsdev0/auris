package com.mars.auris.auth;

import com.mars.auris.common.auth.JwtProperties;
import com.mars.auris.common.auth.JwtService;
import com.mars.auris.common.auth.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * JwtService 四态: 签发/解析/过期/篡改 (+refresh 不能当 access 用)
 *
 * @author geyan
 * @date 2026/9/7
 */
class JwtServiceTest {

    private JwtService jwtService;

    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setAccessTokenSecret("test-access-secret-0123456789abcdef0123456789");
        jwtProperties.setRefreshTokenSecret("test-refresh-secret-0123456789abcdef0123456789");
        jwtProperties.setAccessTokenExpire(7200L);
        jwtProperties.setRefreshTokenExpire(604800L);
        jwtProperties.setIssuer("auris-test");
        jwtService = new JwtService(jwtProperties);
    }

    @Test
    void 签发并解析_accessToken() {
        String token = jwtService.generateAccessToken(2096642151758856193L, "alice");

        UserPrincipal principal = jwtService.parseAccessToken(token);

        assertNotNull(principal);
        assertEquals(2096642151758856193L, principal.userId());
        assertEquals("alice", principal.username());
    }

    @Test
    void 过期_accessToken_返回null() {
        jwtProperties.setAccessTokenExpire(-1L); // 签出来的 token 生成即过期
        String token = jwtService.generateAccessToken(1L, "alice");

        assertNull(jwtService.parseAccessToken(token));
    }

    @Test
    void 篡改签名_返回null() {
        String token = jwtService.generateAccessToken(1L, "alice");
        String tampered = token.substring(0, token.length() - 3) + "xyz";

        assertNull(jwtService.parseAccessToken(tampered));
    }

    @Test
    void refreshToken_不能当accessToken用() {
        // 双 secret: refresh token 用另一把钥匙签发, access 通道验签必然失败
        String refreshToken = jwtService.generateRefreshToken(1L);

        assertNull(jwtService.parseAccessToken(refreshToken));
    }

    @Test
    void 同秒重复签发_jti保证不重样() {
        String t1 = jwtService.generateRefreshToken(1L);
        String t2 = jwtService.generateRefreshToken(1L);

        org.junit.jupiter.api.Assertions.assertNotEquals(t1, t2);
    }
}
