package com.yiyu.mergebot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 飞书侧配置。
 */
@Data
@ConfigurationProperties(prefix = "feishu")
public class FeishuProperties {

    /** 应用 App ID，形如 cli_xxx */
    private String appId;

    /** 应用 App Secret */
    private String appSecret;

    /** 接收审批卡片的群 chat_id，形如 oc_xxx */
    private String chatId;

    /**
     * 允许点按钮的人的 open_id 白名单，形如 ou_xxx。
     * 空列表本身不会放行任何人，需要配合 allowGroupMembers 才有放行来源。
     */
    private List<String> allowedOpenIds = new ArrayList<>();

    /**
     * 除了白名单之外，是否允许"本群成员"也能点按钮。
     *
     * 打开后就不需要维护名单了：谁进群谁就能点，谁退群自动失效（最迟一个缓存周期）。
     * 代价是每次判断要调一次飞书接口，并且应用必须有"查看群成员 / 获取群组信息"权限。
     *
     * 为什么这个设计比"白名单全量维护"划算：
     * 卡片是可以被转发的，所以校验"你在不在这个群"，比校验"你有没有拿到这张卡"更贴近真实意图。
     *
     * 默认 true。接口调不通时一律判为"不是成员"，是拒绝而不是放行，
     * 所以就算权限没配好，也只会点不动，不会造成越权。
     */
    private boolean allowGroupMembers = true;

    /**
     * 群成员名单的缓存秒数。
     *
     * 实现是**整批缓存全量成员名单**，不是按人缓存"是/不是成员"的结论：
     *   - 名单里有的人直接放行，不用再打接口；
     *   - 名单里没有的人会强制刷新一次再判断，所以刚被拉进群的人不用等缓存过期；
     *   - 拉名单失败时沿用上一份，只有从来没成功拿到过才判为"不是成员"。
     *
     * 退群的人最迟一个缓存周期后就点不动了（期限到了会重新拉一次）。
     * 填 0 表示每次判断都重新拉。
     */
    private int groupMemberCacheSeconds = 300;
}
