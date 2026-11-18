package com.yiyu.mergebot.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等去重。
 *
 * 飞书回调在超时或异常时会重推，用户手抖也会连点两次。
 * 同一张卡片上的同一个动作只处理一次，第二次直接按"已处理"返回，不能报错。
 */
@Slf4j
@Component
public class IdempotentStore {

    private static final long TTL_MILLIS = 30 * 60 * 1000L;
    private static final int CLEAN_THRESHOLD = 5000;

    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    /**
     * @return true 表示第一次见到，可以继续处理；false 表示重复请求
     */
    public boolean firstTime(String key) {
        long now = System.currentTimeMillis();
        if (seen.size() > CLEAN_THRESHOLD) {
            seen.entrySet().removeIf(e -> now - e.getValue() > TTL_MILLIS);
        }
        Long prev = seen.putIfAbsent(key, now);
        if (prev == null) {
            return true;
        }
        log.warn("重复请求已忽略: {}", key);
        return false;
    }
}
