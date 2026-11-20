package com.yiyu.mergebot.web;

import com.yiyu.mergebot.config.FeishuProperties;
import com.yiyu.mergebot.config.YunxiaoProperties;
import com.yiyu.mergebot.feishu.CardFactory;
import com.yiyu.mergebot.feishu.FeishuMessageService;
import com.yiyu.mergebot.support.IdempotentStore;
import com.yiyu.mergebot.support.WebhookDebugStore;
import com.yiyu.mergebot.support.WebhookGuard;
import com.yiyu.mergebot.yunxiao.MrView;
import com.yiyu.mergebot.yunxiao.YunxiaoClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Webhook 去重测试。
 *
 * 这个类守的是一个真实出过的事故：同一个合并请求"关闭 → 重开 → 关闭 → 重开"，
 * 前两次卡片都收到了，第三次重开时卡片消失。原因是去重键只拼了"动作名"，
 * 两次 reopen 的键一模一样，第二次被当成重复投递静默丢掉。
 *
 * 现在键换成云效给的投递编号，测的就是：
 *  - 同一份报文重推 → 只发一张
 *  - 同一动作发生两次（两份不同报文）→ 发两张
 *  - 没带投递编号 → 直接报错，不能拼成空串把后续事件全吞掉
 */
class YunxiaoWebhookControllerTest {

    private static final String TOKEN = "mrb-test";
    private static final String DELIVERY = "83fd7702-a9d1-46ca-9c05-058bcb084b89";

    private FeishuMessageService messageService;
    private WebhookDispatchService dispatchService;
    private WebhookGuard guard;
    private YunxiaoWebhookController controller;

    @BeforeEach
    void setUp() throws Exception {
        FeishuProperties feishuProps = new FeishuProperties();
        feishuProps.setChatId("oc_test");

        YunxiaoProperties yunxiaoProps = new YunxiaoProperties();
        yunxiaoProps.setWebhookToken(TOKEN);

        YunxiaoClient client = mock(YunxiaoClient.class);
        when(client.getRepositoryName(anyString())).thenReturn("");
        when(client.getChangeRequest(anyString(), anyString())).thenReturn(MrView.of(null));
        when(client.commitsFor(anyString(), anyString(), anyString())).thenReturn(Collections.emptyList());
        when(client.webUrlFor(anyString(), anyString(), any(MrView.class))).thenReturn("");

        CardFactory cardFactory = mock(CardFactory.class);
        when(cardFactory.approvalCard(anyString(), anyString(), anyString(), any(MrView.class),
                any(), anyString(), anyBoolean())).thenReturn("{}");

        messageService = mock(FeishuMessageService.class);
        when(messageService.sendCard(anyString(), anyString())).thenReturn("om_test");

        // 业务逻辑已经搬到共用服务里，两个入口（云效直连 / 监听服务转发）共用同一份
        dispatchService = new WebhookDispatchService(feishuProps, client, cardFactory, messageService,
                new IdempotentStore());
        // 配置文件里的默认值就是 true，测试环境不走 Spring 容器，手动置上
        ReflectionTestUtils.setField(dispatchService, "usePayloadCommit", true);

        guard = new WebhookGuard(yunxiaoProps);
        controller = new YunxiaoWebhookController(guard, dispatchService, new WebhookDebugStore());
    }

    @Test
    @DisplayName("同一条投递还在处理中：云效入口回 200（云效不会自动重投，回失败只会多一条假故障记录）")
    void inFlightIs200OnCloudEntry() {
        WebhookDispatchService business = mock(WebhookDispatchService.class);
        when(business.dispatch(anyString(), anyString()))
                .thenReturn(DispatchResult.inFlight("处理中：这条投递还在处理"));
        YunxiaoWebhookController cloudController =
                new YunxiaoWebhookController(guard, business, new WebhookDebugStore());

        ResponseEntity<String> resp = cloudController.onEvent(payload("open"), headers(DELIVERY));

        // 和内部入口的 503 有意不一致：那边要"等一会儿再来"，这边要"别再打扰云效后台"
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    @DisplayName("同一份报文被重推：只发一张卡")
    void sameDeliveryOnlyOnce() {
        controller.onEvent(payload("open"), headers(DELIVERY));
        controller.onEvent(payload("open"), headers(DELIVERY));

        verify(messageService, times(1)).sendCard(anyString(), anyString());
    }

    @Test
    @DisplayName("同一动作发生两次（两次重开）：要发两张卡，这就是之前漏掉的那一张")
    void sameActionTwicePushesTwice() {
        controller.onEvent(payload("reopen"), headers("delivery-1"));
        controller.onEvent(payload("reopen"), headers("delivery-2"));

        verify(messageService, times(2)).sendCard(anyString(), anyString());
    }

    @Test
    @DisplayName("没带投递编号：直接报错，不能拼成空串把后面所有事件静默吞掉")
    void missingDeliveryRejected() {
        ResponseEntity<String> resp = controller.onEvent(payload("open"), headers(null));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(messageService, times(0)).sendCard(anyString(), anyString());
    }

    @Test
    @DisplayName("关闭事件不发卡，也不占用投递编号")
    void closeIgnoredAndDoesNotOccupyDelivery() {
        controller.onEvent(payload("close"), headers(DELIVERY));
        verify(messageService, times(0)).sendCard(anyString(), anyString());

        // 同一条投递编号如果被 close 登记过，这里就发不出去了
        controller.onEvent(payload("open"), headers(DELIVERY));
        verify(messageService, times(1)).sendCard(anyString(), anyString());
    }

    @Test
    @DisplayName("发卡失败要撤销登记，否则云效后台点「重新投递」也会被当成重复挡掉")
    void sendFailureAllowsRedelivery() {
        when(messageService.sendCard(anyString(), anyString()))
                .thenThrow(new RuntimeException("飞书接口超时"))
                .thenReturn("om_ok");

        controller.onEvent(payload("open"), headers(DELIVERY));
        controller.onEvent(payload("open"), headers(DELIVERY));

        verify(messageService, times(2)).sendCard(anyString(), anyString());
    }

    @Test
    @DisplayName("推送事件（云效「测试」按钮）没带投递编号：一样报错，不能按事件类型开天窗")
    void pushEventWithoutDeliveryRejected() {
        ResponseEntity<String> resp = controller.onEvent(
                "{\"object_kind\":\"push\",\"ref\":\"refs/heads/master\"}", headers(null));

        // 缺头说明代理把请求头吃了，这是链路故障。按"这个事件本来也不发卡"放行 200，
        // 等于让故障在配置验证阶段看起来一切正常，等真实合并请求来了才暴露。
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(messageService, times(0)).sendCard(anyString(), anyString());
    }

    @Test
    @DisplayName("推送事件带投递编号：正常忽略，返回 200（入口校验不能误伤正常链路）")
    void pushEventWithDeliveryIgnored() {
        ResponseEntity<String> resp = controller.onEvent(
                "{\"object_kind\":\"push\",\"ref\":\"refs/heads/master\"}", headers(DELIVERY));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(messageService, times(0)).sendCard(anyString(), anyString());
    }

    @Test
    @DisplayName("关闭事件没带投递编号：同样报错——缺头是链路问题，与事件类型无关")
    void closeWithoutDeliveryRejected() {
        ResponseEntity<String> resp = controller.onEvent(payload("close"), headers(null));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(messageService, times(0)).sendCard(anyString(), anyString());
    }

    @Test
    @DisplayName("dispatch 收到空编号要判成永久失败，不能拼出共用键 yx:dlv:null")
    void dispatchRejectsBlankDelivery() {
        // 入口已经拦过，正常走不到这里。这条守的是"以后有人新增调用方忘了校验"：
        // String 拼 null 不会报错，只会默默得到 "yx:dlv:null"，
        // 届时所有请求共用一个键，除第一条外全被静默丢弃。
        // 判成"永久失败"而不是可重试：报文里再也不会长出编号来，重投一百次也一样
        DispatchResult result = dispatchService.dispatch(payload("open"), null);

        assertEquals(DispatchResult.Outcome.PERMANENT_FAIL, result.getOutcome());
        verify(messageService, times(0)).sendCard(anyString(), anyString());
    }

    /** 只保留判断要用到的字段，跟真实报文结构一致（新版：local_id + target_project_id） */
    private String payload(String action) {
        return "{"
                + "\"object_kind\":\"merge_request\","
                + "\"object_attributes\":{"
                + "\"action\":\"" + action + "\","
                + "\"local_id\":52,"
                + "\"target_project_id\":5425059,"
                + "\"last_commit\":{\"id\":\"a000a78ae678de0f8337c2bd66e13b46818b01e4\","
                + "\"message\":\"机构查询我的课程，过滤家教课程\",\"author\":{\"name\":\"zlx\"}},"
                + "\"url\":\"https://codeup.aliyun.com/607f9fbe/1998java/store-web-new/change/52\""
                + "},"
                + "\"repository\":{\"name\":\"store-web-new\"}"
                + "}";
    }

    private HttpHeaders headers(String delivery) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Codeup-Token", TOKEN);
        if (delivery != null) {
            h.add("X-Codeup-Delivery", delivery);
        }
        return h;
    }
}
