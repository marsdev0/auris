package com.mars.auris.common.error;

/**
 * 服务位 - 服务
 * 0 - 通用common
 * 1 - ai-service
 * 2 - user-service
 * 3- push-service
 * 4 - gateway-service
 *
 * @author geyan
 * @date 2026/8/18
 */
public interface ErrorCode {

    Integer getHttpStatus();

    Integer getCode();

    String getMsg();
}
