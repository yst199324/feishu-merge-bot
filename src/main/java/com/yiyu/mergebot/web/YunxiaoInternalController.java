package com.yiyu.mergebot.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiyu.mergebot.support.WebhookGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 监听服务（feishu-merge-relay）转发过来的云效报文。
 *
 * 为什么不复用 {@code /webhook/yunxiao}（设计文档 4.3）：返回码语义完全不同。
 * 云效入口只需要 200 / 400 / 403 / 500 几种人话；这个入口必须给队列三态——
 * 200 删文件、400 直接进死信、503 进重试队列。一个接口满足两套语义只能靠加参数区分，
 * 反而更难维护。业务逻辑是共用的（{@link WebhookDispatchService}），
 * 这里只有约百行的接口适配。
 *
 * 两条与云效入口不同的地方：
 *  1. **不做来源校验**（也不看 X-Codeup-Token）。用户已定：靠"端口不对公网开放"保护，
 *     将来两个服务分机器部署时只改安全组，不用改代码；
 *  2. 失败必须如实返回非 200：以前吞掉异常回 200 会让队列以为转发成功、把文件删掉，
 *     这条消息就彻底消失了（R13）。
 *
 * ⚠️ 默认**不注册**（{@code mergebot.internal-endpoint-enabled=false}）。
 * 形态一下合并项目自己占着对公网开放的 18123，这个接口又没有来源校验，
 * 永远注册等于开一个无鉴权发卡入口。部署监听服务时在 .env 设 INTERNAL_ENDPOINT=true。
 */
@Slf4j
@RestController
@ConditionalOnProperty(prefix = "mergebot", name = "internal-endpoint-enabled", havingValue = "true")
public class YunxiaoInternalController {

    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    private final WebhookDispatchService dispatchService;
    private final WebhookGuard guard;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 是否把报文原文打进日志。和云效入口共用同一个开关，默认开 */
    @Value("${mergebot.log-webhook-raw:true}")
    private boolean logRawWebhook;

    public YunxiaoInternalController(WebhookDispatchService dispatchService, WebhookGuard guard) {
        this.dispatchService = dispatchService;
        this.guard = guard;
    }

    /**
     * 收一条转发过来的投递。
     *
     * 报文用 byte[] 收，是为了按请求头里的 charset 自己解码：转发层会把云效那一次的
     * Content-Type 原样带过来（含 charset），按它解就不会把中文标题解成乱码。
     * 若用 String 直接接，请求头没带 charset 时 Spring 会按 ISO-8859-1 解，
     * 而这只在"走监听服务"这条路上才会出问题，很难联想到是转发层的锅。
     *
     * 报文设成**可选**（{@code required = false}）：监听服务启动时会发一次空报文探测
     * "这个接口在不在"，那条请求没有 body、也可能没有 Content-Type。
     * 用必填的话它会先在 Spring 的参数解析阶段抛异常，返回码可能是 415，
     * 而 415 不在监听服务的白名单里，探测会得出错误结论。
     * 让空报文正常进业务逻辑（判成"缺投递编号"→400）才能让探测结果确定。
     *
     * 投递编号头名必须认 {@code X-Codeup-Delivery}——队列转发时带的就是它，
     * 认错了会让每条消息都因缺编号被判永久失败、直接进死信。
     * 取头规则统一复用 {@link WebhookGuard#deliveryOf}，不另写一套。
     */
    @PostMapping("/internal/yunxiao")
    public ResponseEntity<String> onEvent(@RequestBody(required = false) byte[] body,
                                          @RequestHeader HttpHeaders headers,
                                          @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false)
                                                  String contentType) {
        String deliveryId = guard.deliveryOf(headers);
        String raw = decode(body, contentType);
        log.info("收到监听服务转发的云效报文, 报文长度={}, 事件={}, 投递={}",
                raw.length(),
                headerOr(headers, "X-Codeup-Event", "（未带事件头）"),
                StringUtils.hasText(deliveryId) ? deliveryId : "（没带投递编号）");
        // 正文原样再记一份：监听服务那边也有一份，这里记的是"本服务实际收到了什么"。
        // 手工重放（README 里那个 curl）时只有这条能对上；两边的日志时间差还能看出转发延迟。
        if (logRawWebhook && StringUtils.hasText(raw)) {
            log.info("监听服务转发原文:\n{}", raw);
        }

        // 缺投递编号时 dispatch 会判成"永久失败"，回 400：
        // 队列那边直接进死信，不用白等六次重试
        DispatchResult result = dispatchService.dispatch(raw, deliveryId);
        return respond(result);
    }

    /**
     * 结论 → 返回码（设计文档 6.2 的三态）。
     *
     * 200 = 已发卡 / 确认重复 / 不是合并请求事件 → 队列删文件；
     * 400 = 重试再多次也没用（报文解析不了、取不到编号、缺投递编号、云效凭据失效）→ 队列直接进死信；
     * 503 = 现在处理不了（云效连不上、发飞书卡片失败、**同一条投递上一次还在处理中**）→ 队列按阶梯重试。
     *
     * ⚠️ IN_FLIGHT 必须走 503。它和"已完成"看着都是"这次没做事"，但意思相反：
     * 已完成 = 别再来了（删文件），处理中 = 过一会儿再来（留文件）。
     * 这里若图省事回 200，第一次处理最后失败时这条消息就再也找不回来了。
     */
    private ResponseEntity<String> respond(DispatchResult result) {
        HttpStatus status;
        switch (result.getOutcome()) {
            case DONE:
            case IGNORED:
                status = HttpStatus.OK;
                break;
            case IN_FLIGHT:
                status = HttpStatus.SERVICE_UNAVAILABLE;
                break;
            case PERMANENT_FAIL:
                status = HttpStatus.BAD_REQUEST;
                break;
            default:
                status = HttpStatus.SERVICE_UNAVAILABLE;
                break;
        }
        logOutcome(status, result);
        return ResponseEntity.status(status).contentType(JSON_UTF8).body(toJson(result));
    }

    /**
     * 按结论打日志。
     *
     * IN_FLIGHT 用 info：两条投递撞在一起是设计内的情况，不是故障。
     * 用 warn 会让运维以为出了错，去查一个不存在的问题。
     */
    private void logOutcome(HttpStatus status, DispatchResult result) {
        if (status == HttpStatus.OK) {
            return;
        }
        if (result.getOutcome() == DispatchResult.Outcome.IN_FLIGHT) {
            log.info("同一条投递正在处理中，让队列稍后重试：{}", result.getNote());
            return;
        }
        log.warn("内部入口返回 {}，队列会据此决定重试或进死信：{}", status.value(), result.getNote());
    }

    /**
     * 响应体：{@code {"outcome":"...","note":"..."}}。
     *
     * 队列只看状态码，note 是给人看的（日志、/health、排查时用）。
     * 序列化失败也绝不能反过来把状态码搞错，所以兜底手拼一个最简 JSON。
     */
    private String toJson(DispatchResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", result.getOutcome().name());
        body.put("note", result.getNote());
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.warn("响应体序列化失败，回落到最简 JSON", e);
            return "{\"outcome\":\"" + result.getOutcome().name() + "\",\"note\":\"\"}";
        }
    }

    private String decode(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return "";
        }
        return new String(body, charsetOf(contentType));
    }

    /** Content-Type 里没写 charset 时按 UTF-8 解；写坏了也回落 UTF-8，不能因此把消息丢掉 */
    private Charset charsetOf(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return StandardCharsets.UTF_8;
        }
        try {
            Charset charset = MediaType.parseMediaType(contentType).getCharset();
            return charset == null ? StandardCharsets.UTF_8 : charset;
        } catch (Exception e) {
            log.warn("Content-Type 解析不了，按 UTF-8 处理：{}", contentType);
            return StandardCharsets.UTF_8;
        }
    }

    private String headerOr(HttpHeaders headers, String name, String fallback) {
        String value = headers.getFirst(name);
        return StringUtils.hasText(value) ? value : fallback;
    }
}
