package com.yiyu.mergebot.yunxiao;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

/**
 * 合并请求的只读视图，只抽卡片上要显示的字段。
 * 字段名对照云效 GetChangeRequest 接口的返回。
 */
@Getter
public class MrView {

    private final String title;
    /** 合并请求说明。提交人写的"这个改动是干什么的"，审核的人最先想看的就是它 */
    private final String description;
    private final String sourceBranch;
    private final String targetBranch;
    private final String authorName;
    private final String status;
    private final String conflictCheckStatus;
    private final boolean allRequirementsPass;
    private final String detailUrl;
    private final int ahead;
    private final int behind;
    private final String updatedAt;
    /** 发起时间。卡片上要显示"什么时候提的"，注意它和 updatedAt 不是一个东西 */
    private final String createdAt;

    private MrView(JsonNode n, String statusFallback) {
        this.title = text(n, "title");
        this.description = text(n, "description");
        this.sourceBranch = text(n, "sourceBranch");
        this.targetBranch = text(n, "targetBranch");
        this.authorName = n.path("author").path("name").asText("");
        String s = normalizeStatus(firstText(n, "status", "state", "mergeStatus"));
        this.status = s.isEmpty() && statusFallback != null ? statusFallback : s;
        this.conflictCheckStatus = text(n, "conflictCheckStatus");
        this.allRequirementsPass = n.path("allRequirementsPass").asBoolean(false);
        this.detailUrl = text(n, "detailUrl");
        this.ahead = n.path("ahead").asInt(0);
        this.behind = n.path("behind").asInt(0);
        this.updatedAt = firstText(n, "updatedAt", "updateTime", "createdAt");
        this.createdAt = firstText(n, "createdAt", "created_at", "createTime", "gmtCreate");
    }

    /**
     * 把云效两套接口的状态值统一成一种写法。
     *
     * 为什么要统一：云效**列表**接口（ListChangeRequests）用 `state`，取值 opened/merged/closed；
     * **详情**接口（GetChangeRequest）用 `status`，取值 TO_BE_MERGED/MERGED/CLOSED。
     * 同一件事两个字段名、两套枚举值，不统一的话判断"能不能合并"必然漏。
     *
     * 统一后一律用详情接口的写法（TO_BE_MERGED 等），
     * 因为按钮回调、卡片文案都按这套值写的，改动面最小。
     */
    private static String normalizeStatus(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        switch (raw.toLowerCase()) {
            case "opened":
            case "to_be_merged":
                return "TO_BE_MERGED";
            case "merged":
                return "MERGED";
            case "closed":
                return "CLOSED";
            case "locked":
                return "LOCKED";
            case "draft":
                return "DRAFT";
            default:
                // 认识不了就原样保留，卡片上至少能看到云效到底返回了什么
                return raw;
        }
    }

    private static String firstText(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = text(n, f);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    /**
     * 内容指纹。轮询时用它判断"这个合并请求有没有变化过"：
     * 变了才重新发卡片，没变就跳过，避免每轮都刷屏。
     *
     * 四个字段任一变化都算变化——代码推了新提交（ahead 变）、
     * 目标分支前进了（behind 变）、改了标题、或者云效记录了新的更新时间。
     */
    public String fingerprint() {
        return sourceBranch + "|" + title + "|" + ahead + "|" + behind + "|" + updatedAt;
    }

    public static MrView of(JsonNode n) {
        return new MrView(n == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : n, null);
    }

    /**
     * 带状态兜底的构造。
     *
     * 场景：列表接口已经按 state=opened 过滤过了，但某些版本的返回体里
     * 压根不带状态字段。这时我们**确定**这条一定是待合并，用兜底值补上，
     * 否则 mergeable() 判 false，卡片会显示成"不能合并"。
     */
    public static MrView of(JsonNode n, String statusFallback) {
        return new MrView(n == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : n,
                statusFallback);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    /** 是否还处于"可以合并"的状态 */
    public boolean mergeable() {
        return "TO_BE_MERGED".equals(status);
    }

    public boolean hasConflict() {
        return "HAS_CONFLICT".equals(conflictCheckStatus);
    }

    /** 冲突状态的中文说明 */
    public String conflictText() {
        switch (conflictCheckStatus == null ? "" : conflictCheckStatus) {
            case "NO_CONFLICT":
                return "无";
            case "HAS_CONFLICT":
                return "有冲突，需提交人自行解决";
            case "CHECKING":
                return "检测中";
            case "FAILED":
                return "检测失败";
            default:
                return "未知";
        }
    }

    /** 卡点是否全部通过的中文说明 */
    public String requirementsText() {
        return allRequirementsPass ? "全部通过" : "未全部通过（评审/流水线等）";
    }

    /** 分支展示文本，形如 `dev订单 -> test`。日志、审计、卡片都用这一个格式，避免各处各拼一套 */
    public String branchText() {
        return blankTo(sourceBranch, "未知") + " -> " + blankTo(targetBranch, "未知");
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isEmpty() ? fallback : s;
    }
}
