package com.yiyu.mergebot.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yiyu.mergebot.yunxiao.MrView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卡片底部「复制」按钮。
 *
 * 为什么要单独一个测试类：复制这件事很容易被"优化"回去——
 * 有人觉得卡片上有明文链接就够了，就把按钮删掉；或者把两个按钮拆成两行、把复制按钮做得跟主按钮一样大。
 * 这些都直接影响用户能不能把链接捞到电脑上，所以用测试钉死：
 * 按钮存在、带链接、和主按钮同一行、比主按钮窄、明文链接仍保留。
 */
@DisplayName("卡片「复制」按钮")
class CardFactoryCopyButtonTest {

    private static final String URL = "https://codeup.aliyun.com/1998java/source-admin-new/change/128";
    private static final String REPO = "1998java/source-admin-new";

    private final CardFactory factory = new CardFactory();
    private final ObjectMapper mapper = new ObjectMapper();

    private MrView pendingMr() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("title", "修复登录弹窗抖动");
        n.put("sourceBranch", "feature/login");
        n.put("targetBranch", "test");
        n.put("authorName", "张三");
        n.put("status", "TO_BE_MERGED");
        n.put("detailUrl", URL);
        n.put("allRequirementsPass", true);
        n.put("createdAt", "2026-11-20T09:00:00Z");
        return MrView.of(n);
    }

    @Test
    @DisplayName("待审批卡：复制按钮带链接、小一号，且和「在云效打开」在同一行")
    void approvalCardHasCopyButton() throws Exception {
        String json = factory.approvalCard(REPO, "repo1", "128", pendingMr(), null, URL);

        JsonNode copy = findButton(json, "复制");
        assertNotNull(copy, "卡片上应该有「复制」按钮");
        assertEquals("copy", copy.at("/behaviors/0/value/act").asText(), "点了要回调服务端，不是跳转");
        assertEquals(URL, copy.at("/behaviors/0/value/url").asText(), "复制按钮必须带上这串链接");
        assertEquals("small", copy.path("size").asText(), "复制按钮要比主按钮小一号");

        JsonNode row = findRow(json, "复制", "在云效打开");
        assertNotNull(row, "两个按钮必须在同一行（同一个 column_set）里");

        // 复制按钮那一栏权重更小 = 更窄。写反了它就比主按钮还宽
        assertEquals(3, row.at("/columns/0/weight").asInt(0), "主按钮占 3 份宽度");
        assertEquals(1, row.at("/columns/1/weight").asInt(0), "复制按钮占 1 份宽度");
        assertEquals("weighted", row.at("/columns/1/width").asText(""),
                "width 必须是 weighted，否则 weight 不生效");

        // 明文链接照旧留着：不点按钮也能看到完整地址
        assertTrue(json.contains("云效链接（点一下在浏览器打开）："), "底部灰字不能因为加了按钮就删掉");
    }

    @Test
    @DisplayName("结果卡也带复制按钮")
    void resultCardHasCopyButton() throws Exception {
        String json = factory.resultCard(REPO, "修复登录弹窗抖动", ResultKind.MERGED, "李四（abc123）",
                "已合并进 test", pendingMr(), URL, "");
        JsonNode copy = findButton(json, "复制");
        assertNotNull(copy, "结果卡上也能复制链接");
        assertEquals(URL, copy.at("/behaviors/0/value/url").asText());
        assertNotNull(findRow(json, "复制", "去云效查看"), "结果卡的两个按钮同样要在一行");
    }

    @Test
    @DisplayName("没有链接时不出按钮，也不出空的复制动作")
    void noUrlNoCopyButton() throws Exception {
        String json = factory.approvalCard(REPO, "repo1", "128", pendingMr(), null, "");
        // detailUrl 还有值，所以这里仍有链接；真正没有链接的情况见下一条
        assertNotNull(findButton(json, "复制"));

        ObjectNode bare = JsonNodeFactory.instance.objectNode();
        bare.put("status", "TO_BE_MERGED");
        String noLink = factory.approvalCard(REPO, "repo1", "128", MrView.of(bare), null, "");
        assertNull(findButton(noLink, "复制"), "没有链接就不该出现复制按钮");
        assertFalse(noLink.contains("act\":\"copy"), "也不该留下只有 act 没有 url 的死按钮");
    }

    // ---------------------------------------------------------------- 工具

    /** 卡片里文字为 text 的按钮，逐层找（按钮可能直接放 body.elements，也可能嵌在分栏里） */
    private JsonNode findButton(String json, String text) throws Exception {
        JsonNode root = mapper.readTree(json);
        for (JsonNode el : root.at("/body/elements")) {
            JsonNode hit = matchButton(el, text);
            if (hit != null) {
                return hit;
            }
            for (JsonNode col : el.path("columns")) {
                for (JsonNode inner : col.path("elements")) {
                    JsonNode found = matchButton(inner, text);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    private JsonNode matchButton(JsonNode node, String text) {
        if ("button".equals(node.path("tag").asText())
                && text.equals(node.at("/text/content").asText())) {
            return node;
        }
        return null;
    }

    /** 同时含有这两段文字的 column_set（即"同一行"） */
    private JsonNode findRow(String json, String a, String b) throws Exception {
        JsonNode root = mapper.readTree(json);
        for (JsonNode el : root.at("/body/elements")) {
            if (!"column_set".equals(el.path("tag").asText())) {
                continue;
            }
            String s = el.toString();
            if (s.contains("\"content\":\"" + a + "\"") && s.contains("\"content\":\"" + b + "\"")) {
                return el;
            }
        }
        return null;
    }
}
