package com.yiyu.mergebot.support;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * 一次卡片操作的可回溯记录 + 过程日志。
 *
 * 两个职责合在一个类里，是为了**让"打日志"这件事不可能忘**：
 * 每次点击进来先建一个对象，后面各步骤直接 `audit.step(...)`，
 * 不用到处传 logger、也不会出现"某个分支忘了记"。
 *
 * 追踪号（trace）：形如 `a1b2c3/36`（卡片消息编号末 6 位 / 合并请求编号）。
 * 日志每一行都带它，一次点击从收到到出结果能直接 grep 串起来；
 * 多条同时发生也不会看串。审计文件里也带这个号，能互相印证。
 *
 * 打日志的克制原则：**只在关键步骤和失败点记**。
 * 一次成功合并大约 5 条，一次失败 2~3 条；不逐行打参数、不打无关中间态。
 */
@Slf4j
@Getter
public class AuditEntry {

    private final long startAt = System.currentTimeMillis();

    /** 追踪号，形如 `a1b2c3/36` */
    private final String trace;
    /** 动作：merge_ok / close / cancel */
    private final String action;
    /** 显示名，形如「杨书庭（676706）」；查不到人是「未知用户（676706）」 */
    private String operator;
    /** 完整 open_id，审计时用来和飞书后台对账 */
    private final String openId;
    private final String chatId;
    private final String messageId;

    private final String project;
    private final String repoId;
    private final String mrId;

    /** 待审批卡上的「最近提交」那一行，由按钮值带回来，结果卡要原样显示 */
    private final String latestCommit;

    private String title = "";
    private String branches = "";
    private String result = "未完成";
    private String detail = "";

    public AuditEntry(String action, String operator, String openId, String chatId, String messageId,
                      String project, String repoId, String mrId, String latestCommit) {
        this.action = nz(action);
        this.operator = nz(operator);
        this.openId = nz(openId);
        this.chatId = nz(chatId);
        this.messageId = nz(messageId);
        this.project = nz(project);
        this.repoId = nz(repoId);
        this.mrId = nz(mrId);
        this.latestCommit = nz(latestCommit);
        this.trace = buildTrace(this.messageId, this.mrId);
    }

    /**
     * 追踪号的规则：有消息编号就用它末 6 位，没有就用仓库编号。
     * 后面统一跟合并请求编号，这样即使两张卡片的编号末位撞了也能区分。
     */
    private static String buildTrace(String messageId, String mrId) {
        String head = tailOf(messageId, 6);
        if (!StringUtils.hasText(head)) {
            head = "no-msg";
        }
        return head + "/" + (StringUtils.hasText(mrId) ? mrId : "?");
    }

    private static String tailOf(String s, int n) {
        if (!StringUtils.hasText(s)) {
            return "";
        }
        String t = s.trim();
        return t.length() <= n ? t : t.substring(t.length() - n);
    }

    /**
     * 补上"合了什么"：标题和分支。
     *
     * 这里刻意**不接收 MrView**——审计组件是通用的支撑类，
     * 依赖业务模型会让依赖方向反过来（支撑层指向业务层）。
     * 需要展示什么文本，由调用方从自己的模型里取好再传进来。
     */
    public AuditEntry title(String title) {
        if (!StringUtils.hasText(this.title)) {
            this.title = nz(title);
        }
        return this;
    }

    public AuditEntry branches(String branches) {
        if (!StringUtils.hasText(this.branches)) {
            this.branches = nz(branches);
        }
        return this;
    }

    /**
     * 补上操作人显示名。
     *
     * 只用于"先过了权限校验、再去查姓名"的顺序：姓名查询要打飞书接口，
     * 没通过校验的点击不该浪费那一次调用，所以先建记录、通过校验后再补名字。
     */
    public AuditEntry operator(String operator) {
        this.operator = nz(operator);
        return this;
    }

    public AuditEntry result(String result, String detail) {
        this.result = nz(result);
        this.detail = nz(detail);
        return this;
    }

    public long costMs() {
        return System.currentTimeMillis() - startAt;
    }

    // ------------------------------------------------------------ 过程日志

    /**
     * 记一个关键步骤（INFO）。
     *
     * 只在"出了问题需要知道走到哪一步"的位置调用：收到点击、通过校验、拿到锁、
     * 状态校验结论、开始调云效、拿到结果。不要每个 if 都打一条。
     */
    public void step(String name, String detail) {
        log.info("[{}] {} {}", trace, name, StringUtils.hasText(detail) ? detail : "");
    }

    /** 某一步失败/没执行（WARN）。step 名要能直接指出卡在哪 */
    public void stepFail(String name, String reason) {
        log.warn("[{}] {} 未通过：{}", trace, name, nz(reason));
    }

    /** 某一步抛异常（ERROR 带堆栈，排查时靠它定位具体代码位置） */
    public void stepError(String name, String reason, Throwable e) {
        log.error("[{}] {} 异常：{}", trace, name, nz(reason), e);
    }

    // ------------------------------------------------------------ 审计行

    /**
     * 拼成一行审计记录（不含时间，时间由 logback 的 pattern 加）。
     *
     * 含空格的字段（项目名、标题、分支、说明）加双引号，
     * 这样 `awk -F' \\| '` 切出来的每一段都能直接读；
     * 值内部的双引号换成单引号、换行压成空格，保证一行一条不会被折断。
     */
    public String line() {
        return "trace=" + trace
                + " | action=" + action
                + " | result=" + result
                + " | cost=" + costMs() + "ms"
                + " | operator=" + q(operator)
                + " | openId=" + openId
                + " | project=" + q(project)
                + " | repo=" + repoId
                + " | mr=" + mrId
                + " | title=" + q(title)
                + " | branches=" + q(branches)
                + " | chat=" + chatId
                + " | message=" + messageId
                + " | detail=" + q(detail);
    }

    /** 带引号的值：压掉换行、把内部双引号换成单引号 */
    private String q(String s) {
        String v = nz(s).replace("\r", " ").replace("\n", " ").replace("\"", "'").trim();
        return "\"" + v + "\"";
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}
