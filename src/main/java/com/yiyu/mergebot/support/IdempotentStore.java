package com.yiyu.mergebot.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等去重。
 *
 * 飞书回调在超时或异常时会重推，用户手抖也会连点两次。
 * 同一张卡片上的同一个动作在有效期内只处理一次，第二次直接按"已处理"返回，不能报错。
 *
 * 有效期满后允许重新处理：合并这种操作有状态校验兜底（已合并的会被拦住），
 * 所以"宁可允许重试，也不要因为一张旧卡片的记录把后续操作永久锁死"。
 *
 * 存在内存里，重启即清空——这是有意的取舍：
 * 单机单进程、一天几十个事件，不需要为此引入 Redis 或数据库。
 * 重启后飞书重推的旧回调会重新执行一次，但那时云效状态已经变了，会被状态校验挡住。
 */
@Slf4j
@Component
public class IdempotentStore {

    private static final long TTL_MILLIS = 30 * 60 * 1000L;
    private static final int CLEAN_THRESHOLD = 5000;

    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    /**
     * @return true 表示第一次见到（或上一次已过期），可以继续处理；false 表示重复请求
     */
    public boolean firstTime(String key) {
        long now = System.currentTimeMillis();
        boolean[] first = {false};

        // 用 compute 而不是 putIfAbsent：需要"看完时间戳再决定写不写"，
        // 这两步必须是原子的，否则并发下两个线程可能同时判成"第一次"。
        seen.compute(key, (k, prev) -> {
            if (prev == null || now - prev > TTL_MILLIS) {
                first[0] = true;
                return now;
            }
            return prev;
        });

        if (first[0]) {
            cleanIfNeeded(now);
        } else {
            log.warn("重复请求已忽略: {}", key);
        }
        return first[0];
    }

    /**
     * 撤销一次登记。
     *
     * 用在"操作没成功"的场合：云效接口超时、网络失败这类问题，
     * 用户应该能立刻重试；如果标记留着，他会被误判成"重复点击"而干等 30 分钟。
     *
     * 注意：只有确定**没生效**的失败才撤销。像"有冲突""卡点未过"这种业务拒绝不用撤销，
     * 因为重试也没用，得先去云效处理。
     */
    public void forget(String key) {
        seen.remove(key);
    }

    /** 条目太多时顺手清掉过期的，避免长期运行越堆越多 */
    private void cleanIfNeeded(long now) {
        if (seen.size() > CLEAN_THRESHOLD) {
            seen.entrySet().removeIf(e -> now - e.getValue() > TTL_MILLIS);
        }
    }
}
