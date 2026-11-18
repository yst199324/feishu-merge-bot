package com.yiyu.mergebot.yunxiao;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合并请求视图的纯函数测试。
 *
 * 这里最重要的一组是状态归一化：云效列表接口用 state（opened/merged/closed），
 * 详情接口用 status（TO_BE_MERGED/MERGED/CLOSED）。这个不一致曾经导致
 * "永远返回 0 条待合并"，是整套代码里最贵的一个坑，必须有测试盯着。
 */
class MrViewTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private MrView of(String json) throws Exception {
        return MrView.of(mapper.readTree(json));
    }

    @Test
    @DisplayName("列表接口的 opened 归一化成待合并")
    void normalizeOpened() throws Exception {
        MrView v = of("{\"state\":\"opened\"}");
        assertEquals("TO_BE_MERGED", v.getStatus());
        assertTrue(v.mergeable());
    }

    @Test
    @DisplayName("详情接口的 TO_BE_MERGED 也认")
    void normalizeToBeMerged() throws Exception {
        MrView v = of("{\"status\":\"TO_BE_MERGED\"}");
        assertTrue(v.mergeable());
    }

    @Test
    @DisplayName("已合并和已关闭都归一化，且不可合并")
    void normalizeMergedAndClosed() throws Exception {
        assertEquals("MERGED", of("{\"state\":\"merged\"}").getStatus());
        assertEquals("CLOSED", of("{\"state\":\"closed\"}").getStatus());
        assertFalse(of("{\"state\":\"merged\"}").mergeable());
        assertFalse(of("{\"state\":\"closed\"}").mergeable());
    }

    @Test
    @DisplayName("状态字段缺失时才用兜底值，有真实状态时兜底值不能覆盖")
    void fallbackOnlyWhenMissing() throws Exception {
        assertEquals("TO_BE_MERGED",
                MrView.of(mapper.readTree("{}"), "TO_BE_MERGED").getStatus());
        assertEquals("MERGED",
                MrView.of(mapper.readTree("{\"state\":\"merged\"}"), "TO_BE_MERGED").getStatus());
    }

    @Test
    @DisplayName("只有明确的 HAS_CONFLICT 才算有冲突")
    void conflictDetection() throws Exception {
        assertTrue(of("{\"conflictCheckStatus\":\"HAS_CONFLICT\"}").hasConflict());
        assertFalse(of("{\"conflictCheckStatus\":\"NO_CONFLICT\"}").hasConflict());
        assertFalse(of("{}").hasConflict());
    }

    @Test
    @DisplayName("内容指纹随提交数变化——推了新代码才会重发卡片")
    void fingerprintChangesWithAhead() throws Exception {
        String one = of("{\"sourceBranch\":\"dev\",\"title\":\"t\",\"ahead\":1,\"behind\":0,\"updatedAt\":\"x\"}")
                .fingerprint();
        String two = of("{\"sourceBranch\":\"dev\",\"title\":\"t\",\"ahead\":2,\"behind\":0,\"updatedAt\":\"x\"}")
                .fingerprint();
        assertNotEquals(one, two);
    }

    @Test
    @DisplayName("空报文不能抛异常——查询失败时卡片还要能发出去")
    void nullSafe() {
        MrView v = MrView.of(null);
        assertEquals("", v.getTitle());
        assertFalse(v.mergeable());
        assertEquals("未知", v.conflictText());
    }
}
