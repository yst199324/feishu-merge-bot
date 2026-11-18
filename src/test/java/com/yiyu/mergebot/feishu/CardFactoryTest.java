package com.yiyu.mergebot.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiyu.mergebot.yunxiao.CommitItem;
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
 *   - 正文第一行是结果，接着是**完整不截断**的标题，再接着是最近提交；
 *   - URL 只出现在底部灰字里，不再占正文。
 */
class CardFactoryTest {

    private final CardFactory factory = new CardFactory();
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String COMMIT = "23a64ce8　验证合并　王涛";

    private MrView mr(String status, String title) throws Exception {
        return MrView.of(mapper.readTree("{\"title\":\"" + title + "\",\"status\":\"" + status + "\","
                + "\"sourceBranch\":\"dev订单\",\"targetBranch\":\"test\",\"authorName\":\"李四\","
                + "\"allRequirementsPass\":true,\"createdAt\":\"2026-11-18T14:32:00+08:00\"}"));
    }

    @Test
    @DisplayName("已合并：头部是「已合并 · 标题」，绿色，正文有完整标题、最近提交和处理时间")
    void mergedCard() throws Exception {
        String json = factory.resultCard("1998java/source-admin-new", "修复订单导出超时",
                ResultKind.MERGED, "杨书庭（676706）", "已合并进 test",
                mr("MERGED", "修复订单导出超时"), "https://codeup.aliyun.com/x", COMMIT);

        assertTrue(json.contains("已合并 · 修复订单导出超时"), "header 必须带标题，否则群列表看不出是哪条");
        assertTrue(json.contains("\"template\":\"green\""), "已合并用绿色");
        assertTrue(json.contains("结果：**<font color='green'>已合并进 test</font>**"), "结果要带标签且着色");
        assertTrue(json.contains("标题：**修复订单导出超时**"), "标题要带标签且完整");
        assertTrue(json.contains("最近提交"), "结果卡要保留最近提交");
        assertTrue(json.contains(COMMIT));
        assertTrue(json.contains("处理：杨书庭（676706）"), "要有处理人和时间");
        assertTrue(json.contains("分支：<font color='blue'>**dev订单**</font> → <font color='green'>**test**</font>"),
                "源分支蓝、目标分支绿，都加粗");
        assertTrue(json.contains("去云效查看"), "跳转按钮不能丢");
        assertTrue(json.contains("云效链接（长按可复制）"), "URL 放底部灰字");
    }

    @Test
    @DisplayName("已拒绝：头部是「已拒绝」，灰色（不是黄色/红色）")
    void closedCard() throws Exception {
        String json = factory.resultCard("proj", "调整首页文案", ResultKind.CLOSED, "张三（123456）",
                "已拒绝（关闭）该合并请求", mr("CLOSED", "调整首页文案"), null, COMMIT);

        assertTrue(json.contains("已拒绝 · 调整首页文案"));
        assertTrue(json.contains("\"template\":\"grey\""), "拒绝是正常决策，用灰色而不是警告色");
        assertTrue(json.contains("<font color='grey'>已拒绝（关闭）该合并请求</font>"));
    }

    @Test
    @DisplayName("失败：头部是「操作失败」，红色")
    void failedCard() throws Exception {
        String json = factory.resultCard("proj", "改动 A", ResultKind.FAILED, "张三（123456）",
                "存在代码冲突，必须由提交人自己解决", mr("TO_BE_MERGED", "改动 A"), null, "");

        assertTrue(json.contains("操作失败 · 改动 A"));
        assertTrue(json.contains("\"template\":\"red\""));
    }

    @Test
    @DisplayName("标题为空时头部回落到项目名，不会出现「已合并 · 」这种空标题")
    void emptyTitleFallsBackToRepo() throws Exception {
        String json = factory.resultCard("1998java/source-admin-new", "", ResultKind.MERGED,
                "张三（123456）", "已合并进 test", null, null, "");

        assertTrue(json.contains("已合并 · 1998java/source-admin-new"));
        assertFalse(json.contains("已合并 · \""), "不能出现空的标题段");
    }

    @Test
    @DisplayName("待审批卡：头部带「待审批 ·」，按钮值带上最近提交，正文不再有明文 URL 行")
    void approvalCardUrlMovedToBottom() throws Exception {
        String json = factory.approvalCard("proj", "5425167", "36", mr("TO_BE_MERGED", "修复订单导出超时"),
                Collections.singletonList(new CommitItem("23a64ce8abcdef", "验证合并", "王涛")),
                "https://codeup.aliyun.com/x");

        assertTrue(json.contains("待审批 · 修复订单导出超时"));
        assertTrue(json.contains("\"template\":\"blue\""), "待审批用蓝色（待办）");
        assertTrue(json.contains("分支：<font color='blue'>**dev订单**</font> → <font color='green'>**test**</font>"));
        assertFalse(json.contains("合并到 "), "按钮只写「合并」");
        assertFalse(json.contains("合并请求：https"), "正文那行明文 URL 已经去掉");
        assertTrue(json.contains("云效链接（长按可复制）：https://codeup.aliyun.com/x"), "底部灰字保留可复制链接");
        assertTrue(json.contains("23a64ce8"), "按钮值里带上最近提交，供结果卡透传");
        assertTrue(json.contains("\"url\":\"https://codeup.aliyun.com/x\""),
                "Webhook 给的链接要塞进按钮值，结果卡的「去云效查看」直接用它");
    }

    @Test
    @DisplayName("恢复待审批卡不丢东西：最近提交、两个按钮、自动检查、跳转按钮、灰字链接都在")
    void restoredApprovalCardKeepsEverything() throws Exception {
        String json = factory.approvalCardFromCallback("proj", "5425167", "36",
                mr("TO_BE_MERGED", "修复订单导出超时"), COMMIT, "https://codeup.aliyun.com/x");

        assertTrue(json.contains("待审批 · 修复订单导出超时"));
        assertTrue(json.contains("最近提交"), "取消/重试后提交内容不能消失");
        assertTrue(json.contains(COMMIT));
        assertTrue(json.contains("合并"), "合并按钮还在");
        assertFalse(json.contains("合并到 "), "按钮不再带分支名，长分支名不会挤坏并排布局");
        assertTrue(json.contains("拒绝"), "拒绝按钮还在");
        assertTrue(json.contains("机器人自动检查"));
        assertTrue(json.contains("在云效打开"), "跳转按钮还在");
        assertTrue(json.contains("云效链接（长按可复制）"));
        assertTrue(json.contains("\"url\":\"https://codeup.aliyun.com/x\""), "按钮值继续透传链接");
        assertTrue(json.contains("23a64ce8"), "按钮值继续透传最近提交");
    }

    @Test
    @DisplayName("没有提交内容时不渲染空的「最近提交」块")
    void noGhostCommitBlock() throws Exception {
        String json = factory.approvalCardFromCallback("proj", "5425167", "36",
                mr("TO_BE_MERGED", "改动 A"), "", "https://codeup.aliyun.com/x");
        assertFalse(json.contains("最近提交"));
    }

    @Test
    @DisplayName("头部截断按显示宽度算：英文项目名完整保留，超长中文标题才截")
    void headerTruncatesByWidth() throws Exception {
        // 25 个半角字符（宽度 25），按宽度 38 应该完整放下
        String ascii = factory.resultCard("1998java/source-admin-new", "", ResultKind.MERGED,
                "张三（123456）", "已合并进 test", null, null, "");
        assertTrue(ascii.contains("已合并 · 1998java/source-admin-new"),
                "英文项目名不该被截，按字符数截会白白切掉最后几个字母");

        // 31 个汉字（宽度 62），超过 38，必须截
        String longTitle = "修复登录弹窗在缩放一百二十五的情况下切换标签页会出现抖动的问题";
        String cn = factory.resultCard("proj", longTitle, ResultKind.MERGED,
                "张三（123456）", "已合并进 test", null, null, "");
        assertTrue(cn.contains("…"), "超长中文标题要在头部截断");
        assertFalse(cn.contains("已合并 · " + longTitle), "整段塞进 header 会超出飞书一行宽度");
        // 但正文里的完整标题不能丢，一个字都不能截
        assertTrue(cn.contains(longTitle), "正文必须保留完整、不截断的标题");
    }
}
