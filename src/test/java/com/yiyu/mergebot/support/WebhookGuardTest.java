package com.yiyu.mergebot.support;

import com.yiyu.mergebot.config.YunxiaoProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Webhook 来源校验测试。
 *
 * 这是"谁能往服务里塞事件"的唯一一道门，空配置必须拒绝——
 * 一旦"空就放行"，等于公网任何人都能让机器人在群里发卡片。
 */
class WebhookGuardTest {

    private WebhookGuard guard(String expected) {
        YunxiaoProperties props = new YunxiaoProperties();
        props.setWebhookToken(expected);
        return new WebhookGuard(props);
    }

    @Test
    @DisplayName("本地没配密钥时一律拒绝，不能放行")
    void emptyExpectedRejectsAll() {
        assertFalse(guard("").pass("anything"));
        assertFalse(guard(null).pass("anything"));
    }

    @Test
    @DisplayName("密钥一致才通过")
    void matchingTokenPasses() {
        assertTrue(guard("mrb-2026").pass("mrb-2026"));
    }

    @Test
    @DisplayName("密钥不一致、或压根没带，都拒绝")
    void mismatchOrMissingRejects() {
        assertFalse(guard("mrb-2026").pass("mrb-2027"));
        assertFalse(guard("mrb-2026").pass(null));
        assertFalse(guard("mrb-2026").pass(""));
    }

    @Test
    @DisplayName("两个密钥头任意一个有值都能取到")
    void tokenFromEitherHeader() {
        HttpHeaders codeup = new HttpHeaders();
        codeup.set("X-Codeup-Token", "a");
        assertEquals("a", guard("a").tokenOf(codeup));

        HttpHeaders gitlab = new HttpHeaders();
        gitlab.set("X-Gitlab-Token", "b");
        assertEquals("b", guard("b").tokenOf(gitlab));

        // 两个都没有时返回空串，交给 pass() 判拒绝
        assertEquals("", guard("c").tokenOf(new HttpHeaders()));
    }

    @Test
    @DisplayName("取头时优先用 X-Codeup-Token")
    void preferCodeupHeader() {
        HttpHeaders both = new HttpHeaders();
        both.set("X-Codeup-Token", "codeup");
        both.set("X-Gitlab-Token", "gitlab");
        assertEquals("codeup", guard("codeup").tokenOf(both));
    }

    @Test
    @DisplayName("投递编号从 X-Codeup-Delivery 取")
    void deliveryFromCodeupHeader() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Codeup-Delivery", "83fd7702-a9d1-46ca-9c05-058bcb084b89");
        assertEquals("83fd7702-a9d1-46ca-9c05-058bcb084b89", guard("x").deliveryOf(h));
    }

    @Test
    @DisplayName("没带投递编号时退回 GitLab 的老头名")
    void deliveryFallsBackToGitlabUuid() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Gitlab-Event-UUID", "uuid-old");
        assertEquals("uuid-old", guard("x").deliveryOf(h));
    }

    @Test
    @DisplayName("两个头都没有时返回空串，交给调用方报错——绝不能返回 null 拼进键里")
    void deliveryEmptyWhenMissing() {
        assertEquals("", guard("x").deliveryOf(new HttpHeaders()));
        assertEquals("", guard("x").deliveryOf(null));
    }
}
