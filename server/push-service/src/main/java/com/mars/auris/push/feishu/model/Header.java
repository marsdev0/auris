package com.mars.auris.push.feishu.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 卡片头(标题 + 颜色主题)
 *
 * @author geyan
 * @date 2026/10/2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Header {

    private Title title;

    /**
     * 颜色主题:blue, wathet, turquoise, green, yellow, orange, red, carmine, violet, purple, indigo, grey, grey_black
     */
    private String template;
}
