package com.yiyu.mergebot.check;

import com.lark.oapi.service.im.v1.model.ListChat;
import com.lark.oapi.service.im.v1.model.ListChatResp;
import com.yiyu.mergebot.config.FeishuProperties;
import com.yiyu.mergebot.config.PollProperties;
import com.yiyu.mergebot.config.YunxiaoProperties;
import com.yiyu.mergebot.feishu.CardFactory;
import com.yiyu.mergebot.feishu.FeishuMessageService;
import com.yiyu.mergebot.feishu.FeishuMessageService.ChatProbe;
import com.yiyu.mergebot.feishu.FeishuWsRunner;
import com.yiyu.mergebot.support.WebhookDebugStore;
import com.yiyu.mergebot.yunxiao.RepoItem;
import com.yiyu.mergebot.yunxiao.YunxiaoClient;
import com.yiyu.mergebot.yunxiao.YunxiaoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 一键自检：配置填好、服务起来之后，把所有依赖一次全查一遍。
 *
 * 设计上守三条，都是为了不让自检本身变成新的坑：
 *
 *  1. **只读，不改任何东西**。除了可关闭的"发一张卡片"，全部是查询接口。
 *     自检可以反复跑，跑十次的结果应该一样。
 *
 *  2. **不复用业务缓存**。群成员在业务里缓存 300 秒、姓名缓存 10 分钟，
 *     如果自检读缓存，"缓存里恰好有"会变成假通过。这里全部直连飞书/云效。
 *
 *  3. **测不了的就说测不了，不给假绿**。写权限、公网可达这两项无法无损验证，
 *     一律标"待确认"并写清人工怎么验，而不是硬凑一个通过。
 *
 * 每项都带"怎么修"，写的是界面点击路径，不是错误码。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "mergebot.test-endpoints", havingValue = "true")
public class SelfCheckService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 飞书：应用缺权限 */
    private static final int FEISHU_NO_PERMISSION = 99991672;
    /** 飞书：机器人（或用户）不在这个群里 */
    private static final int FEISHU_NOT_IN_CHAT = 230002;

    /** 仓库编号最多验证这么多个，防止配了二十个仓库时自检跑成几分钟 */
    private static final int MAX_REPO_PROBE = 5;

    private final YunxiaoClient yunxiao;
    private final YunxiaoProperties yunxiaoProps;
    private final FeishuProperties feishuProps;
    private final PollProperties pollProps;
    private final FeishuMessageService messageService;
    private final FeishuWsRunner wsRunner;
    private final WebhookDebugStore debugStore;
    private final CardFactory cardFactory;

    private final String serverPort;
    private final String serverAddress;

    public SelfCheckService(YunxiaoClient yunxiao,
                            YunxiaoProperties yunxiaoProps,
                            FeishuProperties feishuProps,
                            PollProperties pollProps,
                            FeishuMessageService messageService,
                            FeishuWsRunner wsRunner,
                            WebhookDebugStore debugStore,
                            CardFactory cardFactory,
                            @Value("${server.port:8080}") String serverPort,
                            @Value("${server.address:}") String serverAddress) {
        this.yunxiao = yunxiao;
        this.yunxiaoProps = yunxiaoProps;
        this.feishuProps = feishuProps;
        this.pollProps = pollProps;
        this.messageService = messageService;
        this.wsRunner = wsRunner;
        this.debugStore = debugStore;
        this.cardFactory = cardFactory;
        this.serverPort = serverPort;
        this.serverAddress = serverAddress;
    }

    /**
     * 跑一遍全部检查。
     *
     * @param sendCard 是否真发一张卡片到群里。这是唯一有副作用的检查，
     *                 也是唯一能证明「有发消息权限」的方式（那个权限没有只读接口可验）
     */
    public Report run(boolean sendCard) {
        long start = System.currentTimeMillis();
        Report report = new Report();
        report.time = LocalDateTime.now().format(FMT);

        // 每组单独兜底：自检的价值在于"把问题指出来"，
        // 如果某一组自己写崩了（空指针之类），不能让整个接口 500 —— 那样
        // 用户连"其它几组是好的"都看不到。崩掉的那组会变成一条 FAIL，
        // 说明这是自检自己的 bug，不是配置问题。
        report.groups.add(safeGroup("一、配置文件", this::checkConfig));
        report.groups.add(safeGroup("二、云效接口", this::checkYunxiao));
        report.groups.add(safeGroup("三、飞书", () -> checkFeishu(sendCard)));
        report.groups.add(safeGroup("四、运行环境", this::checkRuntime));

        renumber(report);
        report.summarize();
        log.info("自检完成，{}，耗时 {} ms", report.summary, System.currentTimeMillis() - start);

        // 有必须修的项时单独打一条 warn：验收的人不一定会打开这个接口，
        // 但部署完一定会看日志。这条能让人在日志里就发现"某个关键配置没填对"
        Result headline = report.firstToFix();
        if (headline != null) {
            log.warn("自检有 {} 项必须修，最先要修的是 #{} {} —— {}",
                    report.getCounts().get("fail"), headline.no, headline.title, headline.detail);
        }
        return report;
    }

    private Group safeGroup(String name, Supplier<List<Result>> supplier) {
        try {
            return new Group(name, supplier.get());
        } catch (Exception e) {
            log.warn("自检分组[{}]执行出错", name, e);
            List<Result> one = new ArrayList<>();
            one.add(fail("这一组检查本身出错了", e.toString(),
                    "这是自检程序的 bug，不是你的配置问题。把服务日志里的这段异常发出来"));
            return new Group(name, one);
        }
    }

    // ================================================================ 一、配置

    private List<Result> checkConfig() {
        List<Result> items = new ArrayList<>();

        // 01 四个必填项。缺一项服务本来就起不来，但配置可能来自 jar 同级的另一份文件
        Map<String, String> required = new LinkedHashMap<>();
        required.put("云效令牌", yunxiaoProps.getToken());
        required.put("飞书 app-id", feishuProps.getAppId());
        required.put("飞书 app-secret", feishuProps.getAppSecret());
        required.put("审批群 chat-id", feishuProps.getChatId());

        StringBuilder detail = new StringBuilder();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : required.entrySet()) {
            boolean ok = filled(e.getValue());
            detail.append(e.getKey()).append(ok ? " ✓" : " ✗").append("  ");
            if (!ok) {
                missing.add(e.getKey());
            }
        }
        if (missing.isEmpty()) {
            items.add(pass("必填配置项", detail.toString().trim()));
        } else {
            items.add(fail("必填配置项", detail.toString().trim()
                            + "；缺：" + String.join("、", missing),
                    "在 application.yml 里补齐上面标 ✗ 的项。服务器上改的是 jar 同级那份"
                            + "（start.sh 用的就是它），改源码里的 application.yml 不生效。"
                            + "注意模板里的「你的应用ID」这类中文占位值不算填过"));
        }

        // 02 触发方式
        boolean webhookReady = filled(yunxiaoProps.getWebhookToken());
        boolean pollOn = pollProps.isEnabled();
        if (webhookReady && pollOn) {
            items.add(warn("触发方式", "Webhook 和轮询同时开着。同一个合并请求会收到两张卡片 —— "
                            + "两条链路各有一套去重规则，互相不认",
                    "长期只留一条：有公网就关轮询（mergebot.poll.enabled: false），"
                            + "没公网就清空 yunxiao.webhook-token"));
        } else if (webhookReady) {
            items.add(pass("触发方式", "云效 Webhook（需要公网能访问到本服务）"));
        } else if (pollOn) {
            items.add(pass("触发方式", "轮询（不用公网，延迟约 "
                    + (pollProps.getIntervalMs() / 1000) + " 秒）"));
        } else {
            items.add(fail("触发方式", "一个都没开：服务在跑，但永远不会发卡片",
                    "二选一：① 有公网 → 填 yunxiao.webhook-token，并把同一个值填进云效"
                            + "代码库 → 设置 → WebHooks；② 没公网 → 把 mergebot.poll.enabled 改成 true"));
        }

        // 03 轮询配置是否自相矛盾
        if (!pollOn) {
            items.add(skip("轮询配置", "轮询没开，走 Webhook，这项不用管"));
        } else if (pollProps.getRepoIds() == null || pollProps.getRepoIds().isEmpty()) {
            items.add(fail("轮询配置", "轮询开着，但 mergebot.poll.repo-ids 是空的，扫不到任何仓库",
                    "填仓库编号（不是仓库名）。拿法：打开云效任意合并请求，地址里 "
                            + "/repositories/{这串数字}/changeRequests/ 中间那段"));
        } else {
            items.add(pass("轮询配置", "盯 " + pollProps.getRepoIds().size() + " 个仓库："
                    + String.join(", ", pollProps.getRepoIds())));
        }

        // 04 谁能点按钮
        int whitelist = feishuProps.getAllowedOpenIds() == null ? 0 : feishuProps.getAllowedOpenIds().size();
        if (whitelist > 0 && !feishuProps.isAllowGroupMembers()) {
            items.add(pass("谁能点按钮", "只认白名单里的 " + whitelist + " 个人"));
        } else if (whitelist > 0) {
            items.add(pass("谁能点按钮", "白名单 " + whitelist + " 人 + 审批群所有人"));
        } else if (feishuProps.isAllowGroupMembers()) {
            items.add(pass("谁能点按钮", "审批群里所有人都能点（推荐，加减人自动生效）"));
        } else {
            items.add(fail("谁能点按钮", "白名单为空，且 allow-group-members 被关掉了 —— "
                            + "任何人点按钮都会提示「没有合并权限」",
                    "两种改法选一个：① feishu.allow-group-members 改成 true（推荐）；"
                            + "② 把要放行的人写进 feishu.allowed-open-ids（ou_ 开头）"));
        }

        // 05 组织 ID
        if (filled(yunxiaoProps.getOrgId())) {
            items.add(pass("云效组织 ID", yunxiaoProps.getOrgId()));
        } else {
            items.add(warn("云效组织 ID", "没填，接口会走 Region 版路径",
                    "如果你用的是 codeup.aliyun.com 的组织，必须填。从云效地址栏第一段拿，"
                            + "形如 607f9fbe429bb594fdddc638"));
        }

        return items;
    }

    // ================================================================ 二、云效

    private List<Result> checkYunxiao() {
        List<Result> items = new ArrayList<>();
        if (!filled(yunxiaoProps.getToken())) {
            items.add(skip("云效接口", "令牌都没填，这几项没法测。先补 yunxiao.token"));
            return items;
        }

        // 01 令牌 + 读代码库权限，一次调用同时验证两件事
        boolean canReadRepo = false;
        try {
            List<RepoItem> repos = yunxiao.listRepositories("");
            canReadRepo = true;
            items.add(pass("令牌有效、能读代码库", "看到 " + repos.size() + " 个代码库"));
        } catch (YunxiaoException e) {
            items.add(tokenFailure(e.getMessage()));
        } catch (Exception e) {
            items.add(fail("令牌有效、能读代码库", e.getMessage(),
                    "先确认服务器能访问 " + yunxiaoProps.getDomain() + "（防火墙、DNS）"));
        }

        // 02 读合并请求权限。没有配置仓库时跳过，不硬凑一个结论
        LinkedHashSet<String> repoIds = configuredRepoIds();
        if (!canReadRepo) {
            items.add(skip("能读合并请求", "上一步没通过，不再重复等超时"));
        } else if (repoIds.isEmpty()) {
            items.add(skip("能读合并请求", "没配仓库编号（走 Webhook 时确实不用配）。"
                    + "想验证的话，随便填一个到 mergebot.poll.repo-ids 再跑一次"));
        } else {
            String repoId = repoIds.iterator().next();
            try {
                int n = yunxiao.listOpenChangeRequests(repoId).size();
                items.add(pass("能读合并请求", "仓库 " + repoId + " 当前有 " + n + " 个待合并请求"));
            } catch (YunxiaoException e) {
                items.add(repoFailure("能读合并请求", repoId, e.getMessage()));
            }
        }

        // 03 配置里的每个仓库编号是否真实存在
        if (repoIds.isEmpty()) {
            items.add(skip("仓库编号有效", "没配仓库编号，跳过"));
        } else if (!canReadRepo) {
            items.add(skip("仓库编号有效", "上一步没通过，跳过"));
        } else {
            items.add(checkRepoIds(repoIds));
        }

        // 04 写权限：确实没法自动验，明说
        items.add(warn("合并 / 关闭的写权限", "这个没法自动验证 —— 只有真合一次才知道，"
                        + "我不会为了凑一个「全通过」去动你的仓库",
                "找个测试仓库、随便开一个合并请求，在飞书卡片上点一次「合并」。"
                        + "报 403 说明令牌少了「合并请求 读+写」里的写权限，"
                        + "去云效 → 头像 → 个人设置 → 个人访问令牌 → 编辑该令牌补上（令牌值不变，不用重启）"));

        return items;
    }

    /** 把云效失败的原文翻译成"该去改什么" */
    private Result tokenFailure(String raw) {
        String msg = raw == null ? "" : raw;
        if (msg.contains("HTTP 401")) {
            return fail("令牌有效、能读代码库", "401：令牌无效或已过期",
                    "云效个人访问令牌默认 7 天过期。去云效 → 右上角头像 → 个人设置 → "
                            + "个人访问令牌，看这条是不是已过期，重新生成后填进 yunxiao.token 并重启");
        }
        if (msg.contains("HTTP 403")) {
            return fail("令牌有效、能读代码库", "403：令牌有效，但少了代码仓库的读权限",
                    "去云效 → 头像 → 个人设置 → 个人访问令牌 → 编辑这条令牌，勾上"
                            + "「代码管理 → 代码仓库 → 只读」和「代码管理 → 合并请求 → 读+写」。"
                            + "令牌值不变，改完立即生效，不用重启");
        }
        if (msg.contains("HTTP 404")) {
            return fail("令牌有效、能读代码库", "404：接口地址不对",
                    "检查 yunxiao.domain 是不是 https://openapi-rdc.aliyuncs.com，"
                            + "以及 org-id 是否填了组织 ID（中心版必须带）");
        }
        return fail("令牌有效、能读代码库", msg,
                "先确认服务器能访问 " + yunxiaoProps.getDomain() + "（防火墙、DNS、代理）");
    }

    private Result checkRepoIds(LinkedHashSet<String> repoIds) {
        StringBuilder ok = new StringBuilder();
        List<String> bad = new ArrayList<>();
        int n = 0;
        String networkDown = null;

        for (String id : repoIds) {
            if (n++ >= MAX_REPO_PROBE) {
                break;
            }
            try {
                ok.append(id).append("=").append(yunxiao.probeRepository(id)).append("  ");
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (isNetworkFailure(msg)) {
                    // 第一个仓库就超时，说明是整体网络不通。接着探剩下几个只会再各等
                    // 20 秒（读超时），把浏览器一路拖到网关超时（Nginx 默认 60 秒），
                    // 用户看到的是 504 而不是这份报告 —— 那等于自检自己废掉了。
                    networkDown = msg;
                    break;
                }
                bad.add(id + "（" + shortReason(msg) + "）");
            }
        }

        if (networkDown != null) {
            return fail("仓库编号有效", "网络不通，没法验证：" + networkDown,
                    "先确认服务器能访问 " + yunxiaoProps.getDomain()
                            + "（防火墙、DNS、代理）。和上面「令牌」那项一起看");
        }
        if (bad.isEmpty()) {
            return pass("仓库编号有效", ok.toString().trim()
                    + (repoIds.size() > MAX_REPO_PROBE ? "（只验了前 " + MAX_REPO_PROBE + " 个）" : ""));
        }
        return fail("仓库编号有效", "查不到的：" + String.join("、", bad)
                        + (ok.length() > 0 ? "；正常的：" + ok.toString().trim() : ""),
                "编号写错或者令牌没有这个仓库的权限。拿正确编号：调 /test/repos?name=仓库名，"
                        + "返回里的 repoId 就是。注意新版云效页面的地址栏已经不带仓库编号了");
    }

    /** 连不上、超时、DNS 失败归这一类；带 HTTP 状态码的是业务失败，不该短路 */
    private boolean isNetworkFailure(String msg) {
        return msg.contains("网络或超时");
    }

    private Result repoFailure(String title, String repoId, String raw) {
        String msg = raw == null ? "" : raw;
        if (msg.contains("HTTP 404")) {
            return fail(title, "仓库 " + repoId + " 不存在，或者令牌看不到它",
                    "用 /test/repos?name=仓库名 反查正确编号");
        }
        if (msg.contains("HTTP 403")) {
            return fail(title, "仓库 " + repoId + " 没权限",
                    "去云效个人访问令牌里补「合并请求 读+写」权限");
        }
        return fail(title, "仓库 " + repoId + "：" + msg, "按提示内容排查网络或令牌");
    }

    // ================================================================ 三、飞书

    /** 编排四段：凭证 → 群与成员 → 长连接 → 发消息。具体判断在各段自己的方法里 */
    private List<Result> checkFeishu(boolean sendCard) {
        List<Result> items = new ArrayList<>();
        String chatId = feishuProps.getChatId();

        boolean credentialOk = checkCredential(items, chatId);
        boolean chatOk = checkChatAndMembers(items, chatId, credentialOk);
        checkWs(items);
        checkSendCard(items, chatId, chatOk, sendCard);

        return items;
    }

    /**
     * 01 应用凭证 + 列群。这一步同时证明 app-id/app-secret 和「获取群组信息」权限。
     *
     * @param chatId 配置的审批群，用来在群列表里确认它到底在不在
     * @return 凭证与列群是否都通过；后面的群检查全部依赖它
     */
    private boolean checkCredential(List<Result> items, String chatId) {
        try {
            ListChatResp resp = messageService.listChats();
            if (resp == null || resp.getData() == null) {
                // 接口返回空 data 时直接取 items 会空指针，NPE 会伪装成"凭证错误"，
                // 把真正的原因（往往是没有权限）藏起来
                items.add(fail("应用凭证、能列群",
                        "接口返回是空的（code=" + (resp == null ? "无响应" : resp.getCode()) + "）",
                        "先确认 app-id / app-secret，再看有没有「获取群组信息」权限"));
                return false;
            }
            ListChat[] chats = resp.getData().getItems();
            int n = chats == null ? 0 : chats.length;
            boolean found = false;
            if (chats != null) {
                for (ListChat c : chats) {
                    if (c != null && chatId != null && chatId.equals(c.getChatId())) {
                        found = true;
                        break;
                    }
                }
            }
            items.add(pass("应用凭证、能列群", "机器人所在群 " + n + " 个"
                    + (n == 0 ? "" : found ? "，其中有配置的审批群" : "，但列表里没有配置的审批群")));
            return true;
        } catch (Exception e) {
            items.add(feishuCredentialFailure(e.getMessage()));
            return false;
        }
    }

    /**
     * 02~04 群可访问、能查群成员、能换出人名。
     *
     * 三段写在一起，是因为后两段都依赖"群能访问"：拆成三个独立方法反而要在三处
     * 重复判同一个前置条件，读的时候还得来回跳。
     *
     * @return 群是否可访问
     */
    private boolean checkChatAndMembers(List<Result> items, String chatId, boolean credentialOk) {
        if (!filled(chatId)) {
            addGroupSkips(items, "chat-id 没填");
            return false;
        }
        if (!credentialOk) {
            addGroupSkips(items, "上一步凭证没通过，跳过");
            return false;
        }

        ChatProbe probe = messageService.chatProfile(chatId);
        items.add(chatAccessResult(probe));
        addMembersAndContact(items, probe);
        return probe.isChatOk();
    }

    /** 群这一组没法测时的三连跳过，保证报告里编号不空档 */
    private void addGroupSkips(List<Result> items, String why) {
        items.add(skip("审批群可访问", why));
        items.add(skip("能查群成员", why));
        items.add(skip("能换出人名", why));
    }

    private Result chatAccessResult(ChatProbe probe) {
        if (probe.isChatOk()) {
            // 群名不是关键信息，飞书没返回时给个占位，别输出「群「null」」
            String name = StringUtils.hasText(probe.getChatName()) ? probe.getChatName() : "（群名未返回）";
            return pass("审批群可访问", "群「" + name + "」可访问，机器人在群里");
        }
        if (probe.getChatErrorCode() == FEISHU_NOT_IN_CHAT) {
            return fail("审批群可访问", "230002：机器人不在这个群里",
                    "打开那个群 → 右上角设置 → 群机器人 → 添加机器人 → 选你这个应用。"
                            + "加完立即生效，不用重启");
        }
        if (probe.getChatErrorCode() == FEISHU_NO_PERMISSION) {
            return fail("审批群可访问", "99991672：缺「获取群组信息」权限",
                    permissionFix("获取群组信息", "im:chat:readonly"));
        }
        if (probe.getChatErrorCode() > 0) {
            return fail("审批群可访问",
                    "code=" + probe.getChatErrorCode() + " msg=" + probe.getChatErrorMsg(),
                    "chat-id 写错的可能性最大。拿正确值：/test/chats 看机器人所在的群列表");
        }
        return fail("审批群可访问", probe.getChatErrorMsg(),
                "先确认服务器能不能访问 open.feishu.cn");
    }

    private void addMembersAndContact(List<Result> items, ChatProbe probe) {
        if (!probe.isChatOk()) {
            items.add(skip("能查群成员", "上一项没通过，跳过"));
            items.add(skip("能换出人名", "上一项没通过，跳过"));
            return;
        }
        if (!probe.isMembersOk()) {
            items.add(membersFailure(probe));
            items.add(skip("能换出人名", "上一项没通过，跳过"));
            return;
        }
        items.add(pass("能查群成员", "群成员 "
                + (probe.getMemberTotal() == null ? "?" : probe.getMemberTotal()) + " 人"));
        if (probe.getSampleOpenId() == null) {
            items.add(skip("能换出人名", "群里没取到真人成员（可能只有机器人），没法试。"
                    + "往群里拉一个人再跑一次"));
        } else {
            items.add(checkContact(probe.getSampleOpenId()));
        }
    }

    private Result membersFailure(ChatProbe probe) {
        if (probe.getMembersErrorCode() == FEISHU_NO_PERMISSION) {
            return fail("能查群成员", "99991672：缺「查看群成员」权限",
                    permissionFix("查看群成员", "im:chat.members:read"));
        }
        return fail("能查群成员",
                "code=" + probe.getMembersErrorCode() + " msg=" + probe.getMembersErrorMsg(),
                "多半还是权限问题，按上面的路径开「查看群成员」");
    }

    /** 05 长连接：只影响按钮，不影响发卡片，但用户感知最强 */
    private void checkWs(List<Result> items) {
        if (wsRunner.isConnected()) {
            items.add(pass("长连接（按钮回调）", "已建立，点卡片按钮会有反应"));
        } else {
            items.add(fail("长连接（按钮回调）", "没建立。卡片能发出去，但点「合并」不会有任何反应",
                    "① 刚启动的话等 5~10 秒再刷新本页；② 还没好就看日志搜「飞书长连接」，"
                            + "若报 1000040350 说明同一应用被多个进程连着（上限 50 条），"
                            + "ps -ef | grep java 查残留进程"));
        }
    }

    /** 06 发消息权限：唯一的验证方式是真发一条 */
    private void checkSendCard(List<Result> items, String chatId, boolean chatOk, boolean sendCard) {
        if (!sendCard) {
            items.add(skip("能发消息", "本次没发卡片（地址栏加 ?card=true 才发）"));
        } else if (!chatOk) {
            items.add(skip("能发消息", "群都访问不到，先修上面那项"));
        } else {
            try {
                String messageId = messageService.sendCard(chatId, selfCheckCard());
                items.add(pass("能发消息", "已发一张到群里，去群里看一眼。messageId=" + messageId));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains(String.valueOf(FEISHU_NOT_IN_CHAT))) {
                    items.add(fail("能发消息", "230002：机器人不在群里",
                            "群设置 → 群机器人 → 添加机器人"));
                } else if (msg.contains(String.valueOf(FEISHU_NO_PERMISSION))) {
                    items.add(fail("能发消息", "99991672：缺发消息权限",
                            permissionFix("以机器人身份发消息", "im:message:send_as_bot")));
                } else {
                    items.add(fail("能发消息", msg,
                            "权限刚加的话，必须去「版本管理与发布」创建版本并发布，不发版等于没加"));
                }
            }
        }
    }

    private Result feishuCredentialFailure(String raw) {
        String msg = raw == null ? "" : raw;
        if (msg.contains("10014")) {
            return fail("应用凭证、能列群", "10014：app-secret 不对",
                    "开发者后台 → 你的应用 → 凭证与基础信息 → 重新复制 App Secret");
        }
        if (msg.contains("10003")) {
            return fail("应用凭证、能列群", "10003：app-id 或参数不对",
                    "检查 feishu.app-id 是不是 cli_ 开头、有没有多复制空格");
        }
        if (msg.contains(String.valueOf(FEISHU_NO_PERMISSION))) {
            return fail("应用凭证、能列群", "凭证有效，但缺「获取群组信息」权限，列不出群",
                    permissionFix("获取群组信息", "im:chat:readonly"));
        }
        return fail("应用凭证、能列群", msg,
                "先确认服务器能访问 open.feishu.cn，再看 app-id / app-secret 是否填错");
    }

    private Result checkContact(String openId) {
        try {
            Map<String, Object> r = messageService.diagnoseUserName(openId);
            if (Boolean.TRUE.equals(r.get("success"))) {
                Object name = r.get("name");
                return pass("能换出人名", "卡片上的操作人会显示真名（示例：" + name + "）");
            }
            Object code = r.get("code");
            if (code != null && String.valueOf(code).contains(String.valueOf(FEISHU_NO_PERMISSION))) {
                return warn("能换出人名", "99991672：缺通讯录权限，卡片上操作人会显示"
                                + "「未知用户（末 6 位）」。不影响合并，只是看不出是谁点的",
                        permissionFix("获取通讯录基本信息", "contact:contact.base:readonly"));
            }
            return warn("能换出人名", "code=" + code + " msg=" + r.get("msg"),
                    "见返回里的 hint。常见是这个人不在应用可用范围，"
                            + "去开发者后台 → 版本管理与发布 → 可用范围 里把他加上");
        } catch (Exception e) {
            return warn("能换出人名", e.getMessage(), "看网络能否访问 open.feishu.cn");
        }
    }

    // ================================================================ 四、运行环境

    private List<Result> checkRuntime() {
        List<Result> items = new ArrayList<>();

        // 01 监听地址。只听 127.0.0.1 时，云效从公网打不进来
        String addr = StringUtils.hasText(serverAddress) ? serverAddress : "0.0.0.0";
        if ("127.0.0.1".equals(serverAddress) || "localhost".equals(serverAddress)) {
            items.add(warn("服务监听地址", addr + ":" + serverPort
                            + "，只有本机能连。云效从公网打不进来（配了 Nginx 反代就是这样，正常）",
                    "如果没配 Nginx 反代而云效又打不进来，把 server.address 那行注释掉，重启"));
        } else {
            items.add(pass("服务监听地址", addr + ":" + serverPort + "（所有网卡）"));
        }

        // 02 JDK
        items.add(pass("运行环境", "Java " + System.getProperty("java.version")
                + " · " + System.getProperty("java.home")));

        // 03 用的是哪份配置文件。这是运维最容易搞混的一处：
        //    start.sh 用 --spring.config.additional-location 指向 jar 同级，
        //    所以改源码里的 yml 不生效。
        //    这里不能用当前工作目录判断 —— 脚本并没有 cd 到安装目录，
        //    从别处执行 start.sh 时工作目录是执行处，结论会正好反过来。
        items.add(configSourceResult());

        // 04 有没有真的收到过云效推送。没收到过，说明云效那边或网络还没通
        items.add(checkLastWebhook());

        // 05 排错接口本身的安全提醒。能跑到这里就说明开关一定是开的
        //    （本类只在 mergebot.test-endpoints=true 时才被创建），不用再判断
        items.add(warn("排错接口开关", "mergebot.test-endpoints 开着，/test/* 谁都能调（包括发消息）",
                "验收完改成 false 并重启。调的时候只用服务器本机 127.0.0.1，"
                        + "别用公网地址，免得把群 ID 和仓库列表暴露在外面"));

        return items;
    }

    private Result configSourceResult() {
        File location = codeLocation();
        // 部署时 location 是 .../merge-bot.jar，父目录就是 start.sh 里的 APP_HOME；
        // IDE 里是 target/classes，没有外部配置这一说
        File home = location.isFile() ? location.getParentFile() : location;
        File beside = new File(home, "application.yml");

        if (beside.isFile()) {
            return pass("配置文件来源", "用的是 " + beside.getAbsolutePath()
                    + "（和 jar 同级，start.sh 加载的就是这份）");
        }
        if (location.isFile()) {
            return fail("配置文件来源", home.getAbsolutePath() + " 下没有 application.yml，"
                            + "现在用的是打包进 jar 的那份，改外面的配置不会生效",
                    "把 application.yml 放到 " + home.getAbsolutePath() + "，和 jar 同级，再重启");
        }
        return pass("配置文件来源", "IDE 里跑，用的是 src/main/resources/application.yml");
    }

    /** 本类是从哪加载的：部署时是 jar 文件，IDE 里是 target/classes 目录 */
    private File codeLocation() {
        try {
            return new File(SelfCheckService.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            log.debug("取自身加载位置失败，退回当前工作目录", e);
            return new File(System.getProperty("user.dir", "."));
        }
    }

    private Result checkLastWebhook() {
        Map<String, Object> latest = debugStore.latest();
        if (latest.isEmpty()) {
            return warn("最近一次 Webhook", "服务启动以来还没收到过任何推送",
                    "走 Webhook 的话：① 云效 Webhook 的 URL 用公网地址，不能填 127.0.0.1；"
                            + "② 服务器端口要放行（安全组 + 本机防火墙）；"
                            + "③ 去云效投递记录点「测试」或「重试」，再看这个接口");
        }
        String time = String.valueOf(latest.get("收到时间"));
        String note = String.valueOf(latest.get("处理结果"));
        boolean tokenOk = Boolean.TRUE.equals(latest.get("token 是否匹配"));
        if (!tokenOk) {
            return fail("最近一次 Webhook", time + " 收到一条，但 Secret Token 不匹配",
                    "云效 Webhook 里填的 Secret Token 必须和 yunxiao.webhook-token 完全一致"
                            + "（前后空格也算不一致）");
        }
        if (note.startsWith("成功")) {
            return pass("最近一次 Webhook", time + " 收到并发送成功");
        }
        if (note.startsWith("忽略")) {
            return warn("最近一次 Webhook", time + " 收到了，但被忽略：" + note,
                    "通常是云效勾错了触发事件（只勾「合并请求事件」），"
                            + "或者云效点的是「测试」按钮（那是推送事件，被忽略才是对的）");
        }
        return fail("最近一次 Webhook", time + " 处理失败：" + note,
                "看返回里的原因；报文结构对不上时用 /test/last-webhook 看原文");
    }

    // ================================================================ 渲染

    /** 纯文本版，给人在浏览器里直接看 */
    public String render(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("======== 合并审批机器人 · 自检 ========\n");
        sb.append(report.time).append("    ").append(report.summary).append("\n");
        sb.append("======================================\n\n");

        Result first = report.firstToFix();
        if (first != null) {
            sb.append("最先要修：").append(first.no).append(" ").append(first.title).append("\n\n");
        } else if (report.getCounts().getOrDefault("warn", 0) > 0) {
            sb.append("没有必须修的项。标「待确认」的看一眼说明即可，不影响使用。\n\n");
        } else {
            sb.append("全部通过。\n\n");
        }

        for (Group g : report.groups) {
            sb.append("【").append(g.name).append("】\n");
            for (Result r : g.items) {
                sb.append("[").append(levelZh(r.level)).append("] ")
                        .append(r.no).append(" ").append(r.title).append("\n");
                if (StringUtils.hasText(r.detail)) {
                    sb.append("         详情：").append(r.detail).append("\n");
                }
                if (StringUtils.hasText(r.fix)) {
                    sb.append("         怎么修：").append(r.fix).append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("--------------------------------------------------\n");
        sb.append("两点说明：\n");
        sb.append(" · 「合并 / 关闭」写权限没法自动验证，只有真合一次才知道。\n");
        sb.append(" · 公网能不能访问不在这里测：服务从自己机器访问自己的公网地址会走内网，\n");
        sb.append("   防火墙没放行也照样通，测了等于没测。真要验：手机切 4G 打开\n");
        sb.append("   http://公网IP:端口/webhook/yunxiao ，返回 405 就是通的。\n");
        return sb.toString();
    }

    // ================================================================ 工具

    private LinkedHashSet<String> configuredRepoIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (pollProps.getRepoIds() != null) {
            ids.addAll(pollProps.getRepoIds());
        }
        if (yunxiaoProps.getRepos() != null) {
            ids.addAll(yunxiaoProps.getRepos().values());
        }
        ids.removeIf(s -> !StringUtils.hasText(s));
        return ids;
    }

    /**
     * 空值，或还是模板里的占位值。
     *
     * 判断依据是"有没有中文字符"：真实凭据只可能是英文字母、数字、下划线和横线
     * （cli_xxx / pt_xxx / oc_xxx / 十六进制组织 ID），出现中文就一定是从模板抄下来没改的
     * 「你的应用ID」「自己定一个随机字符串」。
     *
     * 之前只认 "在这里" 和 "xxxx" 两种，结果把模板里那六个占位值全判成"已填"，
     * 于是第 01 项报"配置都填好了"、第 06 项报"令牌过期" —— 自相矛盾，
     * 还把本来只是"没填"的人引去云效查令牌到期时间。
     */
    private boolean filled(String v) {
        if (!StringUtils.hasText(v)) {
            return false;
        }
        if (v.contains("xxxx") || v.contains("XXXX")) {
            return false;
        }
        return v.chars().allMatch(c -> c < 128);
    }

    private String shortReason(String msg) {
        if (!StringUtils.hasText(msg)) {
            return "查不到";
        }
        if (msg.contains("HTTP 404")) {
            return "不存在或没权限";
        }
        if (msg.contains("HTTP 403")) {
            return "没权限";
        }
        return msg.length() > 40 ? msg.substring(0, 40) + "…" : msg;
    }

    private String permissionFix(String cnName, String scope) {
        return "飞书开放平台 open.feishu.cn → 你的应用 → 权限管理 → 搜索「" + cnName
                + "」→ 开通（标识是 " + scope + "）；然后**必须**去"
                + "「版本管理与发布」创建版本并发布，不发版等于没加";
    }

    /** 自检发出的那张卡。刻意做得短，看到就说明「在群 + 能发消息」两件事都成了 */
    private String selfCheckCard() {
        String time = LocalDateTime.now().format(FMT);
        return cardFactory.noticeCard("合并审批机器人 · 自检",
                Arrays.asList(
                        "能看到这张卡片，说明 **机器人已经在群里**，并且 **有发消息权限**。",
                        "其余检查结果（云效令牌、群成员、按钮回调）看 /test/doctor 的返回。"),
                "由自检接口自动发出 · " + time + " · 可以忽略");
    }

    private void renumber(Report report) {
        int n = 1;
        for (Group g : report.groups) {
            for (Result r : g.items) {
                r.no = String.format("%02d", n++);
            }
        }
    }

    private static Result pass(String title, String detail) {
        return new Result(Level.PASS, title, detail, null);
    }

    private static Result warn(String title, String detail, String fix) {
        return new Result(Level.WARN, title, detail, fix);
    }

    private static Result fail(String title, String detail, String fix) {
        return new Result(Level.FAIL, title, detail, fix);
    }

    private static Result skip(String title, String detail) {
        return new Result(Level.SKIP, title, detail, null);
    }

    private static String levelZh(Level l) {
        switch (l) {
            case PASS:
                return "通过";
            case WARN:
                return "待确认";
            case FAIL:
                return "需要修";
            default:
                return "跳过";
        }
    }

    // ================================================================ 数据

    public enum Level {
        /** 验证通过 */
        PASS,
        /** 验不了，或者缺了不影响主流程 */
        WARN,
        /** 不改就用不了 */
        FAIL,
        /** 依赖的前一项没通过，这项没测 */
        SKIP
    }

    public static class Result {
        private String no;
        private final Level level;
        private final String title;
        private final String detail;
        private final String fix;

        Result(Level level, String title, String detail, String fix) {
            this.level = level;
            this.title = title;
            this.detail = detail;
            this.fix = fix;
        }

        public String getNo() {
            return no;
        }

        public Level getLevel() {
            return level;
        }

        public String getTitle() {
            return title;
        }

        public String getDetail() {
            return detail;
        }

        public String getFix() {
            return fix;
        }
    }

    public static class Group {
        private final String name;
        private final List<Result> items;

        Group(String name, List<Result> items) {
            this.name = name;
            this.items = items;
        }

        public String getName() {
            return name;
        }

        public List<Result> getItems() {
            return items;
        }
    }

    public static class Report {
        private String time;
        private String summary;
        private Map<String, Integer> counts = new LinkedHashMap<>();
        private List<Group> groups = new ArrayList<>();

        void summarize() {
            int pass = 0;
            int warn = 0;
            int fail = 0;
            int skip = 0;
            for (Group g : groups) {
                for (Result r : g.items) {
                    switch (r.level) {
                        case PASS:
                            pass++;
                            break;
                        case WARN:
                            warn++;
                            break;
                        case FAIL:
                            fail++;
                            break;
                        default:
                            skip++;
                            break;
                    }
                }
            }
            counts.put("total", pass + warn + fail + skip);
            counts.put("pass", pass);
            counts.put("warn", warn);
            counts.put("fail", fail);
            counts.put("skip", skip);
            summary = "共 " + counts.get("total") + " 项：通过 " + pass
                    + " · 待确认 " + warn + " · 需要修 " + fail + " · 跳过 " + skip;
            if (fail == 0 && warn > 0) {
                summary += "（没有必须修的项，待确认的看一眼说明即可）";
            }
        }

        /**
         * 第一个必须修的项，没有就返回 null。
         *
         * **只认 FAIL**。以前没有 FAIL 时会退而返回第一个「待确认」，而写权限那一项
         * 永远是待确认 —— 结果每次自检都打印"最先要修：合并 / 关闭的写权限"，
         * 让用户去修一件根本不需要修、也修不了的事。跑三次之后整份报告就没人看了。
         */
        Result firstToFix() {
            for (Group g : groups) {
                for (Result r : g.items) {
                    if (r.level == Level.FAIL) {
                        return r;
                    }
                }
            }
            return null;
        }

        public String getTime() {
            return time;
        }

        public String getSummary() {
            return summary;
        }

        public Map<String, Integer> getCounts() {
            return counts;
        }

        public List<Group> getGroups() {
            return groups;
        }
    }
}
