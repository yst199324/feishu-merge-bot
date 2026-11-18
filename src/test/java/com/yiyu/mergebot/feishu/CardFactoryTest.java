package com.yiyu.mergebot.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiyu.mergebot.yunxiao.MrView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卡片输出测试。
 *
 * 卡片是审批人唯一看得见的东西，文案和颜色改坏了没人会去查代码，
 * 只会觉得"这机器人怎么怪怪的"。所以把几个关键约定钉在测试里：
 *   - header 必须同时有状态和标题（群列表只显示这一行）；
 *   - 已合并绿、已拒绝灰、失败红；
 *   - 正文第一行是结果，且有完整标题；
 *   - URL 只出现在底部灰字里，不再占正文。
 */
class CardFactoryTest {

    private final CardFactory factory = new CardFactory();
    private final ObjectMapper mapper = new ObjectMapper();

    private MrView mr(String status, String title) throws Exception {
        return MrView.of(mapper.readTree("{\"title\":\"" + title + "\",\"status\":\"" + status + "\","
                + "\"sourceBranch\":\"dev订单\",\"targetBranch\":\"test\",\"authorName\":\"李四\","
                + "\"allRequirementsPass\":true,\"createdAt\":\"2026-11-18T14:32:00+08:00\"}"));
    }

    @Test
    @DisplayName("已合并：头部是「已合并 · 标题」，绿色，正文有完整标题和处理时间")
    void mergedCard() throws Exception {
        String json = factory.resultCard("1998java/source-admin-new", "修复订单导出超时",
                ResultKind.MERGED, "杨书庭（676706）", "已合并进 test",
                mr("MERGED", "修复订单导出超时"), "https://codeup.aliyun.com/x");

        assertTrue(json.contains("已合并 · 修复订单导出超时"), "header 必须带标题，否则群列表看不出是哪条");
        assertTrue(json.contains("\"template\":\"green\""), "已合并用绿色");
        assertTrue(json.contains("<font color='green'>已合并进 test</font>"), "结果行要着色");
        assertTrue(json.contains("**修复订单导出超时**"), "正文要有完整标题");
        assertTrue(json.contains("处理：杨书庭（676706）"), "要有处理人和时间");
        assertTrue(json.contains("云效链接（长按可复制）"), "URL 放底部灰字");
    }

    @Test
    @DisplayName("已拒绝：头部是「已拒绝」，灰色（不是黄色/红色）")
    void closedCard() throws Exception {
        String json = factory.resultCard("proj", "调整首页文案", ResultKind.CLOSED, "张三（123456）",
                "已拒绝（关闭）该合并请求", mr("CLOSED", "调整首页文案"), null);

        assertTrue(json.contains("已拒绝 · 调整首页文案"));
        assertTrue(json.contains("\"template\":\"grey\""), "拒绝是正常决策，用灰色而不是警告色");
        assertTrue(json.contains("<font color='grey'>已拒绝（关闭）该合并请求</font>"));
    }

    @Test
    @DisplayName("失败：头部是「操作失败」，红色")
    void failedCard() throws Exception {
        String json = factory.resultCard("proj", "改动 A", ResultKind.FAILED, "张三（123456）",
                "存在代码冲突，必须由提交人自己解决", mr("TO_BE_MERGED", "改动 A"), null);

        assertTrue(json.contains("操作失败 · 改动 A"));
        assertTrue(json.contains("\"template\":\"red\""));
    }

    @Test
    @DisplayName("标题为空时头部回落到项目名，不会出现「已合并 · 」这种空标题")
    void emptyTitleFallsBackToRepo() throws Exception {
        String json = factory.resultCard("1998java/source-admin-new", "", ResultKind.MERGED, "张三（123456）",
                "已合并进 test", null, null);

        assertTrue(json.contains("已合并 · 1998java/source-admin-new"));
        assertFalse(json.contains("已合并 · \""), "不能出现空的标题段");
    }

    @Test
    @DisplayName("待审批卡：头部带「待审批 ·」，正文不再有明文 URL 行，URL 只在底部灰字")
    void approvalCardUrlMovedToBottom() throws Exception {
        String json = factory.approvalCard("proj", "5425167", "36", mr("TO_BE_MERGED", "修复订单导出超时"),
                Collections.emptyList(), "https://codeup.aliyun.com/x");

        assertTrue(json.contains("待审批 · 修复订单导出超时"));
        assertTrue(json.contains("\"template\":\"blue\""), "待审批用蓝色（待办）");
        assertFalse(json.contains("合并请求：https"), "正文那行明文 URL 已经去掉");
        assertTrue(json.contains("云效链接（长按可复制）：https://codeup.aliyun.com/x"), "底部灰字保留可复制链接");
    }
}
