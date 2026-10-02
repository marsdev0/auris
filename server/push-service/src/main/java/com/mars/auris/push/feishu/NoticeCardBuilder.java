package com.mars.auris.push.feishu;

import com.mars.auris.push.entity.NoticeDO;
import com.mars.auris.push.feishu.model.Card;
import com.mars.auris.push.feishu.model.Element;
import com.mars.auris.push.feishu.model.Header;
import com.mars.auris.push.feishu.model.Title;

import java.util.ArrayList;
import java.util.List;

/**
 * NoticeDO → 飞书卡片:header 按 type 着色(完成绿/失败红/超时橙/其他蓝),
 * 正文 markdown + 底部 note 标类型与时间。
 *
 * @author geyan
 * @date 2026/10/2
 */
public final class NoticeCardBuilder {

    private static final String TAG_MARKDOWN = "markdown";
    private static final String TAG_NOTE = "note";
    private static final String TAG_PLAIN_TEXT = "plain_text";
    private static final String DEFAULT_TEMPLATE = "blue";

    private NoticeCardBuilder() {
    }

    public static Card build(NoticeDO notice) {
        return Card.builder()
                .header(Header.builder()
                        .title(new Title(TAG_PLAIN_TEXT, notice.getTitle()))
                        .template(templateOf(notice.getType()))
                        .build())
                .elements(elements(notice))
                .build();
    }

    /**
     * 颜色即语义:一眼分清好坏消息
     */
    private static String templateOf(String type) {
        return switch (type == null ? "" : type) {
            case "transcribe.completed" -> "green";
            case "transcribe.failed" -> "red";
            case "transcribe.timeout" -> "orange";
            default -> DEFAULT_TEMPLATE;
        };
    }

    private static List<Element> elements(NoticeDO notice) {
        List<Element> elements = new ArrayList<>();
        elements.add(Element.builder()
                .tag(TAG_MARKDOWN)
                .content(notice.getContent())
                .build());
        elements.add(Element.builder()
                .tag(TAG_NOTE)
                .elements(List.of(Element.builder()
                        .tag(TAG_PLAIN_TEXT)
                        .content("auris-push · " + notice.getType() + " · " + notice.getCreatedAt())
                        .build()))
                .build());
        return elements;
    }
}
