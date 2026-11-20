package com.yiyu.mergebot.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackContext;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.yiyu.mergebot.config.FeishuProperties;
import com.yiyu.mergebot.support.AuditLog;
import com.yiyu.mergebot.support.IdempotentStore;
import com.yiyu.mergebot.support.MrLocks;
import com.yiyu.mergebot.yunxiao.MrView;
import com.yiyu.mergebot.yunxiao.YunxiaoClient;
import com.yiyu.mergebot.yunxiao.YunxiaoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 卡片容错的「安全边界」测试（设计文档 7.4 / 7.5，验收清单 D 组）。
 *
 * <p>这三条守的是同一件事：<b>什么时候卡片一个字都不能动、什么时候必须换卡</b>。
 * 换错方向的代价不对称——
 *   - 服务抖动时换了红卡：按钮消失，服务恢复后也没法继续点，只能去云效手工操作；
 *   - 调过写接口后却不换卡：卡片还带按钮，用户再点一次就是<b>二次合并</b>。</p>
 *
 * <p>只测这三个关键落点，不铺开覆盖所有分支。用 standalone 方式直接构造
 * {@link P2CardActionTrigger}，不需要起 Spring 容器、不需要飞书长连接。</p>
 */
class CardActionHandlerTest {

    private static final String OPEN_ID = "ou_test_operator";
    private static final String REPO_ID = "5425059";
    private static final String LOCAL_ID = "52";
    private static final String MESSAGE_ID = "om_test_message";

    private final ObjectMapper mapper = new ObjectMapper();

    private FeishuProperties props;
    private CardFactory cardFactory;
    private YunxiaoClient yunxiao;
    private FeishuMessageService messageService;
    private AuditLog auditLog;
    private CardActionHandler handler;

    @BeforeEach
    void setUp() {
        props = new FeishuProperties();
        props.setChatId("oc_test");
        // 走白名单放行，避免依赖"群成员"那条要打飞书接口的通道
        props.setAllowedOpenIds(Arrays.asList(OPEN_ID));

        cardFactory = mock(CardFactory.class);
        yunxiao = mock(YunxiaoClient.class);
        messageService = mock(FeishuMessageService.class);
        auditLog = mock(AuditLog.class);

        // 结果卡随便返回一个能解析的 JSON；本例只关心"有没有 setCard"
        when(cardFactory.resultCard(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("{}");
        when(cardFactory.confirmCard(any(), any(), any(), any(), any(), any()))
                .thenReturn("{}");

        // 手工装配：幂等、锁都用真实对象，贴近线上行为
        handler = new CardActionHandler(props, cardFactory, yunxiao, messageService,
                new IdempotentStore(), new MrLocks(), auditLog);
    }

    @Test
    @DisplayName("「确认合并」时云效连不上（查详情阶段）→ 不换卡、只弹统一提示、幂等名额已撤")
    void confirmMergeWhenYunxiaoUnavailableKeepsCard() throws Exception {
        when(yunxiao.getChangeRequest(eq(REPO_ID), eq(LOCAL_ID)))
                .thenThrow(new YunxiaoException(YunxiaoException.Kind.SERVICE_UNAVAILABLE,
                        "云效接口调用失败（网络或超时）：Connection refused"));

        P2CardActionTriggerResponse resp = handler.handle(mergeOkEvent());

        // 关键断言：没有 card 字段 → 飞书收到空 card 就保留原卡片，服务恢复后还能接着点
        assertNull(resp.getCard(), "查详情阶段连不上云效时，绝不能换卡");
        assertNotNull(resp.getToast());
        assertTrue(resp.getToast().getContent().contains("云效暂时联系不上"),
                "应使用统一提示，而不是原始报错：" + resp.getToast().getContent());
    }

    @Test
    @DisplayName("合并接口已成功、确认状态时连不上云效 → 必须换卡（7.5 安全边界：防二次合并）")
    void confirmResultWhenYunxiaoUnavailableMustReplaceCard() throws Exception {
        // 第一次 getChangeRequest（查详情）成功且可合并；第二次（确认合并结果）抛连不上
        when(yunxiao.getChangeRequest(eq(REPO_ID), eq(LOCAL_ID)))
                .thenReturn(mergeableView())
                .thenThrow(new YunxiaoException(YunxiaoException.Kind.SERVICE_UNAVAILABLE,
                        "云效接口调用失败（网络或超时）：Read timed out"));

        P2CardActionTriggerResponse resp = handler.handle(mergeOkEvent());

        // 已经调过写接口（合并），结果未知 —— 必须换掉卡片，不能把还能点的卡片留给用户
        assertNotNull(resp.getCard(), "调过合并接口之后确认失败，必须换卡，否则会二次合并");
        assertNotNull(resp.getToast());
        assertTrue(resp.getToast().getContent().contains("合并接口已返回成功"),
                "要明确告诉用户「已合并但确认失败，请去云效核对」：" + resp.getToast().getContent());
    }

    @Test
    @DisplayName("云效返回 401（凭据失效）→ 仍然换失败卡并写清原因")
    void authFailureStillReplacesCard() throws Exception {
        when(yunxiao.getChangeRequest(eq(REPO_ID), eq(LOCAL_ID)))
                .thenThrow(new YunxiaoException(YunxiaoException.Kind.AUTH, 401,
                        "云效接口失败 HTTP 401，invalid token", null));

        P2CardActionTriggerResponse resp = handler.handle(mergeOkEvent());

        // 令牌问题重试无用，属于"确定不会成功"，应换失败卡让群里看到结论
        assertNotNull(resp.getCard(), "401 凭据失效应换失败卡，而不是静默保留原卡");
        assertNotNull(resp.getToast());
        assertTrue(resp.getToast().getContent().contains("查合并请求详情失败"),
                "应点明失败发生在查详情阶段：" + resp.getToast().getContent());
    }

    /** 构造「点确认合并」的回调事件（act=merge_ok）。 */
    private P2CardActionTrigger mergeOkEvent() {
        Map<String, Object> value = new HashMap<String, Object>();
        value.put("act", "merge_ok");
        value.put("repo", REPO_ID);
        value.put("mr", LOCAL_ID);
        value.put("name", "组名/仓库名");
        value.put("commit", "a000a78ae678de0f8337c2bd66e13b46818b01e4");
        value.put("url", "https://codeup.aliyun.com/org/group/repo/change/52");

        CallBackAction action = new CallBackAction();
        action.setValue(value);

        CallBackOperator operator = new CallBackOperator();
        operator.setOpenId(OPEN_ID);

        CallBackContext context = new CallBackContext();
        context.setOpenMessageId(MESSAGE_ID);
        context.setOpenChatId("oc_test");

        P2CardActionTriggerData data = new P2CardActionTriggerData();
        data.setOperator(operator);
        data.setContext(context);
        data.setAction(action);

        P2CardActionTrigger event = new P2CardActionTrigger();
        event.setEvent(data);
        return event;
    }

    /** 一张"可合并"的详情视图：状态待合并、无冲突、卡点全过。 */
    private MrView mergeableView() throws Exception {
        String json = "{"
                + "\"title\":\"测试合并请求\","
                + "\"sourceBranch\":\"dev\","
                + "\"targetBranch\":\"test\","
                + "\"status\":\"TO_BE_MERGED\","
                + "\"conflictCheckStatus\":\"NO_CONFLICT\","
                + "\"allRequirementsPass\":true"
                + "}";
        return MrView.of(mapper.readTree(json));
    }
}
