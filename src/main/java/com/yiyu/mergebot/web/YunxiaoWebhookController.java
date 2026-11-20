package com.yiyu.mergebot.web;

import com.yiyu.mergebot.support.WebhookDebugStore;
import com.yiyu.mergebot.support.WebhookGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.TreeMap;

/**
 * 接收云效代码库 Webhook（云效直连这条链路，也是应急直连通道）。
 *
 * 云效只会往一个公网 URL 发 POST，不支持长连接，
 * 所以这个接口必须能被外网访问。云效的回调出口 IP 是 47.98.116.130 和 47.111.186.29，
 * 建议在 Nginx 上也加一层白名单，和 X-Codeup-Token 形成两道防护。
 *
 * 本类只负责云效入口独有的三件事：**验来源、取投递编号、把结论翻成云效认的返回码**。
 * 业务处理（判断该不该发卡、拼卡片、发出去）在 {@link WebhookDispatchService}，
 * 因为监听服务的内部入口 {@code /internal/yunxiao} 要用同一份逻辑，
 * 两者只在"怎么验来源"和"返回什么码"上不同（设计文档 4.3）。
 */
@Slf4j
@RestController
public class YunxiaoWebhookController {

    private final WebhookGuard guard;
    private final WebhookDispatchService dispatchService;
    private final WebhookDebugStore debugStore;

    /** 是否把云效报文原文整段打进日志。默认开，排错时全靠它 */
    @Value("${mergebot.log-webhook-raw:true}")
    private boolean logRawWebhook;

    public YunxiaoWebhookController(WebhookGuard guard,
                                    WebhookDispatchService dispatchService,
                                    WebhookDebugStore debugStore) {
        this.guard = guard;
        this.dispatchService = dispatchService;
        this.debugStore = debugStore;
    }

    @PostMapping("/webhook/yunxiao")
    public ResponseEntity<String> onEvent(@RequestBody String raw,
                                          @RequestHeader HttpHeaders headers) {

        // 云效同时发 X-Codeup-Token 和 X-Gitlab-Token 两个头（跟报文一样有新旧两套约定），
        // 取头规则统一放在 WebhookGuard 里，两个 Webhook 入口共用，避免各写一套。
        String token = guard.tokenOf(headers);
        logIncoming(raw, headers, token);

        // 来源校验：配 Webhook 时填的 Secret Token 就是干这个的，不一致直接拒
        if (!guard.pass(token)) {
            debugStore.record(token, false, "来源校验失败：token 和 yunxiao.webhook-token 不一致", raw);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("forbidden");
        }

        // 投递编号是去重的唯一依据，任何事件缺了都必须拦在门口，不分事件类型。
        //
        // ⚠️ 绝不能"取不到就当空串拼进键里"：那样所有请求共用一个键，
        // 30 分钟内第一条之后全部被判成重复、静默丢弃——比漏一张卡严重得多。
        //
        // ⚠️ 也不能只对"要发卡的事件"校验。缺头几乎不是云效没发，而是中间的反向代理/网关
        // 把未声明的请求头滤掉了（Nginx 的 proxy_set_header 白名单、WAF 都会这么干）。
        // 这种配置故障第一个被踩到的往往是配置完点「测试」按钮——推送事件被正常忽略、
        // 返回 200，看起来链路完全正常；等真实合并请求来了才 400，检测点刚好错过最该报错的时刻。
        String deliveryId = guard.deliveryOf(headers);
        if (!StringUtils.hasText(deliveryId)) {
            log.error("webhook 报文没带 X-Codeup-Delivery 头，无法去重，本次丢弃。"
                    + "检查 Nginx/网关是否配了 proxy_set_header 白名单，把未知请求头滤掉了");
            debugStore.record(token, true, "缺少 X-Codeup-Delivery 头，已丢弃", raw);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("missing X-Codeup-Delivery");
        }

        DispatchResult result = dispatchService.dispatch(raw, deliveryId);
        // 处理结论原样留给 /test/last-webhook，排查"云效说推送成功、群里没卡片"时全靠它
        debugStore.record(token, true, result.getNote(), raw);
        return toCloudResponse(result);
    }

    /**
     * 处理结论 → 云效认的返回码。
     *
     * 语义按设计文档 6.3：请求结构、来源校验、事件过滤、200/400/403 保持现状，
     * **唯一改动是处理异常时不再吞掉异常返回 200，改成如实返回 500**。
     * 吞异常回 200 会让云效后台显示"投递成功"，但卡片其实没发出去，运维完全看不出丢了一条；
     * 返回 500 后云效后台会显示投递失败，人能看见，也才有机会去点「重新投递」。
     *
     * @return 200 = 收到并处理完（含"明确不用处理"）；400 = 报文或凭据问题，重投也没用；500 = 处理失败
     */
    private ResponseEntity<String> toCloudResponse(DispatchResult result) {
        switch (result.getOutcome()) {
            case DONE:
            case IGNORED:
                return ResponseEntity.ok("ok");
            case IN_FLIGHT:
                // 同一条投递上一次还在处理中 → 这里回 200，和内部入口的回 503 是**有意不一致的**：
                // 云效不会自动重投（后台只有一个手工按钮），回 400/500 只会在它的投递记录里
                // 多一条假的失败，运维反而要来处理一次"根本不存在的故障"。
                // 真正需要"等一会儿再来"的是队列，那个语义在内部入口。
                return ResponseEntity.ok("ok");
            case PERMANENT_FAIL:
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result.getNote());
            default:
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result.getNote());
        }
    }

    // ---------------------------------------------------------------- 日志

    /**
     * 收到请求就把"云效到底有没有送进来、带了多少字节、判成什么事件"记下来。
     *
     * 没有这一行的话，"什么都没打印"既可能是没收到请求，也可能是收到了但被静默忽略，
     * 两种完全相反的原因在日志里长得一模一样，排错时会走错方向。
     */
    private void logIncoming(String raw, HttpHeaders headers, String token) {
        log.info("收到云效 webhook, 报文长度={}, 事件={}, token={}",
                raw == null ? 0 : raw.length(),
                orDefault(headers.getFirst("X-Codeup-Event"), "（未带事件头）"),
                StringUtils.hasText(token) ? "已带" : "未带");

        // 请求头全量落日志，密钥类脱敏。
        // X-Codeup-Event 决定云效认为这是哪种事件（Push Hook / Merge Request Hook），
        // X-Codeup-Delivery 用来和云效「投递记录」里的那一次推送对上号。
        // 查"云效说发了、代码没反应"时，这两项和报文原文一样关键。
        log.info(headersForLog(headers));

        // 报文原文整段落日志，不做截断、不做美化。
        // 云效的字段名会随版本变（新版把 iid 改成 local_id、仓库 ID 挪进 object_attributes），
        // 出问题时唯一可靠的东西就是"它到底发了什么"，凭记忆猜字段名会白折腾一晚上。
        // 嫌吵可以配 mergebot.log-webhook-raw=false 关掉，只留上面那行摘要。
        if (logRawWebhook) {
            log.info("云效 webhook 原文:\n{}", raw);
        }
    }

    private String orDefault(String s, String fallback) {
        return StringUtils.hasText(s) ? s : fallback;
    }

    /**
     * 把所有请求头整理成一段可读日志。
     *
     * 排序后输出（TreeMap + 忽略大小写），这样同一个请求每次打印顺序一致，
     * 做日志比对时不会因为顺序变化看着像"多了/少了东西"。
     */
    private String headersForLog(HttpHeaders headers) {
        TreeMap<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> {
            String v = values == null || values.isEmpty() ? "" : String.join(",", values);
            sorted.put(name, maskSecret(name, v));
        });

        StringBuilder sb = new StringBuilder("云效 webhook 请求头（").append(sorted.size()).append(" 个）:");
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            sb.append("\n  ").append(e.getKey()).append(": ").append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * 密钥类请求头脱敏。
     *
     * X-Codeup-Token / X-Gitlab-Token 里放的就是 webhook 的 Secret，
     * 明文写进日志等于把密钥抄进了日志文件（日志还可能被收集外发）。
     * 排查时用不上全文：保留前 4 位 + 长度，足够判断"填的是不是那一个"，
     * 而"到底匹不匹配"看同一行日志里的 token 结果字段就够了。
     */
    private String maskSecret(String name, String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String lower = name == null ? "" : name.toLowerCase();
        boolean secret = lower.contains("token") || lower.contains("secret")
                || lower.contains("authorization") || lower.contains("signature")
                || lower.contains("cookie") || lower.contains("passwd");
        if (!secret) {
            return value;
        }
        if (value.length() <= 8) {
            return "******（长度 " + value.length() + "）";
        }
        return value.substring(0, 4) + "******（长度 " + value.length() + "）";
    }
}
