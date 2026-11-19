package com.yiyu.mergebot.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等去重。
 *
 * 两个地方在用，它们要挡的东西不一样，有效期也不该一样：
 *
 *  1. **Webhook 推送**（代码库入口的 key 是云效投递编号 X-Codeup-Delivery，
 *     流水线入口的 key 是"仓库 + 合并请求编号"）：
 *     挡的是"同一份报文被投递了两次"。投递编号本身已经精确到"哪一次投递"，
 *     所以有效期长一点没有副作用——同一条报文隔多久重来都该挡住。
 *     用 {@link #DEFAULT_TTL_MILLIS}。
 *
 *  2. **卡片按钮点击**（key 是消息编号 + 动作）：
 *     挡的是"飞书回调超时重推"和"手抖连点"。飞书 3 秒超时，重推都在秒级，
 *     60 秒足够覆盖。有效期太长反而有害：用户在"确认合并结果"那步失败后
 *     想重试，会被自己 30 分钟前的记录挡住，只能干等。
 *     用 {@link #CLICK_TTL_MILLIS}。
 *
 * 存在内存里，重启即清空——这是有意的取舍：
 * 单机单进程、一天几十个事件，不需要为此引入 Redis 或数据库。
 * 重启后云效重推的旧报文会重新发一张卡，代价是多一张卡，可以接受；
 * 反过来若为省这点内存把键做得太粗，就会漏发卡片，那是不可接受的。
 */
@Slf4j
@Component
public class IdempotentStore {

    /**
     * 默认有效期：Webhook 投递去重用。
     *
     * 为什么是 30 分钟而不是"刚够重试"的几十秒：投递键唯一，长窗口只会多挡、
     * 不会误挡。而缩短窗口的风险是——云效因为超时排队几分钟后再重推同一份报文，
     * 窗口已过，同一件事就发了两张卡。
     */
    public static final long DEFAULT_TTL_MILLIS = 30 * 60 * 1000L;

    /**
     * 卡片按钮点击的有效期。
     *
     * 只覆盖飞书回调的超时重推（秒级）。过期后不会重复执行合并：
     * 还有 MrLocks 的分布式锁和"落笔前重查云效状态"两层兜底。
     */
    public static final long CLICK_TTL_MILLIS = 60 * 1000L;

    /** 条目太多时顺手清掉过期的，避免长期运行越堆越多 */
    private static final int CLEAN_THRESHOLD = 5000;

    /**
     * 一条登记记录。
     *
     * 必须把有效期存进记录里，不能只存时间戳再拿全局常量比对——
     * 两类键的有效期不同，用同一个常量判断会让短有效期的记录被误判成"还新鲜"。
     */
    private static final class Seen {
        private final long at;
        private final long ttl;

        private Seen(long at, long ttl) {
            this.at = at;
            this.ttl = ttl;
        }
    }

    private final Map<String, Seen> seen = new ConcurrentHashMap<>();

    /** 用默认有效期（30 分钟）登记，用于 Webhook 投递去重 */
    public boolean firstTime(String key) {
        return firstTime(key, DEFAULT_TTL_MILLIS);
    }

    /**
     * @param ttlMillis 有效期，传 0 或负数则用默认值
     * @return true 表示第一次见到（或上一次已过期），可以继续处理；false 表示重复请求
     */
    public boolean firstTime(String key, long ttlMillis) {
        long now = System.currentTimeMillis();
        long ttl = ttlMillis <= 0 ? DEFAULT_TTL_MILLIS : ttlMillis;
        boolean[] first = {false};

        // 用 compute 而不是 putIfAbsent：需要"看完时间戳再决定写不写"，
        // 这两步必须是原子的，否则并发下两个线程可能同时判成"第一次"。
        seen.compute(key, (k, prev) -> {
            if (prev == null || now - prev.at > prev.ttl) {
                first[0] = true;
                return new Seen(now, ttl);
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
     * 用在"操作没成功"的场合：云效接口超时、网络失败、发卡片失败这类问题，
     * 用户应该能立刻重试；如果标记留着，他会被误判成"重复点击"而干等半小时。
     *
     * 注意：只有确定**没生效**的失败才撤销。像"有冲突""卡点未过"这种业务拒绝不用撤销，
     * 因为重试也没用，得先去云效处理。
     */
    public void forget(String key) {
        seen.remove(key);
    }

    /** 条目太多时清理过期的；按每条记录自己的有效期判断，不能用全局常量 */
    private void cleanIfNeeded(long now) {
        if (seen.size() > CLEAN_THRESHOLD) {
            seen.entrySet().removeIf(e -> now - e.getValue().at > e.getValue().ttl);
        }
    }
}
