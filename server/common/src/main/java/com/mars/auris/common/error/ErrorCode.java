package com.mars.auris.common.error;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author geyan
 * @date 2026/8/18
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorCode {

    private Integer code;

    private String msg;
}
