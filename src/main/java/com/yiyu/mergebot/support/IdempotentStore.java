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
 *     **这一路走三态**（{@link #claim} / {@link #markDone}）：因为"处理中"和"处理完了"
 *     对上游是两个完全不同的答案——处理中要它等一会儿再来（内部入口回 503），
 *     处理完了才能删文件（回 200）。用 boolean 表达不出来，上游只能猜，猜错的代价是丢消息。
 *
 *  2. **卡片按钮点击**（key 是消息编号 + 动作）：
 *     挡的是"飞书回调超时重推"和"手抖连点"。飞书 3 秒超时，重推都在秒级，
 *     60 秒足够覆盖。有效期太长反而有害：用户在"确认合并结果"那步失败后
 *     想重试，会被自己 30 分钟前的记录挡住，只能干等。
 *     用 {@link #CLICK_TTL_MILLIS}。
 *     这一路仍用 {@link #firstTime}，**永远不调 markDone**——点完就结束，没有"处理中"这个状态。
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
     * 一次登记现在处于哪一态。
     */
    public enum Claim {
        /** 第一次见到（或上一次已过期）：调用方拿到了这次的处理权 */
        FIRST,
        /** 上一次还在处理中，结果未知。**上游必须等，不能当成已完成** */
        IN_FLIGHT,
        /** 上一次已经处理完了（调过 {@link #markDone}） */
        DONE
    }

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
     *
     * done 是"这件事做完了没有"。它不能合进时间戳里表达：
     * 同一个键"刚进门"和"刚干完"都很新，靠新旧分不出来，而这两个状态对上游
     * 意味着相反的答案（等一会儿再来 / 可以删了）。
     */
    private static final class Seen {
        private final long at;
        private final long ttl;
        private final boolean done;

        private Seen(long at, long ttl, boolean done) {
            this.at = at;
            this.ttl = ttl;
            this.done = done;
        }
    }

    private final Map<String, Seen> seen = new ConcurrentHashMap<>();

    /** 用默认有效期（30 分钟）登记，用于 Webhook 投递去重 */
    public boolean firstTime(String key) {
        return firstTime(key, DEFAULT_TTL_MILLIS);
    }

    /**
     * 两态版登记，只回答"这次该不该做"。
     *
     * 给卡片点击那一路用（它没有"处理中"这个状态）。判定逻辑和三态版的
     * {@link #claim} 完全一致，所以返回 true 就等于 claim 拿到 FIRST——
     * 不能在这里另写一套判断，两套迟早分叉。
     *
     * @param ttlMillis 有效期，传 0 或负数则用默认值
     * @return true 表示第一次见到（或上一次已过期），可以继续处理；false 表示重复请求
     */
    public boolean firstTime(String key, long ttlMillis) {
        if (claim(key, ttlMillis) == Claim.FIRST) {
            return true;
        }
        log.warn("重复请求已忽略: {}", key);
        return false;
    }

    /** 用默认有效期（30 分钟）做三态登记，用于 Webhook 投递去重 */
    public Claim claim(String key) {
        return claim(key, DEFAULT_TTL_MILLIS);
    }

    /**
     * 三态版登记：原子地"判定 + 占坑"，一次搞定。
     *
     * 为什么必须原子：判定和占坑分开写，并发下两个线程会同时判成"第一次"，
     * 于是同一份报文被处理两遍、发两张卡。用 compute 是为了让"看完旧记录再决定
     * 写不写"这件事不可分割。
     *
     * 注意**拿到 FIRST 之后必须负责收尾**：成功走 {@link #markDone}，
     * 确定没成功走 {@link #forget}。既不标完成也不撤销的话，这个键会一直停在
     * "处理中"，同一条投递再来就永远被判成 IN_FLIGHT，消息卡死在这里。
     */
    public Claim claim(String key, long ttlMillis) {
        long now = System.currentTimeMillis();
        long ttl = ttlMillis <= 0 ? DEFAULT_TTL_MILLIS : ttlMillis;
        Claim[] verdict = {Claim.FIRST};

        seen.compute(key, (k, prev) -> {
            if (prev == null || now - prev.at > prev.ttl) {
                verdict[0] = Claim.FIRST;
                // 占坑：先记成"处理中"，做完再调 markDone
                return new Seen(now, ttl, false);
            }
            verdict[0] = prev.done ? Claim.DONE : Claim.IN_FLIGHT;
            return prev;
        });

        if (verdict[0] == Claim.FIRST) {
            cleanIfNeeded(now);
        }
        return verdict[0];
    }

    /**
     * 把这条登记标成"处理完了"。
     *
     * 有效期（at）同时刷新成当前时间，重新开始计时。不刷新会有个隐蔽的洞：
     * 一条处理了 25 分钟的消息，门会在它"进门"30 分钟后自己打开，
     * 那时上游再重投一次，就又能拿到 FIRST、再发一张卡。
     * 从"完成"开始算，重投窗口才是从处理结束算起的完整 30 分钟。
     *
     * 键不存在（被 forget 撤过、或从未登记）时也照常登记成 done：
     * 这种调用说明"这件事确实做完了"，留着记录比丢掉安全。
     */
    public void markDone(String key) {
        long now = System.currentTimeMillis();
        seen.compute(key, (k, prev) -> new Seen(now,
                prev == null ? DEFAULT_TTL_MILLIS : prev.ttl, true));
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
