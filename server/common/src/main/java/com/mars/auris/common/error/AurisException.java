package com.mars.auris.common.error;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * @author geyan
 * @date 2026/8/18
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class AurisException extends RuntimeException {

    private Integer httpStatus;

    private Integer code;

    private String msg;

    public AurisException(ErrorCode errorCode) {
        this.httpStatus = errorCode.getHttpStatus();
        this.code = errorCode.getCode();
        this.msg = errorCode.getMsg();
    }

    public AurisException(ErrorCode errorCode, String msg) {
        this.httpStatus = errorCode.getHttpStatus();
        this.code = errorCode.getCode();
        this.msg = msg;
    }
}
