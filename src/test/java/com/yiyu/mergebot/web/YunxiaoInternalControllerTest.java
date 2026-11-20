package com.yiyu.mergebot.web;

import com.yiyu.mergebot.support.WebhookGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内部入口的返回码映射测试。
 *
 * 这是监听服务唯一的判据：200 删文件、400 直接进死信、503 进重试队列。
 * 映射错一档的后果不对等——把"永久失败"映射成 503，消息会白等六次、跨两小时三十八分；
 * 把"可重试"映射成 400，一次网络抖动就把消息丢进死信，只能人工补发。
 */
class YunxiaoInternalControllerTest {

    private static final String DELIVERY = "83fd7702-a9d1-46ca-9c05-058bcb084b89";

    private WebhookDispatchService dispatchService;
    private YunxiaoInternalController controller;

    @BeforeEach
    void setUp() {
        dispatchService = mock(WebhookDispatchService.class);
        WebhookGuard guard = mock(WebhookGuard.class);
        when(guard.deliveryOf(org.mockito.ArgumentMatchers.any(HttpHeaders.class))).thenReturn(DELIVERY);
        controller = new YunxiaoInternalController(dispatchService, guard);
    }

    @Test
    @DisplayName("处理完返回 200，队列据此删文件")
    void doneIs200() {
        when(dispatchService.dispatch(anyString(), anyString()))
                .thenReturn(DispatchResult.done("成功：已发卡片"));

        assertEquals(HttpStatus.OK.value(), call().getStatusCodeValue());
    }

    @Test
    @DisplayName("重复投递也返回 200，不然队列会一直重投同一份报文")
    void ignoredIs200() {
        when(dispatchService.dispatch(anyString(), anyString()))
                .thenReturn(DispatchResult.ignored("跳过：这条投递已经发过卡片了"));

        assertEquals(HttpStatus.OK.value(), call().getStatusCodeValue());
    }

    @Test
    @DisplayName("重试无用返回 400，队列直接进死信，不浪费六次重试")
    void permanentFailIs400() {
        when(dispatchService.dispatch(anyString(), anyString()))
                .thenReturn(DispatchResult.permanentFail("失败：取不到 repoId"));

        assertEquals(HttpStatus.BAD_REQUEST.value(), call().getStatusCodeValue());
    }

    @Test
    @DisplayName("暂时处理不了返回 503，队列进重试队列按阶梯再来")
    void retryableIs503() {
        when(dispatchService.dispatch(anyString(), anyString()))
                .thenReturn(DispatchResult.retryable("失败：处理这条投递时出错：飞书接口超时"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), call().getStatusCodeValue());
    }

    @Test
    @DisplayName("同一条投递还在处理中返回 503，让队列稍后再来——绝不能回 200 让它删文件")
    void inFlightIs503() {
        when(dispatchService.dispatch(anyString(), anyString()))
                .thenReturn(DispatchResult.inFlight("处理中：这条投递还在处理"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), call().getStatusCodeValue());
    }

    @Test
    @DisplayName("报文按请求头里的 charset 解码，中文标题不能变乱码（只有走转发这条路才可能乱）")
    void bodyDecodedAsUtf8() {
        when(dispatchService.dispatch(anyString(), anyString()))
                .thenReturn(DispatchResult.done("成功"));

        byte[] body = "{\"title\":\"机构查询我的课程\"}".getBytes(StandardCharsets.UTF_8);
        controller.onEvent(body, new HttpHeaders(), "application/json;charset=UTF-8");

        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(dispatchService).dispatch(raw.capture(), org.mockito.ArgumentMatchers.eq(DELIVERY));
        assertTrue(raw.getValue().contains("机构查询我的课程"), raw.getValue());
    }

    @Test
    @DisplayName("启动探测：空报文、不带投递编号 → 400（告知「接口在」，不要抛异常变成别的码）")
    void startupProbeGets400() {
        // 监听服务启动时会发一条空报文探测"这个接口在不在"，只看是不是 400。
        // 如果报文是必填，Spring 会在参数解析阶段就抛出去，返回码可能变成 415，
        // 而 415 不在监听服务的白名单里，探测会得出错误结论
        WebhookGuard probeGuard = mock(WebhookGuard.class);
        when(probeGuard.deliveryOf(org.mockito.ArgumentMatchers.any(HttpHeaders.class))).thenReturn("");
        YunxiaoInternalController probeController = new YunxiaoInternalController(dispatchService, probeGuard);
        when(dispatchService.dispatch("", ""))
                .thenReturn(DispatchResult.permanentFail("失败：投递编号为空，拒绝处理"));

        ResponseEntity<String> resp = probeController.onEvent(null, new HttpHeaders(), null);

        assertEquals(HttpStatus.BAD_REQUEST.value(), resp.getStatusCodeValue());
        verify(dispatchService).dispatch("", "");
    }

    private ResponseEntity<String> call() {
        String body = "{\"object_kind\":\"merge_request\"}";
        return controller.onEvent(body.getBytes(StandardCharsets.UTF_8), new HttpHeaders(),
                "application/json;charset=UTF-8");
    }
}
