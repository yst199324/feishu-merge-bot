package com.yiyu.mergebot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 云效 Codeup 侧配置。
 */
@Data
@ConfigurationProperties(prefix = "yunxiao")
public class YunxiaoProperties {

    /** 服务接入点，例如 https://openapi-rdc.aliyuncs.com */
    private String domain = "https://openapi-rdc.aliyuncs.com";

    /**
     * 云效网页版地址，用来拼合并请求的可点击链接。
     *
     * 注意它和 domain **不是一回事**：domain 是给程序调接口用的
     * （openapi-rdc.aliyuncs.com），webBase 是给人用浏览器打开的
     * （codeup.aliyun.com）。拼错了卡片上的链接点开是 404。
     */
    private String webBase = "https://codeup.aliyun.com";

    /** 个人访问令牌 x-yunxiao-token，形如 pt-xxx */
    private String token;

    /**
     * 组织 ID。中心版接口路径里带 organizations/{orgId}，Region 版不带。
     * 留空走 Region 版路径。
     */
    private String orgId;

    /** ff-only / no-fast-forward / squash / rebase */
    private String mergeType = "no-fast-forward";

    /** 合并后是否删除源分支 */
    private boolean removeSourceBranch = false;

    /** 云效 Webhook 里配置的 Secret Token，接收端校验来源用；留空则服务不启动 */
    private String webhookToken;

    /**
     * 仓库名 -> 仓库 ID 的映射，只给流水线那条路用。
     *
     * 流水线的内置变量里没有仓库 ID，只有分支名，所以它传过来的是仓库名，
     * 这里换成 ID 才能调云效接口。例：
     *   repos:
     *     store-web-new: "2813489"
     *
     * 只走代码库 Webhook（/webhook/yunxiao）的话，这一项可以不配。
     */
    private Map<String, String> repos = new LinkedHashMap<>();
}
