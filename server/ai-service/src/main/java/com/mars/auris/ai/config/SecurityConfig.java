package com.mars.auris.ai.config;

import com.mars.auris.ai.filter.JwtAuthFilter;
import com.mars.auris.common.auth.AuthConfiguration;
import com.mars.auris.common.auth.RestAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
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
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)           // 前后端分离，不需要 CSRF
                .sessionManagement(sm -> sm.sessionCreationPolicy(STATELESS))   // 无状态，不用 Session
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // 账号密码认证之后执行jwtAuthFilter
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new RestAuthenticationEntryPoint()));

        return http.build();
    }
}
