package com.mars.auris.common.error;

import com.mars.auris.common.rsp.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * @author geyan
 * @date 2026/8/18
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {


    @ExceptionHandler(AurisException.class)
    public ResponseEntity<ApiResponse<Void>> handleAurisException(AurisException e) {
        return ResponseEntity
                .status(e.getHttpStatus())
                .body(ApiResponse.error(e.getCode(), e.getMsg()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException0(Exception e) {
        log.error("handleException0", e);
        return ResponseEntity
                .status(CommonErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(ApiResponse.error(CommonErrorCode.INTERNAL_ERROR));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleException1(MaxUploadSizeExceededException e) {
        log.error("handleException1 ", e);
        return ResponseEntity
                .status(CommonErrorCode.FILE_TOO_LARGE.getHttpStatus())
                .body(ApiResponse.error(CommonErrorCode.FILE_TOO_LARGE));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleException2(MethodArgumentNotValidException e) {
        log.error("handleException2 ", e);
        return ResponseEntity
                .status(CommonErrorCode.BAD_REQUEST.getHttpStatus())
                .body(ApiResponse.error(CommonErrorCode.BAD_REQUEST));
    }
}
