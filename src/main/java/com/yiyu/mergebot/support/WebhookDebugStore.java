package com.yiyu.mergebot.support;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记住"最近一次收到的 Webhook 长什么样"，供 /test/last-webhook 查看。
 *
 * 为什么需要：云效报文有老版/新版两套结构（新版没有 project 对象、
 * 合并请求编号叫 local_id 而不是 iid），字段对不上时服务只会打一条 warn 日志
 * 然后**照常返回 200**。从云效那边看是"推送成功"，实际一张卡片都没发。
 * 这种情况必须能拿到原文和 HTTP 头才能查，光看云效投递记录是查不出来的。
 *
 * 只保留最后一条，不占内存，也不落盘（报文里可能有仓库信息，没必要存）。
 */
@Component
public class WebhookDebugStore {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private volatile Map<String, Object> latest;

    /**
     * @param token 收到的 X-Codeup-Token（可能是 null）
     * @param passed 是否通过了来源校验
     * @param note 处理结果说明，比如"已发卡片""报文里取不到 repoId"
     */
    public void record(String token, boolean passed, String note, String raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("收到时间", LocalDateTime.now().format(FMT));
        m.put("token 是否带上来", token != null);
        m.put("token 是否匹配", passed);
        m.put("处理结果", note);
        m.put("报文长度", raw == null ? 0 : raw.length());
        m.put("报文", raw);
        this.latest = m;
    }

    /** @return 最近一条，没有则返回空 Map */
    public Map<String, Object> latest() {
        Map<String, Object> v = latest;
        return v == null ? new LinkedHashMap<>() : v;
    }
}
