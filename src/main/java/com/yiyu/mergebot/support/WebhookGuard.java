package com.yiyu.mergebot.support;

import com.yiyu.mergebot.config.YunxiaoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Webhook 来源校验。
 *
 * 云效推送事件时会带 X-Codeup-Token 请求头，值就是你在云效 Webhook 配置里填的 Secret Token。
 * 这里拿它跟本地配置比对，不一致直接拒。
 *
 * 注意两个坑：
 *  1. 配置为空时**一律拒绝**，不能"空就放行"。后者等于门没锁，任何人都能 POST 进来让机器人发卡片；
 *  2. 比对要用常量时间比较之外的方式也没意义，这里就是普通字符串比较，
 *     因为这个 token 不是密码，泄露了换个新的就行。
 */
@Slf4j
@Component
public class WebhookGuard {

    private final YunxiaoProperties props;

    public WebhookGuard(YunxiaoProperties props) {
        this.props = props;
    }

    /** @return true 表示来源可信 */
    public boolean pass(String token) {
        String expect = props.getWebhookToken();
        if (!StringUtils.hasText(expect)) {
            // 启动校验已经挡住空值，走到这里说明配置被改过，直接拒绝
            log.error("yunxiao.webhook-token 未配置，本次请求被拒绝");
            return false;
        }
        if (!expect.equals(token)) {
            log.warn("Webhook token 不匹配，收到 [{}]", token);
            return false;
        }
        return true;
    }
}
