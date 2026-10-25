package com.mars.auris.push.feishu.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Card element: markdown | hr | note (note's child elements reuse this class)
 *
 * @author geyan
 * @date 2026/10/2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Element {

    /**
     * Element type: markdown | hr | note
     */
    private String tag;

    /**
     * Markdown text (used when tag=markdown)
     */
    private String content;

    /**
     * Note child element (used when tag=note)
     */
    private List<Element> elements;
}
