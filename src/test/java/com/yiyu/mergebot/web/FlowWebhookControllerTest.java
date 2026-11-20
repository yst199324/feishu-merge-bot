package com.yiyu.mergebot.web;

import com.yiyu.mergebot.config.FeishuProperties;
import com.yiyu.mergebot.config.YunxiaoProperties;
import com.yiyu.mergebot.feishu.CardFactory;
import com.yiyu.mergebot.feishu.FeishuMessageService;
import com.yiyu.mergebot.support.IdempotentStore;
import com.yiyu.mergebot.support.WebhookGuard;
import com.yiyu.mergebot.yunxiao.YunxiaoClient;
import com.yiyu.mergebot.yunxiao.YunxiaoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import javax.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流水线入口的两条硬要求：失败不许占着名额、验签失败要留记录。
 *
 * <p>为什么不依赖 Spring 容器：这里验的是"撤销登记"和"返回码"，跟装配无关。</p>
 */
@ExtendWith(MockitoExtension.class)
class FlowWebhookControllerTest {

    private static final String REPO = "123";
    private static final String BRANCH = "feature/login";

    @Mock
    private YunxiaoProperties yunxiaoProps;

    @Mock
    private FeishuProperties feishuProps;

    @Mock
    private YunxiaoClient yunxiaoClient;

    @Mock
    private CardFactory cardFactory;

    @Mock
    private FeishuMessageService messageService;

    @Mock
    private WebhookGuard guard;

    @Mock
    private HttpServletRequest request;

    private IdempotentStore idempotent;
    private FlowWebhookController controller;

    @BeforeEach
    void setUp() {
        idempotent = new IdempotentStore();
        controller = new FlowWebhookController(yunxiaoProps, feishuProps, yunxiaoClient,
                cardFactory, messageService, guard, idempotent);
    }

    @Test
    @DisplayName("处理失败要撤掉名额：下一次触发还能重新走一遍，不会被自己挡成 dup")
    void failureReleasesTheClaim() {
        when(guard.tokenOf(org.mockito.ArgumentMatchers.any(HttpHeaders.class))).thenReturn("t");
        when(guard.pass("t")).thenReturn(true);
        when(yunxiaoClient.findOpenMergeRequestId(REPO, BRANCH)).thenReturn("42");
        when(yunxiaoClient.getChangeRequest(REPO, "42"))
                .thenThrow(new YunxiaoException(YunxiaoException.Kind.SERVICE_UNAVAILABLE, "云效挂了"));

        ResponseEntity<String> first = trigger();
        ResponseEntity<String> second = trigger();

        // 两次都真的走到了"查详情"，说明第一次的登记已经被撤销
        verify(yunxiaoClient, times(2)).getChangeRequest(REPO, "42");
        assertEquals("error: 云效挂了", first.getBody());
        assertEquals("error: 云效挂了", second.getBody());
    }

    @Test
    @DisplayName("密钥不匹配 → 403，且不碰云效接口")
    void forbiddenStopsBeforeAnyCall() {
        when(guard.tokenOf(org.mockito.ArgumentMatchers.any(HttpHeaders.class))).thenReturn("bad");
        when(guard.pass("bad")).thenReturn(false);

        ResponseEntity<String> response = trigger();

        assertEquals(403, response.getStatusCodeValue());
        verify(yunxiaoClient, times(0)).findOpenMergeRequestId(REPO, BRANCH);
    }

    /** 流水线那边就是这么调的（表单参数）。 */
    private ResponseEntity<String> trigger() {
        return controller.onFlowTrigger(REPO, BRANCH, "test", "张三", "修登录", "12",
                new HttpHeaders(), request);
    }
}
