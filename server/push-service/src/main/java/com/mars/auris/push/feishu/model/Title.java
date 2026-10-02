package com.mars.auris.push.feishu.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 卡片标题(tag 固定 plain_text)
 *
 * @author geyan
 * @date 2026/10/2
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Title {

    private String tag;

    private String content;
}
