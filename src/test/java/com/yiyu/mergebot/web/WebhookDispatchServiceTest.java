package com.yiyu.mergebot.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiyu.mergebot.config.FeishuProperties;
import com.yiyu.mergebot.feishu.CardFactory;
import com.yiyu.mergebot.feishu.FeishuMessageService;
import com.yiyu.mergebot.support.IdempotentStore;
import com.yiyu.mergebot.yunxiao.MrView;
import com.yiyu.mergebot.yunxiao.YunxiaoClient;
import com.yiyu.mergebot.yunxiao.YunxiaoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 处理结论的判定测试。
 *
 * 守的是"该不该重试"这一条判断：判错一次，代价是"消息白等两小时三十八分后进死信"，
 * 或者反过来"把一份永远不可能成功的报文重投六次"。而这套判断不能靠异常类别去猜——
 * 发飞书卡片失败抛的是 IllegalStateException，根本不在云效异常分类里。
 */
class WebhookDispatchServiceTest {

    private static final String DELIVERY = "83fd7702-a9d1-46ca-9c05-058bcb084b89";

    private YunxiaoClient client;
    private FeishuMessageService messageService;
    private IdempotentStore idempotent;
    private WebhookDispatchService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        FeishuProperties feishuProps = new FeishuProperties();
        feishuProps.setChatId("oc_test");

        client = mock(YunxiaoClient.class);
        when(client.getRepositoryName(anyString())).thenReturn("");
        when(client.getChangeRequest(anyString(), anyString())).thenReturn(view("TO_BE_MERGED"));
        when(client.commitsFor(anyString(), anyString(), anyString())).thenReturn(Collections.emptyList());
        when(client.webUrlFor(anyString(), anyString(), any(MrView.class))).thenReturn("");

        CardFactory cardFactory = mock(CardFactory.class);
        when(cardFactory.approvalCard(anyString(), anyString(), anyString(), any(MrView.class),
                any(), anyString(), anyBoolean())).thenReturn("{}");

        messageService = mock(FeishuMessageService.class);
        when(messageService.sendCard(anyString(), anyString())).thenReturn("om_test");

        idempotent = new IdempotentStore();
        service = new WebhookDispatchService(feishuProps, client, cardFactory, messageService, idempotent);
        ReflectionTestUtils.setField(service, "usePayloadCommit", true);
    }

    @Test
    @DisplayName("发飞书卡片失败要判成可重试——它抛的是 IllegalStateException，不属于云效异常分类")
    void sendCardFailureIsRetryable() {
        when(messageService.sendCard(anyString(), anyString()))
                .thenThrow(new IllegalStateException("发卡片失败: 飞书接口超时"));

        DispatchResult result = service.dispatch(payload("open"), DELIVERY);

        // 按异常类别猜的话它落到"其他"，可能被误判成永久失败直接进死信，
        // 而实际情况是重试一次就好了
        assertEquals(DispatchResult.Outcome.RETRYABLE, result.getOutcome());
    }

    @Test
    @DisplayName("云效令牌失效（401）判成永久失败——令牌不会自己变好，白重试六次没意义")
    void authFailureIsPermanent() {
        when(client.getChangeRequest(anyString(), anyString()))
                .thenThrow(new YunxiaoException(YunxiaoException.Kind.AUTH, 401,
                        "云效接口失败 HTTP 401，invalid token", null));

        DispatchResult result = service.dispatch(payload("open"), DELIVERY);

        assertEquals(DispatchResult.Outcome.PERMANENT_FAIL, result.getOutcome());
    }

    @Test
    @DisplayName("云效连不上判成可重试——过一会儿再试往往就好了")
    void serviceUnavailableIsRetryable() {
        when(client.getChangeRequest(anyString(), anyString()))
                .thenThrow(new YunxiaoException(YunxiaoException.Kind.SERVICE_UNAVAILABLE,
                        "云效接口调用失败（网络或超时）：Connection refused", null));

        DispatchResult result = service.dispatch(payload("open"), DELIVERY);

        assertEquals(DispatchResult.Outcome.RETRYABLE, result.getOutcome());
    }

    @Test
    @DisplayName("详情里状态为空时必须照常发卡——否则字段一变就静默全停，比多发一张废卡难查十倍")
    void blankStatusStillSendsCard() throws Exception {
        when(client.getChangeRequest(anyString(), anyString())).thenReturn(view(null));

        DispatchResult result = service.dispatch(payload("open"), DELIVERY);

        verify(messageService).sendCard(anyString(), anyString());
        assertEquals(DispatchResult.Outcome.DONE, result.getOutcome());
    }

    @Test
    @DisplayName("状态已不是待合并就不发卡，但算处理成功（积压回放时不该刷一屏废卡）")
    void notMergeableSkipped() throws Exception {
        when(client.getChangeRequest(anyString(), anyString())).thenReturn(view("MERGED"));

        DispatchResult result = service.dispatch(payload("open"), DELIVERY);

        verify(messageService, never()).sendCard(anyString(), anyString());
        assertEquals(DispatchResult.Outcome.DONE, result.getOutcome());
        assertTrue(result.getNote().contains("不是待合并"), result.getNote());
    }

    @Test
    @DisplayName("同一条投递上一次还在处理中（没 markDone）：判 IN_FLIGHT，不能再当「已完成」糊过去")
    void concurrentRedeliveryIsInFlight() {
        // 占住那个键 = 模拟"第一次处理还在跑、还没 markDone"。
        // 这不是异常情况：云效/上游等不及重推时就是这样撞上的
        assertEquals(IdempotentStore.Claim.FIRST,
                idempotent.claim(WebhookDispatchService.dedupKeyOf(DELIVERY),
                        IdempotentStore.DEFAULT_TTL_MILLIS));

        DispatchResult result = service.dispatch(payload("open"), DELIVERY);

        assertEquals(DispatchResult.Outcome.IN_FLIGHT, result.getOutcome());
        // 关键：不能因为它被判"这次没做事"就当成已完成——上游据此删文件，消息就丢了
        verify(messageService, never()).sendCard(anyString(), anyString());
    }

    @Test
    @DisplayName("第一次处理完并 markDone 后重投：判 DONE（IGNORED，文案仍是「已经发过卡片」）")
    void redeliveryAfterSuccessIsIgnored() {
        DispatchResult first = service.dispatch(payload("open"), DELIVERY);
        assertEquals(DispatchResult.Outcome.DONE, first.getOutcome());

        DispatchResult second = service.dispatch(payload("open"), DELIVERY);

        assertEquals(DispatchResult.Outcome.IGNORED, second.getOutcome());
        // 文案不能改：/test/last-webhook 和监听服务的日志都靠它认"去重命中"
        assertTrue(second.getNote().contains("已经发过卡片"), second.getNote());
        verify(messageService, times(1)).sendCard(anyString(), anyString());
    }

    @Test
    @DisplayName("「状态不是待合并，跳过」也算处理完成：重投要判 DONE，不能一直判处理中")
    void skippedByStatusCountsAsDone() throws Exception {
        when(client.getChangeRequest(anyString(), anyString())).thenReturn(view("MERGED"));

        assertEquals(DispatchResult.Outcome.DONE,
                service.dispatch(payload("open"), DELIVERY).getOutcome());
        // 这一条守的是 markDone 的位置：如果只把"真发了卡"标完成，
        // 这种跳过会永远停在处理中，同一条投递每次重投都要占一次云效调用
        assertEquals(DispatchResult.Outcome.IGNORED,
                service.dispatch(payload("open"), DELIVERY).getOutcome());
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

    /**
     * 造一个详情视图。
     *
     * @param status 传 null 表示云效返回里压根没有状态字段（字段改名那种事故）
     */
    private MrView view(String status) throws Exception {
        String json = status == null
                ? "{\"title\":\"测试\",\"sourceBranch\":\"dev\",\"targetBranch\":\"test\"}"
                : "{\"title\":\"测试\",\"sourceBranch\":\"dev\",\"targetBranch\":\"test\","
                + "\"status\":\"" + status + "\"}";
        return MrView.of(mapper.readTree(json));
    }
}
