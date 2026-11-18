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
     * 群成员判断结果的缓存秒数。
     * 只缓存"确认是成员"的结论，不缓存否定结论——刚被拉进群的人不用等缓存过期。
     * 填 0 表示每次都查接口。
     */
    private int groupMemberCacheSeconds = 300;
}
