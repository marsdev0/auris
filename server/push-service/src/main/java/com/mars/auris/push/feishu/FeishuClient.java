package com.mars.auris.push.feishu;

import com.mars.auris.push.feishu.model.Card;
import com.mars.auris.push.feishu.model.FeishuMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Feishu group-bot webhook client (single-group setup, URL from config).
 * <p>
 * Failure semantics: unconfigured/4xx/5xx/network exceptions all throw — AbstractChannelWorker uniformly catches
 * and converts to markRetry backoff; attempt exhaustion goes to DLQ. "Wrong URL → backoff → dead letter" is exactly §7's drill path.
 *
 * @author geyan
 * @date 2026/10/2
 */
@Slf4j
@Component
public class FeishuClient {

    private final RestClient restClient;

    /**
     * Empty = channel not configured: send throws immediately, goes to backoff/DLQ (a controllable drill path)
     */
    private final String webhookUrl;

    public FeishuClient(RestClient restClient,
                        @Value("${auris.push.feishu.webhook-url:}") String webhookUrl) {
        this.restClient = restClient;
        this.webhookUrl = webhookUrl;
    }

    /**
     * Send an interactive card, returns true on success; any failure throws (callers don't catch; handle converts to backoff uniformly)
     */
    public boolean sendCard(Card card) {
        if (!StringUtils.hasText(webhookUrl)) {
            throw new IllegalStateException("飞书 webhook 未配置(auris.push.feishu.webhook-url)");
        }
        FeishuMessage msg = FeishuMessage.builder()
                .msgType("interactive")
                .card(card)
                .build();
        restClient.post()
                .uri(webhookUrl)
                .body(msg)
                .retrieve()
                .toBodilessEntity();
        return true;
    }
}
