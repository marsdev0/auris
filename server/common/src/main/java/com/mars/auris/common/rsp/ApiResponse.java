package com.mars.auris.common.rsp;

import com.mars.auris.common.error.CommonErrorCode;
import com.mars.auris.common.error.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author geyan
 * @date 2026/8/27
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private Integer code;

    private String msg;

    private T data;

    public static <T> ApiResponse<T> ok() {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = CommonErrorCode.OK.getCode();
        r.msg = CommonErrorCode.OK.getMsg();
        return r;
    }

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = CommonErrorCode.OK.getCode();
        r.msg = CommonErrorCode.OK.getMsg();
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> error() {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = CommonErrorCode.INTERNAL_ERROR.getCode();
        r.msg = CommonErrorCode.INTERNAL_ERROR.getMsg();
        return r;
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = errorCode.getCode();
        r.msg = errorCode.getMsg();
        return r;
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String errorMsg) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = errorCode.getCode();
        r.msg = errorMsg;
        return r;
    }

    public static <T> ApiResponse<T> error(Integer errorCode, String errorMsg) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = errorCode;
        r.msg = errorMsg;
        return r;
    }

}
