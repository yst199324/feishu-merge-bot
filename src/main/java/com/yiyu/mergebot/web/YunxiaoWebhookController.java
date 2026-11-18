package com.yiyu.mergebot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiyu.mergebot.config.FeishuProperties;
import com.yiyu.mergebot.feishu.CardFactory;
import com.yiyu.mergebot.feishu.FeishuMessageService;
import com.yiyu.mergebot.support.IdempotentStore;
import com.yiyu.mergebot.support.WebhookDebugStore;
import com.yiyu.mergebot.support.WebhookGuard;
import com.yiyu.mergebot.yunxiao.CommitItem;
import com.yiyu.mergebot.yunxiao.MrView;
import com.yiyu.mergebot.yunxiao.YunxiaoClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 接收云效代码库 Webhook（推荐用这条，比流水线中继少一跳）。
 *
 * 云效只会往一个公网 URL 发 POST，不支持长连接，
 * 所以这个接口必须能被外网访问。云效的回调出口 IP 是 47.98.116.130 和 47.111.186.29，
 * 建议在 Nginx 上也加一层白名单，和 X-Codeup-Token 形成两道防护。
 *
 * ⚠️ 云效报文有**两套结构**，字段名完全不同，必须同时兼容：
 *
 *   老版（GitLab 风格）                    新版（带 "version":"new"）
 *   project.id                            object_attributes.target_project_id
 *   project.name                          repository.name
 *   object_attributes.iid                 object_attributes.local_id
 *   没有 action，靠 state=opened 判断      object_attributes.action = open/update/...
 *
 * 只认老版字段的话，新版报文会取不到仓库 ID 和合并请求编号，
 * 而接口仍然返回 200——云效看到"推送成功"，实际一张卡片都没发。
 * 这个坑踩过，别再简化回去。
 */
@Slf4j
@RestController
public class YunxiaoWebhookController {

    private final FeishuProperties feishuProps;
    private final YunxiaoClient yunxiaoClient;
    private final CardFactory cardFactory;
    private final FeishuMessageService messageService;
    private final WebhookGuard guard;
    private final IdempotentStore idempotent;
    private final WebhookDebugStore debugStore;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 是否把云效报文原文整段打进日志。默认开，排错时全靠它 */
    @Value("${mergebot.log-webhook-raw:true}")
    private boolean logRawWebhook;

    /**
     * 提交内容是否优先用报文里的 last_commit。
     *
     * 默认开：报文已经带了最后一条提交，没必要再调一次「比较两个分支」接口。
     * 想看到完整的提交列表才设 false——代价是多一次接口调用，
     * 且令牌必须额外勾「代码管理 → 代码比较 → 只读」。
     */
    @Value("${mergebot.use-payload-commit:true}")
    private boolean usePayloadCommit;

    /**
     * 只有这些动作需要打扰你：新建、重新打开。更新（推新代码）不重复提醒。
     * 老版报文没有 action 字段，只能用 state 的 opened/reopened，两种写法都收。
     */
    private static final Set<String> NOTIFY_ACTIONS = new HashSet<>(Arrays.asList(
            "open", "reopen", "opened", "reopened"));

    public YunxiaoWebhookController(FeishuProperties feishuProps,
                                    YunxiaoClient yunxiaoClient,
                                    CardFactory cardFactory,
                                    FeishuMessageService messageService,
                                    WebhookGuard guard,
                                    IdempotentStore idempotent,
                                    WebhookDebugStore debugStore) {
        this.feishuProps = feishuProps;
        this.yunxiaoClient = yunxiaoClient;
        this.cardFactory = cardFactory;
        this.messageService = messageService;
        this.guard = guard;
        this.idempotent = idempotent;
        this.debugStore = debugStore;
    }

    @PostMapping("/webhook/yunxiao")
    public ResponseEntity<String> onEvent(@RequestBody String raw,
                                          @RequestHeader HttpHeaders headers) {

        // 云效同时发 X-Codeup-Token 和 X-Gitlab-Token 两个头（跟报文一样有新旧两套约定），
        // 取头规则统一放在 WebhookGuard 里，两个 Webhook 入口共用，避免各写一套。
        String token = guard.tokenOf(headers);

        // 第一行就记：云效到底有没有把请求送进来、带了多少字节、判成什么事件。
        // 没有这一行的话，"什么都没打印"既可能是没收到请求，也可能是收到了但被静默忽略，
        // 两种完全相反的原因在日志里长得一模一样，排错时会走错方向。
        log.info("收到云效 webhook, 报文长度={}, 事件={}, token={}",
                raw == null ? 0 : raw.length(),
                orDefault(headers.getFirst("X-Codeup-Event"), "（未带事件头）"),
                StringUtils.hasText(token) ? "已带" : "未带");

        // 请求头全量落日志，密钥类脱敏。
        // X-Codeup-Event 决定云效认为这是哪种事件（Push Hook / Merge Request Hook），
        // X-Codeup-Delivery 用来和云效「投递记录」里的那一次推送对上号。
        // 查"云效说发了、代码没反应"时，这两项和报文原文一样关键。
        log.info(headersForLog(headers));

        // 报文原文整段落日志，不做截断、不做美化。
        // 云效的字段名会随版本变（新版把 iid 改成 local_id、仓库 ID 挪进 object_attributes），
        // 出问题时唯一可靠的东西就是"它到底发了什么"，凭记忆猜字段名会白折腾一晚上。
        // 嫌吵可以配 mergebot.log-webhook-raw=false 关掉，只留上面那行摘要。
        if (logRawWebhook) {
            log.info("云效 webhook 原文:\n{}", raw);
        }

        // 来源校验：配 Webhook 时填的 Secret Token 就是干这个的，不一致直接拒
        if (!guard.pass(token)) {
            debugStore.record(token, false, "来源校验失败：token 和 yunxiao.webhook-token 不一致", raw);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("forbidden");
        }

        // 云效要求快速响应，慢了自己会重推；业务异常吞掉，不让它等
        String note;
        try {
            note = dispatch(raw);
        } catch (Exception e) {
            log.error("处理云效事件失败", e);
            note = "处理异常：" + e.getMessage();
        }
        debugStore.record(token, true, note, raw);
        return ResponseEntity.ok("ok");
    }

    /**
     * @return 处理结果说明，写进 /test/last-webhook，方便对着看是哪一步没走通
     */
    private String dispatch(String raw) throws Exception {
        JsonNode root = mapper.readTree(raw);
        String kind = text(root, "object_kind", "objectKind");
        if (!"merge_request".equals(kind)) {
            // 用 info 不用 debug：云效"测试"按钮发的就是推送事件，日志里能看见"忽略"才证明链路通了
            log.info("忽略非合并请求事件: object_kind={}", kind);
            return "忽略：不是合并请求事件，object_kind=" + kind;
        }

        JsonNode attrs = root.path("object_attributes");
        String action = text(attrs, "action", "state");
        if (!NOTIFY_ACTIONS.contains(action)) {
            log.info("忽略合并请求动作: action={}（只处理 open/reopen）", action);
            return "忽略：action=" + action + "（只处理 open/reopen，update 不重复提醒）";
        }

        String repoId = repoIdOf(root, attrs);
        String localId = localIdOf(attrs);

        if (!StringUtils.hasText(repoId) || !StringUtils.hasText(localId)) {
            log.warn("报文里取不到 repoId / localId，请按真实报文调整字段映射。报文: {}", raw);
            return "失败：取不到 repoId=" + repoId + " localId=" + localId
                    + "。看报文里的 object_attributes 再核对字段映射";
        }

        // 云效重推、或人工重复触发时，避免同一件事发好几张卡
        if (!idempotent.firstTime("yx:" + repoId + ":" + localId + ":" + action)) {
            log.info("该事件已处理过，跳过 repo={} mr={} action={}", repoId, localId, action);
            return "跳过：这件事已经发过卡片了（幂等去重）";
        }

        // 仓库名优先用云效接口查到的（带组名的完整路径），报文里通常只有短名。
        // 短名在多个项目共用一个群时容易混淆，所以能查到完整的就用完整的。
        String repoName = payloadRepoName(root, attrs);
        if (!repoName.contains("/")) {
            String full = yunxiaoClient.getRepositoryName(repoId);
            if (StringUtils.hasText(full)) {
                repoName = full;
            }
        }
        if (!StringUtils.hasText(repoName)) {
            repoName = repoId;
        }

        // 以云效接口查到的为准，不信 Webhook 报文里的状态
        MrView mr = yunxiaoClient.getChangeRequest(repoId, localId);

        // 提交内容优先用报文里的 last_commit，省掉一次"比较两个分支"接口调用。
        // 报文只给最后一条，所以带上 latestOnly=true，卡片写「最近提交」而不是「本次提交（N 个）」——
        // 一次推了 5 个提交却只显示 1 条，写"1 个"会让人以为只改了一处就点了合并。
        List<CommitItem> commits;
        boolean latestOnly = false;
        CommitItem lastCommit = usePayloadCommit ? lastCommitOf(attrs, root) : null;
        if (lastCommit != null) {
            commits = Collections.singletonList(lastCommit);
            latestOnly = true;
        } else {
            // 报文没带（老版报文 / 配置关掉了）才回落到接口，查不到就返回空列表，不影响发卡片
            commits = yunxiaoClient.commitsFor(repoId, mr.getSourceBranch(), mr.getTargetBranch());
        }

        // 链接优先用 Webhook 报文里云效自己给的 url，比我们本地拼的准。
        // 这个地址会一路透传到结果卡的「去云效查看」按钮（走按钮回调值）。
        // 老版报文没这个字段时才回落到本地拼接。
        String webUrl = text(attrs, "url", "web_url", "mergeRequestUrl");
        if (!StringUtils.hasText(webUrl)) {
            webUrl = yunxiaoClient.webUrlFor(repoName, localId, mr);
        }

        String card = cardFactory.approvalCard(repoName, repoId, localId, mr, commits,
                webUrl, latestOnly);
        String messageId = messageService.sendCard(feishuProps.getChatId(), card);
        log.info("已推送审批卡片 repo={} mr={} 提交数={} 来源={} messageId={}",
                repoId, localId, commits.size(), latestOnly ? "报文 last_commit" : "比较分支接口", messageId);
        return "成功：已发卡片 repo=" + repoId + " mr=" + localId + " 提交数=" + commits.size()
                + " 提交来源=" + (latestOnly ? "报文" : "接口") + " messageId=" + messageId;
    }

    /**
     * 取报文里的 last_commit（最后一条提交）。
     *
     * 有这个字段就不用再调"比较两个分支"接口——省钱、少一次失败可能，
     * 也不用再给令牌勾「代码比较」权限。
     *
     * 层级做兼容：object_attributes.last_commit 和顶层 last_commit 都试，
     * 提交号三种写法（id / sha / commit_id）都认。
     */
    private CommitItem lastCommitOf(JsonNode attrs, JsonNode root) {
        JsonNode c = attrs.path("last_commit");
        if (!c.isObject()) {
            c = root.path("last_commit");
        }
        if (!c.isObject()) {
            return null;
        }
        String id = firstText(c, "id", "sha", "commit_id");
        if (!StringUtils.hasText(id)) {
            return null;
        }
        return new CommitItem(id,
                firstText(c, "message", "title"),
                firstText(c.path("author"), "name", "username"));
    }

    /**
     * 取仓库 ID。两套报文三种位置，按可靠性从高到低试：
     *   1. 新版：object_attributes.target_project_id（合并请求挂在目标仓库下）
     *   2. 老版：project.id
     *   3. 兜底：任何一层的 project_id
     */
    private String repoIdOf(JsonNode root, JsonNode attrs) {
        JsonNode project = root.path("project");
        return firstNonEmpty(
                firstText(project, "id", "projectId", "project_id"),
                firstText(attrs, "target_project_id", "project_id"),
                firstText(root, "project_id"));
    }

    /**
     * 取合并请求编号（接口要的是"仓库内编号"，不是全局 id）。
     *   老版：object_attributes.iid
     *   新版：object_attributes.local_id
     * **不要用 id**：那是云效的全局自增 id，拿去调接口会查到别人的请求。
     */
    private String localIdOf(JsonNode attrs) {
        return firstNonEmpty(firstText(attrs, "iid"), firstText(attrs, "local_id"));
    }

    /** 报文里带的仓库名。新版没有 project 对象，名字在顶层 repository 里 */
    private String payloadRepoName(JsonNode root, JsonNode attrs) {
        return firstNonEmpty(
                firstText(root.path("project"), "pathWithNamespace", "path_with_namespace", "name"),
                firstText(root.path("repository"), "name"),
                firstText(attrs.path("target"), "name"),
                firstText(attrs.path("source"), "name"));
    }

    private String text(JsonNode node, String... names) {
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v != null && !v.isNull()) {
                return v.asText();
            }
        }
        return "";
    }

    /** 按顺序取第一个非空文本 */
    private String firstText(JsonNode node, String... names) {
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v != null && !v.isNull()) {
                String s = v.asText();
                if (StringUtils.hasText(s)) {
                    return s;
                }
            }
        }
        return "";
    }

    private String firstNonEmpty(String... values) {
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- 请求头

    private String orDefault(String s, String fallback) {
        return StringUtils.hasText(s) ? s : fallback;
    }

    /**
     * 把所有请求头整理成一段可读日志。
     *
     * 排序后输出（TreeMap + 忽略大小写），这样同一个请求每次打印顺序一致，
     * 做日志比对时不会因为顺序变化看着像"多了/少了东西"。
     */
    private String headersForLog(HttpHeaders headers) {
        TreeMap<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> {
            String v = values == null || values.isEmpty() ? "" : String.join(",", values);
            sorted.put(name, maskSecret(name, v));
        });

        StringBuilder sb = new StringBuilder("云效 webhook 请求头（").append(sorted.size()).append(" 个）:");
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            sb.append("\n  ").append(e.getKey()).append(": ").append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * 密钥类请求头脱敏。
     *
     * X-Codeup-Token / X-Gitlab-Token 里放的就是 webhook 的 Secret，
     * 明文写进日志等于把密钥抄进了日志文件（日志还可能被收集外发）。
     * 排查时用不上全文：保留前 4 位 + 长度，足够判断"填的是不是那一个"，
     * 而"到底匹不匹配"看同一行日志里的 token 结果字段就够了。
     */
    private String maskSecret(String name, String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String lower = name == null ? "" : name.toLowerCase();
        boolean secret = lower.contains("token") || lower.contains("secret")
                || lower.contains("authorization") || lower.contains("signature")
                || lower.contains("cookie") || lower.contains("passwd");
        if (!secret) {
            return value;
        }
        if (value.length() <= 8) {
            return "******（长度 " + value.length() + "）";
        }
        return value.substring(0, 4) + "******（长度 " + value.length() + "）";
    }
}
