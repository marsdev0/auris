package com.mars.auris.push.feishu;

import com.mars.auris.push.entity.NoticeDO;
import com.mars.auris.push.feishu.model.Card;
import com.mars.auris.push.feishu.model.Element;
import com.mars.auris.push.feishu.model.Header;
import com.mars.auris.push.feishu.model.Title;

import java.util.ArrayList;
import java.util.List;

/**
 * NoticeDO → Feishu card: header colored by type (green=complete/red=fail/orange=timeout/blue=other),
 * body markdown + a bottom note with type and time.
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
     * Color is semantics: tell good news from bad at a glance
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
