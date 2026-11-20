package com.yiyu.mergebot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiyu.mergebot.config.FeishuProperties;
import com.yiyu.mergebot.feishu.CardFactory;
import com.yiyu.mergebot.feishu.FeishuMessageService;
import com.yiyu.mergebot.support.IdempotentStore;
import com.yiyu.mergebot.yunxiao.CommitItem;
import com.yiyu.mergebot.yunxiao.MrView;
import com.yiyu.mergebot.yunxiao.YunxiaoClient;
import com.yiyu.mergebot.yunxiao.YunxiaoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 云效报文的业务处理：判断该不该发卡、组装卡片、发出去。
 *
 * 从 {@code YunxiaoWebhookController} 里整段搬出来的，原因只有一个：
 * 这件事现在有两个入口要共用——云效直连的 {@code /webhook/yunxiao}
 * 和监听服务转发的 {@code /internal/yunxiao}。两个入口的差别只在
 * "怎么验来源"和"返回什么状态码"，业务一模一样，不能复制一份。
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
@Service
public class WebhookDispatchService {

    /**
     * 只有这些动作需要打扰你：新建、重新打开。更新（推新代码）不重复提醒。
     * 老版报文没有 action 字段，只能用 state 的 opened/reopened，两种写法都收。
     */
    private static final Set<String> NOTIFY_ACTIONS = new HashSet<>(Arrays.asList(
            "open", "reopen", "opened", "reopened"));

    private final FeishuProperties feishuProps;
    private final YunxiaoClient yunxiaoClient;
    private final CardFactory cardFactory;
    private final FeishuMessageService messageService;
    private final IdempotentStore idempotent;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 提交内容是否优先用报文里的 last_commit。
     *
     * 默认开：报文已经带了最后一条提交，没必要再调一次「比较两个分支」接口。
     * 想看到完整的提交列表才设 false——代价是多一次接口调用，
     * 且令牌必须额外勾「代码管理 → 代码比较 → 只读」。
     */
    @Value("${mergebot.use-payload-commit:true}")
    private boolean usePayloadCommit;

    public WebhookDispatchService(FeishuProperties feishuProps,
                                  YunxiaoClient yunxiaoClient,
                                  CardFactory cardFactory,
                                  FeishuMessageService messageService,
                                  IdempotentStore idempotent) {
        this.feishuProps = feishuProps;
        this.yunxiaoClient = yunxiaoClient;
        this.cardFactory = cardFactory;
        this.messageService = messageService;
        this.idempotent = idempotent;
    }

    /**
     * 判断这份报文要不要处理，要处理的才去重、发卡。
     *
     * 结论一律用 {@link DispatchResult} 表达，不抛异常给入口去猜：
     * 入口一猜就会猜错（发飞书卡片失败抛的是 {@link IllegalStateException}，
     * 不在这套云效异常分类里，按类别猜会把它误判成"永久失败"直接丢掉，
     * 其实重试一次就好了）。所以这里把"能不能重试"当场定下来。
     *
     * @param raw        报文原文，只用于打日志和错误提示
     * @param deliveryId 云效投递编号，去重键的唯一组成
     */
    public DispatchResult dispatch(String raw, String deliveryId) {
        // 空编号会把去重键拼成 "yx:dlv:null"，所有请求共用这一个键，
        // 30 分钟内除第一条外全部被判成重复、静默丢弃——这是本项目最怕的故障形态。
        // 两个入口都已在门口拦过（缺头直接 400），这里再判一次是为了让
        // "将来新增调用方忘了校验"在开发和测试阶段就炸出来，而不是上线后
        // 表现成"卡片偶尔不发、日志一切正常"。注意 String 拼 null 不会抛异常，
        // 只会默默拼出 "null"，靠编译器和运行时不报错。
        if (!StringUtils.hasText(deliveryId)) {
            if (StringUtils.hasText(raw)) {
                log.error("投递编号为空，拒绝处理（会拼出共用键 yx:dlv:null）");
            } else {
                // 监听服务启动时会发一次"空报文、不带编号"的可达性探测。
                // 这是设计好的行为，不是故障，所以记 info 而不是 error——
                // 每次重启都刷一条 ERROR，久了大家就不看 ERROR 了，真的丢编号时反而没人注意
                log.info("收到空报文且没有投递编号，按可达性探测处理，返回永久失败");
            }
            return DispatchResult.permanentFail("失败：投递编号为空，拒绝处理");
        }

        JsonNode root = parse(raw);
        if (root == null) {
            // 报文不是 JSON：重投多少次都是同样一段字节，属于永久失败
            return DispatchResult.permanentFail("失败：报文不是合法 JSON，解析不了");
        }

        DispatchResult ignorable = ignoreReason(root);
        if (ignorable != null) {
            return ignorable;
        }

        JsonNode attrs = root.path("object_attributes");
        String repoId = repoIdOf(root, attrs);
        String localId = localIdOf(attrs);
        if (!StringUtils.hasText(repoId) || !StringUtils.hasText(localId)) {
            log.warn("报文里取不到 repoId / localId，请按真实报文调整字段映射。报文: {}", raw);
            return DispatchResult.permanentFail("失败：取不到 repoId=" + repoId + " localId=" + localId
                    + "。看报文里的 object_attributes 再核对字段映射");
        }

        // 投递级去重：同一个投递编号就是同一份报文，重推多少次都只处理一次。
        //
        // ⚠️ 键里**只能有投递编号**，不拼动作名。之前是"仓库+编号+动作名"，
        // 结果同一个请求"关掉-重开-再关掉-再重开"时，两次 reopen 的键完全一样，
        // 第二次重开的卡片被当成重复静默丢掉——审批链直接断在那里，而且日志看着一切正常。
        //
        // ⚠️ 必须是三态，不能用"第一次/重复"两态：
        // 处理需要几十秒（最多三次云效调用），上游可能等不及就重推了。两态会把
        // "还在处理中"和"已经处理完"都答成"已完成"，上游据此删掉消息；
        // 而第一次处理万一最后失败，这条消息就静默消失。
        String dedupKey = dedupKeyOf(deliveryId);
        IdempotentStore.Claim claim = idempotent.claim(dedupKey);
        if (claim == IdempotentStore.Claim.IN_FLIGHT) {
            // info 不用 warn：这是"两条投递撞在一起"，不是故障
            log.info("该投递上一次还在处理中，本次不重复执行 delivery={} repo={} mr={}",
                    deliveryId, repoId, localId);
            return DispatchResult.inFlight("处理中：这条投递还在处理，本次没有重复执行 delivery=" + deliveryId);
        }
        if (claim == IdempotentStore.Claim.DONE) {
            log.info("该投递已处理过，跳过 delivery={} repo={} mr={}", deliveryId, repoId, localId);
            return DispatchResult.ignored("跳过：这条投递已经发过卡片了 delivery=" + deliveryId);
        }

        DispatchResult result = pushCardAndClassify(root, attrs, repoId, localId, deliveryId, dedupKey);
        if (result.getOutcome() == DispatchResult.Outcome.DONE) {
            // 关门的时机放在这里，是因为它同时覆盖了两种"处理完了"：
            //  1. 真的发了卡片；
            //  2. 发卡前状态复查发现"状态不是待合并"而跳过——这条也确实不用再处理了。
            // 两种都返回 DONE，所以按结论判断比按代码路径判断更不容易漏。
            // 失败路径不标完成：pushCardAndClassify 里已经 forget 掉，重投会重新走一遍。
            idempotent.markDone(dedupKey);
        }
        return result;
    }

    /**
     * 投递去重键。
     *
     * 抽成方法是为了让测试能精确模拟"上一次还在处理中"，
     * 不必在测试里手拼 "yx:dlv:" 这个前缀——前缀一改，测试就该跟着改。
     */
    static String dedupKeyOf(String deliveryId) {
        return "yx:dlv:" + deliveryId;
    }

    /** 报文解析成树；不是合法 JSON 就返回 null，由调用方判成永久失败 */
    private JsonNode parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            log.warn("云效报文不是合法 JSON，无法解析：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 这份报文是不是"收到了但要忽略"。
     *
     * @return 需要忽略时返回对应的结论（IGNORED）；要正常处理返回 null
     */
    private DispatchResult ignoreReason(JsonNode root) {
        String kind = text(root, "object_kind", "objectKind");
        if (!"merge_request".equals(kind)) {
            // 用 info 不用 debug：云效"测试"按钮发的就是推送事件，
            // 日志里能看见"忽略"才证明链路通了
            log.info("忽略非合并请求事件: object_kind={}", kind);
            return DispatchResult.ignored("忽略：不是合并请求事件，object_kind=" + kind);
        }
        String action = text(root.path("object_attributes"), "action", "state");
        if (!NOTIFY_ACTIONS.contains(action)) {
            log.info("忽略合并请求动作: action={}（只处理 open/reopen）", action);
            return DispatchResult.ignored("忽略：action=" + action
                    + "（只处理 open/reopen，update 不重复提醒）");
        }
        return null;
    }

    /**
     * 发卡，并把结果归成"能不能重试"。
     *
     * @param dedupKey 发卡失败时要撤销的登记键
     */
    private DispatchResult pushCardAndClassify(JsonNode root, JsonNode attrs, String repoId, String localId,
                                               String deliveryId, String dedupKey) {
        try {
            return pushCard(root, attrs, repoId, localId, deliveryId);
        } catch (YunxiaoException e) {
            // 发卡失败必须撤销登记：否则云效后台点「重新投递」会被当成重复请求挡掉，
            // 这张卡片再也没有补发的机会。撤销后重投会重新走一遍，最坏是多一张卡。
            idempotent.forget(dedupKey);
            if (e.isAuth()) {
                // 令牌不会自己变好，重试无用。这一行必须单独打出来：
                // 混在"处理失败"里，运维看不出根因，会先去查网络和端口
                log.error("云效凭据失效，请更换 x-yunxiao-token 后手工补发。repo={} mr={} 投递={}：{}",
                        repoId, localId, deliveryId, e.getMessage());
                return DispatchResult.permanentFail("失败：云效凭据失效，重试无用，换完令牌后手工补发。"
                        + e.getMessage());
            }
            log.error("发卡失败（云效接口异常）repo={} mr={} 投递={}", repoId, localId, deliveryId, e);
            return DispatchResult.retryable("失败：处理这条投递时出错：" + e.getMessage());
        } catch (Exception e) {
            // 走到这里的典型是发飞书卡片失败（IllegalStateException）。
            // 它不在云效异常分类里，但重试一次往往就好了，所以一律当可重试
            idempotent.forget(dedupKey);
            log.error("发卡失败（非云效异常）repo={} mr={} 投递={}", repoId, localId, deliveryId, e);
            return DispatchResult.retryable("失败：处理这条投递时出错：" + e.getMessage());
        }
    }

    /**
     * 组装并发送审批卡片。
     *
     * 从 dispatch 里拆出来，是为了让两件事各归各位：
     * dispatch 只判断"这份报文该不该处理"，这里只负责"怎么把卡片发出去"。
     * 混在一起时方法接近 90 行，排查失败时看不出是判断错了还是发送错了。
     *
     * @param deliveryId 只用于日志，方便和云效后台的投递记录逐条对号
     */
    private DispatchResult pushCard(JsonNode root, JsonNode attrs, String repoId, String localId,
                                    String deliveryId) {
        String repoName = resolveRepoName(root, attrs, repoId);

        // 以云效接口查到的为准，不信 Webhook 报文里的状态
        MrView mr = yunxiaoClient.getChangeRequest(repoId, localId);

        // 发卡前先看一眼状态：积压回放时，一大半请求早就被人处理掉了，
        // 再发卡只会刷屏。判断放在这里（拿到详情之后、查提交和链接之前），
        // 状态不对就立刻返回，省掉后面两次无谓的云效接口调用。
        DispatchResult skipped = skipUnlessMergeable(mr, repoId, localId);
        if (skipped != null) {
            return skipped;
        }

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

        String webUrl = webUrlOf(attrs, repoName, localId, mr);
        String card = cardFactory.approvalCard(repoName, repoId, localId, mr, commits, webUrl, latestOnly);
        String messageId = messageService.sendCard(feishuProps.getChatId(), card);
        log.info("已推送审批卡片 repo={} mr={} 投递={} 提交数={} 来源={} messageId={}",
                repoId, localId, deliveryId, commits.size(),
                latestOnly ? "报文 last_commit" : "比较分支接口", messageId);
        return DispatchResult.done("成功：已发卡片 repo=" + repoId + " mr=" + localId + " 投递=" + deliveryId
                + " 提交数=" + commits.size()
                + " 提交来源=" + (latestOnly ? "报文" : "接口") + " messageId=" + messageId);
    }

    /**
     * 发卡前的状态复查：只给"待合并"的发卡（设计文档 14.1）。
     *
     * 不做会怎样：合并项目停机三小时、积压 40 条，恢复后全部发卡，
     * 而里面大部分早已被合并或关闭，群里瞬间出现 40 张没用的卡。
     *
     * ⚠️ 兜底比省事更重要：**只有明确拿到状态、且状态不是待合并，才跳过**。
     * 状态为空时一律照常发卡。原因是这个项目在字段映射上踩过坑
     * （新版报文把 iid 改成 local_id、仓库 ID 换了位置），如果哪天状态字段也变了，
     * 白名单会把"读不到状态"当成"不是待合并"，结果是所有卡片静默不发，
     * 日志里还写着"不是待合并，已跳过"，看起来完全正常——这种故障比丢一张卡难查十倍。
     * 宁可多发一张废卡（人一眼就能看见），也不能静默全停。
     *
     * @return 需要跳过时返回 DONE（"这条不需要处理了"，对云效和队列都算成功）；照常发卡返回 null
     */
    private DispatchResult skipUnlessMergeable(MrView mr, String repoId, String localId) {
        String status = mr.getStatus();
        if (!StringUtils.hasText(status) || mr.mergeable()) {
            return null;
        }
        log.info("状态不是待合并，跳过发卡 repo={} mr={} 状态={}", repoId, localId, status);
        return DispatchResult.done("跳过：当前状态 " + status + "，不是待合并");
    }

    /**
     * 仓库名：优先用云效接口查到的（带组名的完整路径），报文里通常只有短名。
     * 短名在多个项目共用一个群时容易混淆，所以能查到完整的就用完整的。
     */
    private String resolveRepoName(JsonNode root, JsonNode attrs, String repoId) {
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
        return repoName;
    }

    /**
     * 链接优先用 Webhook 报文里云效自己给的 url，比我们本地拼的准。
     * 这个地址会一路透传到结果卡的「去云效查看」按钮（走按钮回调值）。
     * 老版报文没这个字段时才回落到本地拼接。
     */
    private String webUrlOf(JsonNode attrs, String repoName, String localId, MrView mr) {
        String webUrl = text(attrs, "url", "web_url", "mergeRequestUrl");
        if (!StringUtils.hasText(webUrl)) {
            webUrl = yunxiaoClient.webUrlFor(repoName, localId, mr);
        }
        return webUrl;
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
}
