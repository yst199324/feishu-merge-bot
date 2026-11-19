package com.yiyu.mergebot.check;

import com.lark.oapi.service.im.v1.model.ListChat;
import com.lark.oapi.service.im.v1.model.ListChatResp;
import com.lark.oapi.service.im.v1.model.ListChatRespBody;
import com.yiyu.mergebot.check.SelfCheckService.Level;
import com.yiyu.mergebot.check.SelfCheckService.Report;
import com.yiyu.mergebot.check.SelfCheckService.Result;
import com.yiyu.mergebot.config.FeishuProperties;
import com.yiyu.mergebot.config.PollProperties;
import com.yiyu.mergebot.config.YunxiaoProperties;
import com.yiyu.mergebot.feishu.CardFactory;
import com.yiyu.mergebot.feishu.FeishuMessageService;
import com.yiyu.mergebot.feishu.FeishuMessageService.ChatProbe;
import com.yiyu.mergebot.feishu.FeishuWsRunner;
import com.yiyu.mergebot.support.WebhookDebugStore;
import com.yiyu.mergebot.yunxiao.YunxiaoClient;
import com.yiyu.mergebot.yunxiao.YunxiaoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自检接口的测试。
 *
 * 守的是两件事：
 *  1. **报错要指对方向**。401（令牌过期）和 403（权限缺项）在云效那边都是"调不通"，
 *     但人要去改的地方完全不同；230002 和 99991672 在飞书那边也是。翻译错了，
 *     自检就只是把错误码换了个地方显示。
 *  2. **自检自己不能把服务搞挂**。所有外部调用都失败时，仍然要返回一份完整报告。
 */
class SelfCheckServiceTest {

    private static final String CHAT_ID = "oc_test_chat";
    private static final String TOKEN = "pt_1234567890abcdef";

    private YunxiaoClient yunxiao;
    private YunxiaoProperties yunxiaoProps;
    private FeishuProperties feishuProps;
    private PollProperties pollProps;
    private FeishuMessageService messageService;
    private FeishuWsRunner wsRunner;
    private WebhookDebugStore debugStore;
    private SelfCheckService service;

    @BeforeEach
    void setUp() throws Exception {
        yunxiao = mock(YunxiaoClient.class);
        messageService = mock(FeishuMessageService.class);
        wsRunner = mock(FeishuWsRunner.class);
        debugStore = new WebhookDebugStore();

        yunxiaoProps = new YunxiaoProperties();
        yunxiaoProps.setToken(TOKEN);
        yunxiaoProps.setOrgId("607f9fbe429bb594fdddc638");
        yunxiaoProps.setWebhookToken("mrb-test");

        feishuProps = new FeishuProperties();
        feishuProps.setAppId("cli_test");
        feishuProps.setAppSecret("secret_test");
        feishuProps.setChatId(CHAT_ID);
        feishuProps.setAllowGroupMembers(true);

        pollProps = new PollProperties();

        // 默认全部正常，各用例只改自己关心的那一项
        when(yunxiao.listRepositories(anyString())).thenReturn(Collections.emptyList());
        when(messageService.listChats()).thenReturn(listChatResp());
        when(messageService.chatProfile(CHAT_ID)).thenReturn(healthyProbe(null));
        when(wsRunner.isConnected()).thenReturn(true);

        service = new SelfCheckService(yunxiao, yunxiaoProps, feishuProps, pollProps,
                messageService, wsRunner, debugStore, new CardFactory(), "18123", "");
    }

    @Test
    @DisplayName("配置齐全时没有需要修的项")
    void allGood() {
        Report report = service.run(false);

        assertEquals(0, report.getCounts().get("fail"), "不该有 FAIL：" + service.render(report));
        assertEquals(4, report.getGroups().size());
        assertNotNull(report.getSummary());
    }

    @Test
    @DisplayName("app-secret 还是占位值时，明确说缺哪一项")
    void missingSecret() {
        feishuProps.setAppSecret("在这里粘贴 App Secret");

        Report report = service.run(false);

        Result item = find(report, "必填配置项");
        assertEquals(Level.FAIL, item.getLevel());
        assertTrue(item.getDetail().contains("app-secret"), item.getDetail());
        assertTrue(item.getFix().contains("application.yml"), item.getFix());
    }

    @Test
    @DisplayName("云效 401 指到「令牌过期」，不是权限")
    void yunxiao401() {
        when(yunxiao.listRepositories(anyString()))
                .thenThrow(new YunxiaoException("云效接口失败 HTTP 401，{\"error\":\"invalid token\"}"));

        Result item = find(service.run(false), "令牌有效、能读代码库");

        assertEquals(Level.FAIL, item.getLevel());
        assertTrue(item.getDetail().contains("401"), item.getDetail());
        assertTrue(item.getFix().contains("7 天"), item.getFix());
        assertFalse(item.getFix().contains("代码仓库"), "401 不该让人去改权限");
    }

    @Test
    @DisplayName("云效 403 指到「令牌缺权限」，不是令牌过期")
    void yunxiao403() {
        when(yunxiao.listRepositories(anyString()))
                .thenThrow(new YunxiaoException("云效接口失败 HTTP 403，Current token has no permission"));

        Result item = find(service.run(false), "令牌有效、能读代码库");

        assertEquals(Level.FAIL, item.getLevel());
        assertTrue(item.getFix().contains("代码仓库"), item.getFix());
        assertFalse(item.getFix().contains("7 天"), "403 不该让人去重新生成令牌");
    }

    @Test
    @DisplayName("机器人不在群里（230002）让人去群设置加机器人")
    void botNotInChat() {
        ChatProbe probe = new ChatProbe();
        probe.setChatOk(false);
        probe.setChatErrorCode(230002);
        probe.setChatErrorMsg("Bot/User can NOT be out of the chat");
        when(messageService.chatProfile(CHAT_ID)).thenReturn(probe);

        Result item = find(service.run(false), "审批群可访问");

        assertEquals(Level.FAIL, item.getLevel());
        assertTrue(item.getDetail().contains("230002"), item.getDetail());
        assertTrue(item.getFix().contains("群机器人"), item.getFix());
    }

    @Test
    @DisplayName("群访问不了时，后面两项标跳过而不是全红")
    void skipWhenChatFails() {
        ChatProbe probe = new ChatProbe();
        probe.setChatOk(false);
        probe.setChatErrorCode(230002);
        when(messageService.chatProfile(CHAT_ID)).thenReturn(probe);

        Report report = service.run(false);

        assertEquals(Level.SKIP, find(report, "能查群成员").getLevel());
        assertEquals(Level.SKIP, find(report, "能换出人名").getLevel());
        assertEquals(Level.SKIP, find(report, "能发消息").getLevel());
    }

    @Test
    @DisplayName("群里只有机器人时，通讯录那项跳过，不报错")
    void noHumanMember() {
        when(messageService.chatProfile(CHAT_ID)).thenReturn(healthyProbe(null));

        assertEquals(Level.SKIP, find(service.run(false), "能换出人名").getLevel());
    }

    @Test
    @DisplayName("群里有真人时自动拿去验通讯录，不需要调用方传 openId")
    void useMemberToProbeContact() {
        when(messageService.chatProfile(CHAT_ID)).thenReturn(healthyProbe("ou_sample"));
        when(messageService.diagnoseUserName("ou_sample"))
                .thenReturn(mapOf("success", true, "name", "张三"));

        Result item = find(service.run(false), "能换出人名");

        assertEquals(Level.PASS, item.getLevel());
        assertTrue(item.getDetail().contains("张三"), item.getDetail());
        verify(messageService).diagnoseUserName("ou_sample");
    }

    @Test
    @DisplayName("默认发卡片；card=false 时一张都不发")
    void sendCardSwitch() {
        service.run(true);
        verify(messageService, times(1)).sendCard(eq(CHAT_ID), anyString());

        service.run(false);
        // 仍然是 1 次，第二次没发
        verify(messageService, times(1)).sendCard(eq(CHAT_ID), anyString());
    }

    @Test
    @DisplayName("外部接口全挂时，自检仍然返回完整报告，不抛异常")
    void everythingBroken() throws Exception {
        when(yunxiao.listRepositories(anyString())).thenThrow(new RuntimeException("网络不通"));
        when(messageService.listChats()).thenThrow(new IllegalStateException("code=10014 msg=bad secret"));
        when(messageService.chatProfile(CHAT_ID)).thenThrow(new RuntimeException("也挂了"));
        when(wsRunner.isConnected()).thenReturn(false);

        Report report = service.run(false);

        assertEquals(4, report.getGroups().size());
        assertTrue(report.getCounts().get("fail") > 0);
        assertTrue(service.render(report).contains("最先要修"));
    }

    @Test
    @DisplayName("报告里不出现完整令牌")
    void noSecretInReport() {
        String text = service.render(service.run(false));

        assertFalse(text.contains(TOKEN), "令牌不该被原样打印");
        verify(messageService, never()).sendCard(anyString(), anyString());
    }

    @Test
    @DisplayName("纯文本报告包含分组名和四项统计")
    void renderText() {
        String text = service.render(service.run(false));

        assertTrue(text.contains("【一、配置文件】"), text);
        assertTrue(text.contains("【四、运行环境】"), text);
        assertTrue(text.contains("共 "), text);
    }

    @Test
    @DisplayName("照抄配置模板不改，必须报「缺哪一项」，不能显示配置已就绪")
    void templatePlaceholders() {
        // 这六个值就是从 deploy/application.yml.example 原样复制过来的。
        // 之前只认 "在这里" 和 "xxxx"，这六个全被判成"已填"，
        // 于是第一项说配置齐全、第六项说令牌过期，自相矛盾。
        yunxiaoProps.setToken("pt_你的令牌");
        yunxiaoProps.setOrgId("你的组织ID");
        yunxiaoProps.setWebhookToken("自己定一个随机字符串");
        feishuProps.setAppId("cli_你的应用ID");
        feishuProps.setAppSecret("你的 App Secret");
        feishuProps.setChatId("oc_审批群ID");

        Report report = service.run(false);

        Result required = find(report, "必填配置项");
        assertEquals(Level.FAIL, required.getLevel(), required.getDetail());
        assertTrue(required.getDetail().contains("云效令牌"), required.getDetail());
        assertTrue(required.getDetail().contains("审批群 chat-id"), required.getDetail());
        // 组织 ID 同样不能显示通过
        assertNotEquals(Level.PASS, find(report, "云效组织 ID").getLevel());
        // 触发方式也不该说"Webhook 就绪"
        assertEquals(Level.FAIL, find(report, "触发方式").getLevel());
    }

    @Test
    @DisplayName("没有必须修的项时不能说「最先要修」，否则等于每天发一份假待办")
    void noFakeTodo() {
        Report report = service.run(false);
        String text = service.render(report);

        // 默认场景下写权限、最近一次推送都是「待确认」，但没有任何 FAIL
        assertEquals(0, report.getCounts().get("fail"));
        assertFalse(text.contains("最先要修"), text);
        assertTrue(text.contains("没有必须修的项"), text);
    }

    @Test
    @DisplayName("自检卡走 CardFactory，和业务卡是同一套 2.0 结构")
    void selfCheckCardUsesCardFactory() {
        ArgumentCaptor<String> card = ArgumentCaptor.forClass(String.class);

        service.run(true);

        verify(messageService).sendCard(eq(CHAT_ID), card.capture());
        String json = card.getValue();
        // 1.0 结构是顶层 elements，2.0 是 body.elements。这里必须是后者，
        // 否则飞书收紧 1.0 时会出现"业务卡正常、自检卡发不出去"
        assertTrue(json.contains("\"body\""), json);
        assertTrue(json.contains("自检"), json);
    }

    @Test
    @DisplayName("java -jar 启动时按 classpath 认出自己的 jar，不靠执行目录")
    void jarFromClasspathWhenLaunchedByJar() throws Exception {
        // start.sh 是 java -jar merge-bot.jar，此时 classpath 恰好只有这一项
        File jar = File.createTempFile("merge-bot", ".jar");
        jar.deleteOnExit();

        assertEquals(jar.getAbsoluteFile(),
                SelfCheckService.jarFromClasspath(jar.getAbsolutePath()));
    }

    @Test
    @DisplayName("IDE / java -cp 启动时 classpath 里混着依赖，不能瞎认一个 jar")
    void jarFromClasspathIgnoresMixedClasspath() throws Exception {
        File jar = File.createTempFile("merge-bot", ".jar");
        jar.deleteOnExit();
        String sep = File.pathSeparator;

        // 依赖 jar + 自己的 jar：认错就会把依赖所在目录当成部署目录
        assertNull(SelfCheckService.jarFromClasspath(jar.getAbsolutePath() + sep + "/tmp/dep.jar"));
        // 纯 class 目录（IDE 常态）
        assertNull(SelfCheckService.jarFromClasspath(
                "/work/target/classes" + sep + jar.getAbsolutePath()));
        // 空的
        assertNull(SelfCheckService.jarFromClasspath(""));
        assertNull(SelfCheckService.jarFromClasspath(null));
    }

    // ---------------------------------------------------------------- 工具

    private ChatProbe healthyProbe(String sampleOpenId) {
        ChatProbe probe = new ChatProbe();
        probe.setChatOk(true);
        probe.setChatName("审批群");
        probe.setMembersOk(true);
        probe.setMemberTotal(3);
        probe.setSampleOpenId(sampleOpenId);
        return probe;
    }

    private ListChatResp listChatResp() {
        ListChat chat = new ListChat();
        chat.setChatId(CHAT_ID);
        chat.setName("审批群");
        ListChatRespBody body = new ListChatRespBody();
        body.setItems(new ListChat[]{chat});
        ListChatResp resp = new ListChatResp();
        resp.setData(body);
        return resp;
    }

    private Result find(Report report, String title) {
        return report.getGroups().stream()
                .flatMap(g -> g.getItems().stream())
                .filter(r -> title.equals(r.getTitle()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("报告里没有这一项：" + title));
    }

    private Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
