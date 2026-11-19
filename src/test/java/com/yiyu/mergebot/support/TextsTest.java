package com.yiyu.mergebot.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 占位值判定测试。
 *
 * 这条规则决定两件事：服务能不能启动（StartupValidator）、自检报不报"配置没填"
 * （SelfCheckService）。判错的方向只有一种——把占位值当成已填，
 * 表现是服务起来了但所有接口都调不通，比直接起不来更难排查。
 */
class TextsTest {

    @Test
    @DisplayName("空值一律算占位")
    void blankIsPlaceholder() {
        assertTrue(Texts.isPlaceholder(null));
        assertTrue(Texts.isPlaceholder(""));
        assertTrue(Texts.isPlaceholder("   "));
    }

    @Test
    @DisplayName("模板里的中文占位值算占位")
    void chinesePlaceholder() {
        assertTrue(Texts.isPlaceholder("cli_你的应用ID"));
        assertTrue(Texts.isPlaceholder("oc_你的群会话ID"));
        assertTrue(Texts.isPlaceholder("你的组织ID"));
        assertTrue(Texts.isPlaceholder("在这里粘贴 App Secret"));
        assertTrue(Texts.isPlaceholder("在这里填一个随机字符串"));
    }

    @Test
    @DisplayName("xxxx 形式的占位值算占位")
    void xxxxPlaceholder() {
        assertTrue(Texts.isPlaceholder("pt-xxxxxxxxxxxxxxxx"));
        assertTrue(Texts.isPlaceholder("PT-XXXX"));
    }

    @Test
    @DisplayName("真实凭据的形态都不算占位")
    void realValuesAreNotPlaceholder() {
        assertFalse(Texts.isPlaceholder("cli_a1b2c3d4e5f6g7h8"));
        assertFalse(Texts.isPlaceholder("oc_1111111111111111111111111111"));
        assertFalse(Texts.isPlaceholder("1234567890abcdef12345678"));
        assertFalse(Texts.isPlaceholder("mrb-test-token-abc"));
        assertFalse(Texts.isPlaceholder("pt-abc123def456"));
    }

    @Test
    @DisplayName("tail 取末位，空值给占位符")
    void tailStillWorks() {
        assertTrue("f6g7h8".equals(Texts.tail("cli_a1b2c3d4e5f6g7h8", 6)));
        assertTrue(Texts.UNKNOWN_TAIL.equals(Texts.tail(null, 6)));
        assertTrue(Texts.UNKNOWN_TAIL.equals(Texts.tail("  ", 6)));
        assertTrue("ab".equals(Texts.tail("ab", 6)));
    }
}
