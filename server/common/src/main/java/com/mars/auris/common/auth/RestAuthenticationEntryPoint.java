package com.mars.auris.common.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.auris.common.error.CommonErrorCode;
import com.mars.auris.common.rsp.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * 未认证统一返回 401 + ApiResponse 壳({@code 401_001}), 各服务直接 new 挂载, 无状态无需注册为 bean
 *
 * @author geyan
 * @date 2026/9/7
 */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(CommonErrorCode.UNAUTHORIZED.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        MAPPER.writeValue(response.getWriter(), ApiResponse.error(CommonErrorCode.UNAUTHORIZED));
    }
}
