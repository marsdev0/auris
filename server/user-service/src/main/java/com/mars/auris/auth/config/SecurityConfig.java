// Copyright (c) 2026 marsdev0
// Licensed under the MIT License. See the LICENSE file for details.
package com.mars.auris.auth.config;

import com.mars.auris.auth.filter.JwtAuthFilter;
import com.mars.auris.common.auth.AuthConfiguration;
import com.mars.auris.common.auth.RestAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

/**
 * @author geyan
 * @date 2026/9/6
 */
@Configuration
@Import(AuthConfiguration.class)
public class SecurityConfig {

    @Autowired
    private JwtAuthFilter jwtAuthFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)           // 前后端分离，不需要 CSRF
                .sessionManagement(sm -> sm.sessionCreationPolicy(STATELESS))   // 无状态，不用 Session
                .securityContext(sc -> sc.requireExplicitSave(false))   // SecurityContext 自动传播到异步线程（SseEmitter）
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/v1/auth/register",
                                    "/v1/auth/login",
                                    "/v1/auth/refresh",
                                    "/actuator/**").permitAll()  // 认证接口放行
                            .anyRequest().authenticated();  // 其余需要认证
                })
                // 账号密码认证之后执行jwtAuthFilter
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new RestAuthenticationEntryPoint()));

        return http.build();
    }
}
