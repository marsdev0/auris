package com.mars.auris.push.feishu.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Feishu webhook message body (msg_type decides between content and card)
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
     * Message type: text | interactive (card)
     */
    @JsonProperty("msg_type")
    private String msgType;

    /**
     * Text content (used when msg_type=text)
     */
    private Content content;

    /**
     * Card content (used when msg_type=interactive)
     */
    private Card card;
}
