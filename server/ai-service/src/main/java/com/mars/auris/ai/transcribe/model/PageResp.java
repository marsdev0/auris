package com.mars.auris.ai.transcribe.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页响应信封,字段与 MP Page 对齐(P3 §4)。
 * 不加 pages/hasNext——前端按 total 自算,YAGNI
 *
 * @author geyan
 * @date 2026/9/12
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResp<T> {

    /**
     * 总条数
     */
    private Long total;

    /**
     * 当前页(1 起)
     */
    private Long current;

    /**
     * 每页条数
     */
    private Long size;

    private List<T> records;
}
