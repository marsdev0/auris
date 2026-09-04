package com.mars.auris.ai.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author geyan
 * @date 2026/9/2
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EngineResp<T> {

    private Integer code;

    private String message;

    private T data;
}
