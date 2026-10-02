package com.mars.auris.push.feishu.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 卡片元素:markdown | hr | note(note 的子元素复用本类)
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
     * 元素类型:markdown | hr | note
     */
    private String tag;

    /**
     * markdown 文本(tag=markdown 时使用)
     */
    private String content;

    /**
     * note 子元素(tag=note 时使用)
     */
    private List<Element> elements;
}
