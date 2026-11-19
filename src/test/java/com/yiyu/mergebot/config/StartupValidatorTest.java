package com.yiyu.mergebot.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动校验测试。
 *
 * 这套校验的用意是：宁可起不来，也不要"看起来在跑、其实门开着"。
 * 所以最关键的一条是——**照抄模板必须被拦住**。之前占位值判定只认 `在这里` 和 `xxxx`，
 * 模板里那六个中文占位值全被判成"已填"，服务能启动但接口一个都调不通。
 */
class StartupValidatorTest {

    private StartupValidator validator(String appId, String appSecret, String chatId,
                                      String token, String webhookToken, boolean pollEnabled) {
        YunxiaoProperties y = new YunxiaoProperties();
        y.setToken(token);
        y.setWebhookToken(webhookToken);
        y.setOrgId("1234567890abcdef12345678");

        FeishuProperties f = new FeishuProperties();
        f.setAppId(appId);
        f.setAppSecret(appSecret);
        f.setChatId(chatId);

        PollProperties p = new PollProperties();
        p.setEnabled(pollEnabled);

        return new StartupValidator(y, f, p);
    }

    @Test
    @DisplayName("照抄模板启动直接被拦住，并指出具体是哪个配置项")
    void templatePlaceholdersBlockStartup() {
        // token 传真实值，让校验走到 app-id 这一项，专门验"中文占位值"这条新规则：
        // 以前只认 在这里 / xxxx，cli_你的应用ID 会被当成已填
        StartupValidator v = validator("cli_你的应用ID", "在这里粘贴 App Secret",
                "oc_你的群会话ID", "pt-abc123", "mrb-test", false);
        IllegalStateException e = assertThrows(IllegalStateException.class, v::afterPropertiesSet);
        assertTrue(e.getMessage().contains("feishu.app-id"), e.getMessage());
    }

    @Test
    @DisplayName("模板里的 xxxx 形式占位值同样被拦住")
    void xxxxPlaceholderBlocksStartup() {
        StartupValidator v = validator("cli_a1b2c3d4e5f6g7h8", "abc123secret",
                "oc_1111111111111111111111111111", "pt-xxxxxxxxxxxxxxxx", "mrb-test", false);
        IllegalStateException e = assertThrows(IllegalStateException.class, v::afterPropertiesSet);
        assertTrue(e.getMessage().contains("yunxiao.token"), e.getMessage());
    }

    @Test
    @DisplayName("只漏了 app-secret 也要拦住")
    void missingSecretBlocksStartup() {
        StartupValidator v = validator("cli_a1b2c3d4e5f6g7h8", "你的 App Secret",
                "oc_1111111111111111111111111111", "pt-abc123", "mrb-test", false);
        IllegalStateException e = assertThrows(IllegalStateException.class, v::afterPropertiesSet);
        assertTrue(e.getMessage().contains("feishu.app-secret"), e.getMessage());
    }

    @Test
    @DisplayName("配置齐全时通过校验")
    void completeConfigPasses() {
        StartupValidator v = validator("cli_a1b2c3d4e5f6g7h8", "abc123secret",
                "oc_1111111111111111111111111111", "pt-abc123", "mrb-test", false);
        assertDoesNotThrow(v::afterPropertiesSet);
    }

    @Test
    @DisplayName("既没开轮询又没填 webhook-token 时不启动")
    void noTriggerBlocksStartup() {
        StartupValidator v = validator("cli_a1b2c3d4e5f6g7h8", "abc123secret",
                "oc_1111111111111111111111111111", "pt-abc123", "", false);
        IllegalStateException e = assertThrows(IllegalStateException.class, v::afterPropertiesSet);
        assertTrue(e.getMessage().contains("没有任何触发方式"), e.getMessage());
    }
}
