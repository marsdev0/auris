package com.mars.auris.push.feishu.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 飞书 webhook 消息体(msg_type 决定 content/card 二选一)
 *
 * @author geyan
 * @date 2026/10/2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeishuMessage {

    /**
     * 消息类型:text | interactive(卡片)
     */
    @JsonProperty("msg_type")
    private String msgType;

    /**
     * 文本内容(msg_type=text 时使用)
     */
    private Content content;

    /**
     * 卡片内容(msg_type=interactive 时使用)
     */
    private Card card;
}
