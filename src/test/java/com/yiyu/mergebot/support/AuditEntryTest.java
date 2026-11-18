package com.yiyu.mergebot.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiyu.mergebot.yunxiao.MrView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计行格式测试。
 *
 * 这一行是"事后查账"的唯一依据，格式必须稳定：
 * 一行一条、字段可 grep、值里的换行和引号不能把行折断。
 */
class AuditEntryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("追踪号 = 消息编号末 6 位 / 合并请求编号")
    void trace() {
        AuditEntry e = new AuditEntry("merge_ok", "杨书庭（676706）", "ou_7d8a6e6df7621556ce0d21922b676706",
                "oc_abc", "om_xyz123456789", "1998java/source-admin-new", "5425167", "36", "");
        assertEquals("456789/36", e.getTrace());
    }

    @Test
    @DisplayName("没有消息编号时追踪号用 no-msg 兜底，不会拼出空值")
    void traceWithoutMessageId() {
        AuditEntry e = new AuditEntry("close", "未知用户（676706）", "ou_xxx",
                "", "", "p", "5425167", "36", "");
        assertTrue(e.getTrace().startsWith("no-msg/"));
    }

    @Test
    @DisplayName("审计行是一行、字段齐全、能按 key=value 读")
    void lineIsSingleLineAndComplete() throws Exception {
        MrView mr = MrView.of(mapper.readTree("{\"title\":\"验证合并\",\"sourceBranch\":\"dev订单\","
                + "\"targetBranch\":\"test\",\"status\":\"TO_BE_MERGED\",\"allRequirementsPass\":true}"));
        AuditEntry e = new AuditEntry("merge_ok", "杨书庭（676706）", "ou_7d8a6e6df7621556ce0d21922b676706",
                "oc_c8db", "om_xyz123456789", "1998java/source-admin-new", "5425167", "36", "");
        e.title(mr.getTitle()).branches(mr.branchText()).result("成功", "已合并进 test\n第二行说明");
        String line = e.line();

        assertFalse(line.contains("\n"), "审计必须一行一条，不能有换行");
        assertTrue(line.contains("trace=456789/36"));
        assertTrue(line.contains("action=merge_ok"));
        assertTrue(line.contains("result=成功"));
        assertTrue(line.contains("openId=ou_7d8a6e6df7621556ce0d21922b676706"));
        assertTrue(line.contains("mr=36"));
        assertTrue(line.contains("branches=\"dev订单 -> test\""));
        assertTrue(line.contains("cost="));
        // 说明里的换行被压成空格
        assertTrue(line.contains("已合并进 test 第二行说明"));
    }

    @Test
    @DisplayName("值里有双引号时换成单引号，不会破坏字段边界")
    void quotesAreSanitized() {
        AuditEntry e = new AuditEntry("merge_ok", "张三", "ou_1", "oc_1", "om_1", "p", "1", "2", "");
        e.result("失败", "云效说 \"不允许合并\"");
        String line = e.line();
        assertTrue(line.contains("云效说 '不允许合并'"));
        assertFalse(line.contains("\"不允许合并\""));
    }
}
