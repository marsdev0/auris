package com.mars.auris.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mars.auris.auth.model.LoginReq;
import com.mars.auris.auth.model.LoginResp;
import com.mars.auris.auth.model.RefreshTokenReq;
import com.mars.auris.auth.model.RegisterReq;
import com.mars.auris.auth.model.TokenResp;
import com.mars.auris.common.auth.JwtService;
import com.mars.auris.common.error.AurisException;
import com.mars.auris.user.entity.UserDO;
import com.mars.auris.user.error.UserErrorCode;
import com.mars.auris.user.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import com.mars.auris.auth.store.RefreshTokenStore;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * @author geyan
 * @date 2026/9/6
 */
@Slf4j
@Service
public class AuthService {

    private static final String PREFIX_AVATAR_URL = "https://api.dicebear.com/9.x/pixel-art/svg?seed=";

    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private RefreshTokenStore refreshTokenStore;

    /**
     * 不能先查询，再插入，有并发问题
     */
    public void register(RegisterReq req) {
        UserDO userDO = createUser(req);
        try {
            userMapper.insert(userDO);
        } catch (DuplicateKeyException e) {
            throw new AurisException(UserErrorCode.USERNAME_ALREADY_EXISTS);
        }
    }

    private UserDO createUser(RegisterReq req) {
        UserDO user = new UserDO();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickname(StringUtils.isEmpty(req.getNickname()) ? req.getUsername() : req.getNickname());
        user.setAvatarUrl(PREFIX_AVATAR_URL + req.getUsername());
        return user;
    }

    /**
     * 登录
     * 1. 按 username 查用户
     * 2. 校验密码
     * 3. 生成 accessToken + refreshToken
     * 4. 保存 refreshToken(供后续轮换/撤销)
     */
    public LoginResp login(LoginReq req) {
        LambdaQueryWrapper<UserDO> query = new LambdaQueryWrapper<>();
        query.eq(UserDO::getUsername, req.getUsername());
        UserDO userDO = userMapper.selectOne(query);
        if (userDO == null) {
            log.warn("登录失败, 用户不存在, username: {}", req.getUsername());
            throw new AurisException(UserErrorCode.LOGIN_FAILED);
        }
        if (!passwordEncoder.matches(req.getPassword(), userDO.getPassword())) {
            log.warn("登录失败, 密码错误, username: {}", req.getUsername());
            throw new AurisException(UserErrorCode.LOGIN_FAILED);
        }
        if (!userDO.enable()) {
            throw new AurisException(UserErrorCode.ACCOUNT_DISABLED);
        }
        String accessToken = jwtService.generateAccessToken(userDO.getId(), userDO.getUsername());
        String refreshToken = jwtService.generateRefreshToken(userDO.getId());
        refreshTokenStore.save(userDO.getId(), refreshToken);
        return createLoginResp(userDO, accessToken, refreshToken);
    }

    private LoginResp createLoginResp(UserDO userDO, String accessToken, String refreshToken) {
        return LoginResp.builder()
                .username(userDO.getUsername())
                .nickname(userDO.getNickname())
                .avatarUrl(userDO.getAvatarUrl())
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    public void logout(Long userId) {
        refreshTokenStore.delete(userId);
        // TODO 优化
        // 用户 logout 后，access token 在过期前依然能通过 JwtAuthFilter 的校验。目前是 JWT 无状态的常见做法（没法主动让一个 JWT 失效），但如果你对安全性要求高，可以考虑：
        //
        //  - 轻量方案：在 Redis 维护一个黑名单（logout 时把 access token 的剩余 TTL 存进去），JwtAuthFilter 中加一次黑名单检查
        //  - 当前方案够用吗：如果 access token 有效期很短（比如 15-30 分钟），风险很低，可以暂不处理
    }

    /**
     * 刷新 token
     * 1. 解析 refreshToken，拿到userId
     * 2. 从缓存中取出，对比
     * 3. 签发新的 accessToken + refreshToken
     * 4. 缓存新的 refreshToken
     */
    public TokenResp refresh(RefreshTokenReq req) {
        Claims claims = jwtService.parseToken(req.getRefreshToken(), true);
        if (claims == null) {
            throw new AurisException(UserErrorCode.REFRESH_TOKEN_INVALID);
        }
        String type = claims.get("type", String.class);
        if (!"refresh".equals(type)) {
            throw new AurisException(UserErrorCode.REFRESH_TOKEN_INVALID);
        }
        Long userId = Long.parseLong(claims.getSubject());
        String stored = refreshTokenStore.get(userId);
        if (StringUtils.isEmpty(stored) || !stored.equals(req.getRefreshToken())) {
            throw new AurisException(UserErrorCode.REFRESH_TOKEN_INVALID);
        }
        UserDO userDO = userMapper.selectById(userId);
        if (userDO == null) {
            throw new AurisException(UserErrorCode.USER_NOT_FOUND);
        }
        if (!userDO.enable()) {
            throw new AurisException(UserErrorCode.ACCOUNT_DISABLED);
        }
        String newRefreshToken = jwtService.generateRefreshToken(userId);
        String newAccessToken = jwtService.generateAccessToken(userId, userDO.getUsername());
        refreshTokenStore.save(userId, newRefreshToken);
        return new TokenResp(newAccessToken, newRefreshToken);
    }
}
