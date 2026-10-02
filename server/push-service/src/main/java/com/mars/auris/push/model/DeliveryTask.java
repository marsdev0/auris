package com.mars.auris.push.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author geyan
 * @date 2026/9/16
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryTask {

    @JsonProperty("delivery_id")
    private String deliveryId;

    @JsonProperty("notice_id")
    private String noticeId;

    private String channel;

    private int attempt;
}
