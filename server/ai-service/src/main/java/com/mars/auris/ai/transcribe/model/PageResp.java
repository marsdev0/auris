package com.mars.auris.ai.transcribe.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Pagination response envelope, fields aligned with MP Page (P3 §4).
 * No pages/hasNext — the frontend computes from total, YAGNI
 *
 * @author geyan
 * @date 2026/9/12
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResp<T> {

    /**
     * Total count
     */
    private Long total;

    /**
     * Current page (1-based)
     */
    private Long current;

    /**
     * Page size
     */
    private Long size;

    private List<T> records;
}
