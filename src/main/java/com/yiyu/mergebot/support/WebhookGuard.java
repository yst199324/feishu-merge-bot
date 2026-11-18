package com.yiyu.mergebot.support;

import com.yiyu.mergebot.config.YunxiaoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Webhook 来源校验。
 *
 * 云效推送事件时会带密钥头，值就是你在云效 Webhook 配置里填的 Secret Token。
 * 这里拿它跟本地配置比对，不一致直接拒。
 *
 * 两条硬规则：
 *  1. 配置为空时**一律拒绝**，不能"空就放行"。后者等于门没锁，
 *     任何人 POST 进来都能让机器人发卡片；
 *  2. 日志里**绝不能出现密钥明文**。看着只是"一个填错的密钥"，但管理员排查时
 *     常常把正确密钥填错一个字符就发过来，那样日志里就留下了一个几乎正确的密钥。
 *     只记前 4 位和长度，判断"是不是填的那一个"够用了。
 */
@Slf4j
@Component
public class WebhookGuard {

    private final YunxiaoProperties props;

    public WebhookGuard(YunxiaoProperties props) {
        this.props = props;
    }

    /**
     * 从请求头里取密钥。
     *
     * 云效同时发 `X-Codeup-Token` 和 `X-Gitlab-Token`（新旧两套约定都保留），
     * 任意一个有值就用。只认其中一个的话，哪天云效换了头，
     * 现象会是"密钥填错了"，排查方向直接跑偏。
     *
     * 代码库入口和流水线入口都调这里，避免两处各写一套取头规则。
     */
    public String tokenOf(HttpHeaders headers) {
        if (headers == null) {
            return "";
        }
        String token = headers.getFirst("X-Codeup-Token");
        if (!StringUtils.hasText(token)) {
            token = headers.getFirst("X-Gitlab-Token");
        }
        return token == null ? "" : token;
    }

    /** @return true 表示来源可信 */
    public boolean pass(String token) {
        String expect = props.getWebhookToken();
        if (!StringUtils.hasText(expect)) {
            // 启动校验已经挡住空值，走到这里说明配置被改过，直接拒绝
            log.error("yunxiao.webhook-token 未配置，本次请求被拒绝");
            return false;
        }
        if (!StringUtils.hasText(token)) {
            log.warn("Webhook 请求没带密钥头，已拒绝");
            return false;
        }
        if (!constantTimeEquals(expect, token)) {
            log.warn("Webhook 密钥不匹配（收到 {} 长度 {}，本地长度 {}）",
                    mask(token), token.length(), expect.length());
            return false;
        }
        return true;
    }

    /**
     * 比对用常量时间算法。
     *
     * 普通字符串比较在第一个不同字符处就返回，理论上能靠响应时间差逐位试出密钥。
     * 隔着网络做这件事很难，但这里实现成本是零，没有理由不用。
     */
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 只留前 4 位，其余用星号；长度不超过 4 时全遮住 */
    private String mask(String s) {
        if (s == null || s.isEmpty()) {
            return "(空)";
        }
        return s.length() <= 4 ? "****" : s.substring(0, 4) + "****";
    }
}
