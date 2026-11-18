package com.yiyu.mergebot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiyu.mergebot.config.FeishuProperties;
import com.yiyu.mergebot.feishu.CardFactory;
import com.yiyu.mergebot.feishu.FeishuMessageService;
import com.yiyu.mergebot.poll.MergeRequestPoller;
import com.yiyu.mergebot.yunxiao.CommitItem;
import com.yiyu.mergebot.yunxiao.MrItem;
import com.yiyu.mergebot.yunxiao.MrView;
import com.yiyu.mergebot.yunxiao.RepoItem;
import com.yiyu.mergebot.yunxiao.RepoMatcher;
import com.yiyu.mergebot.yunxiao.YunxiaoClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排错用的几个小接口。
 *
 * **只在 mergebot.test-endpoints=true 时才会被加载。** 默认不加载，所以
 * 部署到公网后即使 Nginx 没挡住，这几个接口也不存在。
 * 本地调试时在 application.yml 里把它打开。
 *
 * 典型用法：
 *  1. 启动服务，控制台看到"已连接飞书长连接"；
 *  2. 浏览器打开 http://localhost:8081/test/chats 找到群 chat_id，填进 application.yml；
 *  3. 打开 http://localhost:8081/test/card 发一张测试卡片；
 *  4. 在飞书里点"合并"，看服务端日志有没有打出"收到卡片回调"。
 *     有日志 = 长连接和回调都通了，剩下就是云效参数的问题。
 */
@Slf4j
@RestController
@RequestMapping("/test")
@ConditionalOnProperty(name = "mergebot.test-endpoints", havingValue = "true")
public class TestController {

    private final FeishuMessageService messageService;
    private final FeishuProperties feishuProps;
    private final CardFactory cardFactory;
    private final YunxiaoClient yunxiao;
    private final ObjectProvider<MergeRequestPoller> poller;
    private final com.yiyu.mergebot.support.WebhookDebugStore webhookDebug;
    private final ObjectMapper mapper = new ObjectMapper();

    public TestController(FeishuMessageService messageService,
                          FeishuProperties feishuProps,
                          CardFactory cardFactory,
                          YunxiaoClient yunxiao,
                          ObjectProvider<MergeRequestPoller> poller,
                          com.yiyu.mergebot.support.WebhookDebugStore webhookDebug) {
        this.messageService = messageService;
        this.feishuProps = feishuProps;
        this.cardFactory = cardFactory;
        this.yunxiao = yunxiao;
        this.poller = poller;
        this.webhookDebug = webhookDebug;
    }

    /**
     * 看最近一次云效 Webhook 到底推了什么过来。
     *
     * 为什么需要：Webhook 那条路最坑的失败是"云效显示推送成功、但没发卡片"——
     * 因为接口无论解析成功还是失败都返回 200（云效要求快速响应）。
     * 这时云效的投递记录是绿的，日志里也只有一条 warn。
     * 这个接口把「收到的原文 + token 是否匹配 + 处理结果」摊开，
     * 一眼就能看出是报文结构变了、还是被动作过滤掉了。
     *
     * 典型结果：
     *   处理结果 = "忽略：不是合并请求事件"  → 云效那边勾错了触发事件
     *   处理结果 = "忽略：action=update"     → 只是推了新提交，本来就不该发卡
     *   处理结果 = "失败：取不到 repoId..."  → 报文结构和代码里的字段映射对不上
     *   处理结果 = "成功：已发卡片..."        → 全通了，没收到就是群或机器人问题
     *   处理结果 = "来源校验失败"            → Secret Token 和 yml 里的不一致
     */
    @GetMapping("/last-webhook")
    public Object lastWebhook() {
        Map<String, Object> v = webhookDebug.latest();
        if (v.isEmpty()) {
            v.put("hint", "还没收到过任何 Webhook。先去云效该 Webhook 上点「测试」，"
                    + "或确认 URL 里的 IP:端口 从公网真能访问到");
        }
        return v;
    }

    /**
     * 看一眼云效里现在有哪些待合并请求。
     * 这一步只查询、不调飞书，用来确认令牌、组织 ID、仓库 ID 三个值都填对了。
     */
    @GetMapping("/mrs")
    public Object openMergeRequests(@RequestParam String repo) {
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (MrItem item : yunxiao.listOpenChangeRequests(repo)) {
                MrView mr = item.getMr();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("mrId", item.getLocalId());
                m.put("title", mr.getTitle());
                m.put("source", mr.getSourceBranch());
                m.put("target", mr.getTargetBranch());
                m.put("author", mr.getAuthorName());
                m.put("status", mr.getStatus());
                m.put("conflict", mr.conflictText());
                m.put("requirements", mr.requirementsText());
                list.add(m);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("count", list.size());
            result.put("items", list);
            return result;
        } catch (Exception e) {
            log.error("查询待合并请求失败", e);
            return "查询失败：" + e.getMessage();
        }
    }

    /**
     * 排错用：把云效列表接口的**原文**摊开给你看，不做任何过滤。
     *
     * /test/mrs 返回 count:0 时用这个分辨到底是哪种情况：
     *   1. parsedTotal = 0            → 仓库真的没有合并请求
     *   2. parsedTotal > 0 但没有 TO_BE_MERGED
     *                                 → 数据拿回来了，是 status 的值和预期不一样，
     *                                   看 statusCounts 里到底叫什么，改过滤条件即可
     *   3. parsedTotal > 0 且有 TO_BE_MERGED
     *                                 → 数据没问题，那 /test/mrs 就该有数，
     *                                   说明跑的还是旧 jar
     *
     * 加 &full=true 连原文 JSON 一起返回（默认只在 parsedTotal=0 时返回原文）。
     */
    @GetMapping("/mrs-raw")
    public Object openMergeRequestsRaw(@RequestParam String repo,
                                       @RequestParam(defaultValue = "false") boolean full) {
        String url = yunxiao.changeRequestsUrl(repo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", url);
        try {
            String raw = yunxiao.rawGet(url);
            result.put("ok", true);
            result.put("rawLength", raw == null ? 0 : raw.length());
            if (!org.springframework.util.StringUtils.hasText(raw)) {
                result.put("hint", "云效返回了空内容。检查令牌、组织 ID 是否正确");
                return result;
            }

            JsonNode root = mapper.readTree(raw);
            List<JsonNode> items = findArray(root);

            // 状态值分布：这是分辨"字段名/枚举值变了"最直接的证据
            Map<String, Integer> statusCounts = new LinkedHashMap<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode n : items) {
                // 列表接口用 state，详情接口用 status，两个都打出来，
                // 免得下次又靠猜是哪个字段
                String status = n.path("status").asText("");
                String state = n.path("state").asText("");
                String key = !status.isEmpty() ? status : (!state.isEmpty() ? "(state) " + state : "（两者都没有）");
                statusCounts.merge(key, 1, Integer::sum);

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("localId", n.path("localId").asText(n.path("iid").asText("")));
                m.put("status", status);
                m.put("state", state);
                m.put("source", n.path("sourceBranch").asText(""));
                m.put("target", n.path("targetBranch").asText(""));
                m.put("title", n.path("title").asText(""));
                m.put("author", n.path("author").path("name").asText(""));
                rows.add(m);
            }

            result.put("parsedTotal", items.size());
            result.put("statusCounts", statusCounts);
            result.put("items", rows);

            // 待合并条数：详情接口的写法（TO_BE_MERGED）和列表接口的写法（state=opened）都算
            int openCount = statusCounts.getOrDefault("TO_BE_MERGED", 0)
                    + statusCounts.getOrDefault("(state) opened", 0);
            result.put("openCount", openCount);

            if (items.isEmpty()) {
                result.put("hint", "原文里没找到任何数组元素。云效可能把列表包在别的字段里，"
                        + "看下面 raw 里的结构，把字段名告诉我");
                result.put("raw", raw);
            } else if (openCount == 0) {
                result.put("hint", "拿到 " + items.size() + " 条数据，但没有一条是待合并状态。"
                        + "若 statusCounts 里全是 merged/closed，说明这个仓库当前确实没有待合并请求");
                if (full) {
                    result.put("raw", raw);
                }
            } else {
                result.put("hint", "有 " + openCount + " 条待合并请求，数据正常。"
                        + "若 /test/mrs 仍是 count:0，说明服务器上跑的还是旧 jar");
                if (full) {
                    result.put("raw", raw);
                }
            }
            return result;
        } catch (Exception e) {
            log.error("查云效原文失败", e);
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * 从云效响应里找出"列表"所在的数组。
     * 云效不同接口/版本包法不一样：裸数组、"data"、"list"、"items"、"result" 都见过。
     * 这里只做定位，不改内容——排错接口要如实反映原文。
     */
    private List<JsonNode> findArray(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        if (root.isArray()) {
            root.forEach(out::add);
            return out;
        }
        for (String field : new String[]{"data", "list", "items", "result", "changeRequests"}) {
            JsonNode v = root.get(field);
            if (v == null || v.isNull()) {
                continue;
            }
            if (v.isArray()) {
                v.forEach(out::add);
                return out;
            }
            if (v.isObject()) {
                // 形如 {"data":{"list":[...]}} 再往下钻一层
                for (String inner : new String[]{"list", "items", "result", "changeRequests"}) {
                    JsonNode iv = v.get(inner);
                    if (iv != null && iv.isArray()) {
                        iv.forEach(out::add);
                        return out;
                    }
                }
            }
        }
        return out;
    }

    /**
     * 按仓库名或完整路径查仓库 ID。
     *
     * 云效新版页面的地址栏里不带仓库 ID（只有 orgId/组名/仓库名），
     * 而 /test/mrs、合并、查询接口都要 ID，这里反查一次。
     *
     * **仓库名在组织内不唯一**（1998java/source-admin-new 和 test-group/source-admin-new
     * 是两个不同的仓库），所以推荐传完整路径：
     *   /test/repos?path=1998java/source-admin-new
     * 只传名字也能用，但返回可能有多条，每条会标明是 exact 还是 fuzzy，按 path 认。
     *   /test/repos?name=source-admin-new
     * 两个都不传就列出最近活跃的 100 个仓库。
     */
    @GetMapping("/repos")
    public Object repositories(@RequestParam(required = false, defaultValue = "") String name,
                               @RequestParam(required = false, defaultValue = "") String path) {
        try {
            String exactPath = path.trim();
            String exactName = name.trim();

            // 搜索关键字：传了路径就只用最后一段（仓库名）去搜。
            // 不把带 / 的完整路径直接塞进 search 参数，避免 %2F 被网关拦掉；
            // 完整路径的精确匹配在本地做，比依赖服务端的模糊匹配可靠。
            String keyword = RepoMatcher.keyword(exactPath, exactName);

            // 分两桶装：路径完全对上的放 exact，同名但路径对不上的放 fuzzy。
            // 传了 path 且能对上时**只返回 exact**，避免照着一个重名仓库抄走 ID；
            // 一个都对不上（比如云效返回的 path 带的是命名空间 ID）才退回给 fuzzy，
            // 并把返回的 path 原样打出来，让人自己认——绝不静默挑一个。
            List<Map<String, Object>> exactList = new ArrayList<>();
            List<Map<String, Object>> fuzzyList = new ArrayList<>();

            for (RepoItem repo : yunxiao.listRepositories(keyword)) {
                boolean exact = exactPath.isEmpty()
                        ? repo.getName() != null && repo.getName().equalsIgnoreCase(exactName)
                        : RepoMatcher.pathEquals(repo, exactPath);

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("repoId", repo.getId());
                m.put("name", repo.getName());
                m.put("path", repo.getPathWithNamespace());
                m.put("match", exact ? "exact" : "fuzzy");
                if (repo.isArchived()) {
                    m.put("archived", true);
                }
                (exact ? exactList : fuzzyList).add(m);
            }

            List<Map<String, Object>> list = exactList.isEmpty() ? fuzzyList : exactList;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("count", list.size());
            result.put("items", list);

            if (list.isEmpty()) {
                result.put("hint", "没搜到。换成完整路径再试，如 path=1998java/source-admin-new；"
                        + "或确认令牌有「代码管理-代码仓库-只读」权限");
            } else if (exactPath.isEmpty() && exactList.size() > 1) {
                // 只按名字搜到多个同名仓库：这就是必须带组名的原因
                result.put("hint", "有 " + exactList.size() + " 个同名仓库，match 都是 exact 但路径不同。"
                        + "改用 path=组名/仓库名 指定，别只看名字抄 repoId");
            } else if (exactList.size() > 1) {
                result.put("hint", "有 " + exactList.size() + " 个仓库路径完全等于 " + exactPath
                        + "，先看 path 字段确认，再抄 repoId");
            } else if (exactList.isEmpty() && !exactPath.isEmpty()) {
                result.put("hint", "没有路径完全等于 " + exactPath + " 的仓库，下面是同名仓库。"
                        + "云效返回的 path 不一定和页面地址里的组名一致（可能带命名空间 ID），请按 path 字段认");
            } else if (exactList.isEmpty()) {
                result.put("hint", "都是模糊匹配结果（match=fuzzy），要精确的请传 path=组名/仓库名");
            }
            return result;
        } catch (Exception e) {
            log.error("查询仓库列表失败", e);
            return "查询仓库列表失败：" + e.getMessage();
        }
    }

    /** 不等 60 秒，立刻跑一轮轮询（发卡片） */
    @GetMapping("/scan")
    public Object scanNow() {
        MergeRequestPoller p = poller.getIfAvailable();
        if (p == null) {
            return "轮询没开启：把 mergebot.poll.enabled 改成 true";
        }
        try {
            p.scan();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("已发过卡片的请求", p.snapshot());
            return result;
        } catch (Exception e) {
            log.error("手动轮询失败", e);
            return "轮询失败：" + e.getMessage();
        }
    }

    /** 机器人所在的群列表，用来拿 chat_id */
    @GetMapping("/chats")
    public Object chats() {
        try {
            List<Map<String, String>> list = new ArrayList<>();
            for (com.lark.oapi.service.im.v1.model.ListChat chat : messageService.listChats().getData().getItems()) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("chatId", chat.getChatId());
                m.put("name", chat.getName());
                list.add(m);
            }
            return list;
        } catch (Exception e) {
            log.error("查群列表失败", e);
            return "查群列表失败：" + e.getMessage();
        }
    }

    /**
     * 发一张测试卡片。
     *
     * 不传 repo / mr（或传 0）→ 发一张纯样式演示卡，用来确认"卡片能不能到群里"；
     * 传了真实的 repo / mr → **内容全部来自云效实时数据**，提交列表和链接都是真的，
     * 点按钮也会真的去合。以前这个方法不管传什么都发演示数据，
     * 卡片上写着"张三 / feature/demo"，拿去验收会被当成 bug，已经改掉。
     */
    @GetMapping("/card")
    public Object sendTestCard(@RequestParam(defaultValue = "0") String repo,
                               @RequestParam(defaultValue = "0") String mr,
                               @RequestParam(defaultValue = "测试仓库") String name) {
        try {
            boolean real = StringUtils.hasText(repo) && !"0".equals(repo.trim())
                    && StringUtils.hasText(mr) && !"0".equals(mr.trim());

            MrView view;
            if (real) {
                view = yunxiao.getChangeRequest(repo, mr);
            } else {
                String demo = "{\"title\":\"测试：合并请求通知\",\"sourceBranch\":\"feature/demo\","
                        + "\"targetBranch\":\"test\",\"author\":{\"name\":\"张三\"},"
                        + "\"status\":\"TO_BE_MERGED\",\"conflictCheckStatus\":\"NO_CONFLICT\","
                        + "\"allRequirementsPass\":true,\"detailUrl\":\"\"}";
                view = MrView.of(mapper.readTree(demo));
            }

            List<CommitItem> commits = real
                    ? yunxiao.commitsFor(repo, view.getSourceBranch(), view.getTargetBranch())
                    : null;
            String webUrl = real ? yunxiao.webUrlFor(name, mr, view) : "";

            String card = cardFactory.approvalCard(name, repo, mr, view, commits, webUrl);
            String messageId = messageService.sendCard(feishuProps.getChatId(), card);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("realData", real);
            result.put("commitCount", commits == null ? 0 : commits.size());
            result.put("webUrl", webUrl);
            result.put("messageId", messageId);
            result.put("cardJson", card);
            return result;
        } catch (Exception e) {
            log.error("发测试卡片失败", e);
            return "发卡片失败：" + e.getMessage();
        }
    }
}
