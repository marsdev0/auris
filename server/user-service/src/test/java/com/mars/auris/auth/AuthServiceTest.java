package com.mars.auris.auth;

import com.mars.auris.auth.model.LoginReq;
import com.mars.auris.auth.model.LoginResp;
import com.mars.auris.auth.model.RegisterReq;
import com.mars.auris.auth.service.AuthService;
import com.mars.auris.auth.store.RefreshTokenStore;
import com.mars.auris.common.error.AurisException;
import com.mars.auris.user.entity.UserDO;
import com.mars.auris.user.error.UserErrorCode;
import com.mars.auris.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册查重/登录比对/防枚举(用户不存在与密码错误同码)/封禁拦截
 *
 * @author geyan
 * @date 2026/9/7
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private com.mars.auris.common.auth.JwtService jwtService;
    @Mock
    private RefreshTokenStore refreshTokenStore;

    /** 强度降到4, BCrypt快两个数量级, 单测够用且不影响matches语义 */
    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    @InjectMocks
    private AuthService authService;

    // ---------- register ----------

    @Test
    void 注册_用户名重复_抛USERNAME_ALREADY_EXISTS() {
        when(userMapper.insert(any(UserDO.class))).thenThrow(new DuplicateKeyException("uk_username"));

        AurisException e = assertThrows(AurisException.class,
                () -> authService.register(req("bob", "secret123")));

        assertEquals(UserErrorCode.USERNAME_ALREADY_EXISTS.getCode(), e.getCode());
    }

    @Test
    void 注册_密码BCrypt加密后落库() {
        authService.register(req("bob", "secret123"));

        ArgumentCaptor<UserDO> captor = ArgumentCaptor.forClass(UserDO.class);
        verify(userMapper).insert(captor.capture());
        UserDO saved = captor.getValue();

        assertNotEquals("secret123", saved.getPassword());
        assertTrue(passwordEncoder.matches("secret123", saved.getPassword()));
        assertEquals("bob", saved.getNickname()); // 昵称为空时默认用户名
    }

    // ---------- login ----------

    @Test
    void 登录_用户不存在_返回LOGIN_FAILED() {
        when(userMapper.selectOne(any())).thenReturn(null);

        AurisException e = assertThrows(AurisException.class,
                () -> authService.login(login("ghost", "whatever")));

        assertEquals(UserErrorCode.LOGIN_FAILED.getCode(), e.getCode());
    }

    @Test
    void 登录_密码错误_与用户不存在同码_防枚举() {
        UserDO bob = user("bob", "secret123", 0);
        when(userMapper.selectOne(any())).thenReturn(bob);

        AurisException e = assertThrows(AurisException.class,
                () -> authService.login(login("bob", "wrong-password")));

        assertEquals(UserErrorCode.LOGIN_FAILED.getCode(), e.getCode());
    }

    @Test
    void 登录_账号被封禁_返回ACCOUNT_DISABLED() {
        UserDO bob = user("bob", "secret123", 1);
        when(userMapper.selectOne(any())).thenReturn(bob);

        AurisException e = assertThrows(AurisException.class,
                () -> authService.login(login("bob", "secret123")));

        assertEquals(UserErrorCode.ACCOUNT_DISABLED.getCode(), e.getCode());
    }

    @Test
    void 登录_成功_签发双token并保存refreshToken() {
        UserDO bob = user("bob", "secret123", 0);
        when(userMapper.selectOne(any())).thenReturn(bob);
        when(jwtService.generateAccessToken(bob.getId(), "bob")).thenReturn("at-token");
        when(jwtService.generateRefreshToken(bob.getId())).thenReturn("rt-token");

        LoginResp resp = authService.login(login("bob", "secret123"));

        assertEquals("at-token", resp.getAccessToken());
        assertEquals("rt-token", resp.getRefreshToken());
        verify(refreshTokenStore).save(bob.getId(), "rt-token");
    }

    // ---------- helpers ----------

    private RegisterReq req(String username, String password) {
        RegisterReq r = new RegisterReq();
        r.setUsername(username);
        r.setPassword(password);
        return r;
    }

    private LoginReq login(String username, String password) {
        LoginReq l = new LoginReq();
        l.setUsername(username);
        l.setPassword(password);
        return l;
    }

    private UserDO user(String username, String rawPassword, int status) {
        UserDO u = new UserDO();
        u.setId(1L);
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setStatus(status);
        return u;
    }
}
