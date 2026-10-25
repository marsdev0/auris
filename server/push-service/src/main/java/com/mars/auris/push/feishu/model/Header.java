package com.mars.auris.push.feishu.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Card header (title + color theme)
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
     * Color theme: blue, wathet, turquoise, green, yellow, orange, red, carmine, violet, purple, indigo, grey, grey_black
     */
    private String template;
}
