package com.yiyu.mergebot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轮询配置。
 *
 * 为什么需要轮询：云效的 Webhook 只能往公网 URL 推送，你的服务如果在内网，
 * 就收不到"有人提了合并请求"这个事件。轮询把这个方向反过来——服务主动去问云效。
 *
 * 好处是**一个公网端口都不需要开**：飞书那边用长连接（服务主动外连），
 * 云效这边服务主动去查，两边都是出站请求。
 * 代价是延迟一个轮询周期（默认 60 秒），周末场景完全无感。
 */
@Data
@ConfigurationProperties(prefix = "mergebot.poll")
public class PollProperties {

    /** 总开关。没有公网入口时打开它，整条链路就能自己转起来 */
    private boolean enabled = false;

    /** 轮询间隔，毫秒。60 秒够用；调太小会浪费云效接口配额 */
    private long intervalMs = 60_000;

    /**
     * 要监控的仓库 ID 列表。不是仓库名，是 URL 里那串数字。
     *
     * 从哪拿：打开云效任意一个合并请求，URL 形如
     *   .../repositories/2813489/changeRequests/12
     * 中间那串数字就是仓库 ID。
     */
    private List<String> repoIds = new ArrayList<>();

    /**
     * 只关心合到这些目标分支的请求。
     *
     * 不填等于全部。填 ["test"] 就只处理合到 test 的，
     * 合到 prod / master 的完全不理——那些本来也不该在周末随手点。
     *
     * 注意：这个过滤依赖云效列表接口返回 targetBranch 字段。
     * 万一某个版本没返回，代码会"放行"而不是"丢弃"，宁可多发一张卡也不漏。
     */
    private List<String> targetBranches = new ArrayList<>();

    /** 仓库 ID -> 显示名，卡片上好看一点；不配就直接显示 ID */
    private Map<String, String> repoNames = new LinkedHashMap<>();
}
