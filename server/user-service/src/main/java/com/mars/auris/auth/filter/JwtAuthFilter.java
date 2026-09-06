package com.mars.auris.auth.filter;

import com.mars.auris.auth.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * @author geyan
 * @date 2026/9/6
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Autowired
    private JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            Claims claims = jwtService.parseToken(token, false);
            if (claims != null && "access".equals(claims.get("type"))) {
                Long userId = claims.get("userId", Long.class);
                String username = claims.get("username", String.class);

                // 构造 Spring Security 认证对象
                var userDetails = new CustomUserDetails(userId, username);
                var authToken = new UsernamePasswordAuthenticationToken(
                        userDetails,  // principal —— 身份，"你是谁"
                        null,  // credentials —— 凭证，"你的密码"。JWT 场景下不需要，因为 token 已经验证过了
                        userDetails.getAuthorities());  // authorities —— 权限，"你能做什么"。目前返回空列表，后续加角色/权限时扩展

                // 给认证对象附加请求级别的细节（远程 IP、Session ID 等）。
                // Spring Security 内部的一些组件会读取这些信息，比如日志审计、登录 IP 记录。这一行属于标准写法，不加也不影响认证流程。
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }
        filterChain.doFilter(request, response);
    }
}
