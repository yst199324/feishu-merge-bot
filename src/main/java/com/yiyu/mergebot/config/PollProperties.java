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
     * 要监控的仓库 ID 列表。不是仓库名，是那串数字。
     *
     * 从哪拿：新版云效页面已经不显示仓库 ID 了，用服务自带的排错接口反查（需要
     * `mergebot.test-endpoints=true`）：
     *   curl "127.0.0.1:18123/test/repos?path=组名/仓库名"
     * 或者看云效 Webhook 报文里的 project_id。
     */
    private List<String> repoIds = new ArrayList<>();

    /**
     * 只关心合到这些目标分支的请求。
     *
     * 不填 = 全部都要。填 ["test"] 就只处理合到 test 的。
     *
     * 注意这个过滤依赖接口返回目标分支字段：**字段缺失时按"放行"处理**，
     * 宁可多发一张卡，也不因为字段缺失把真请求漏掉。
     * 所以它是"减少打扰"的过滤器，不是安全边界——真正要不要合由人看卡片决定。
     */
    private List<String> targetBranches = new ArrayList<>();

    /** 仓库 ID -> 显示名，卡片上好看一点；不配就直接显示 ID */
    private Map<String, String> repoNames = new LinkedHashMap<>();
}
