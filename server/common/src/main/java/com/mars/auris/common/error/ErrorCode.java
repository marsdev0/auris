package com.mars.auris.common.error;

/**
 * Service slot - service
 * 0 - common
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
