package com.mars.auris.push.feishu;

import com.mars.auris.push.feishu.model.Card;
import com.mars.auris.push.feishu.model.FeishuMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 飞书群机器人 webhook 客户端(单群形态,URL 来自配置)。
 * <p>
 * 失败语义:未配置/4xx/5xx/网络异常一律抛出——由 AbstractChannelWorker 统一 catch
 * 转 markRetry 退避,attempt 耗尽进 DLQ。"地址配错→退避→死信"正是 §7 的演练路径。
 *
 * @author geyan
 * @date 2026/10/2
 */
@Slf4j
@Component
public class FeishuClient {

    private final RestClient restClient;

    /**
     * 留空 = 渠道未开通:发送即抛,走退避/死信(可控的演练路径)
     */
    private final String webhookUrl;

    public FeishuClient(RestClient restClient,
                        @Value("${auris.push.feishu.webhook-url:}") String webhookUrl) {
        this.restClient = restClient;
        this.webhookUrl = webhookUrl;
    }

    /**
     * 发交互卡片,成功返回 true;任何失败抛异常(调用方不 catch,交给 handle 统一转退避)
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
