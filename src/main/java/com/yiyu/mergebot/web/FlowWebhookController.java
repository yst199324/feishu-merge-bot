package com.yiyu.mergebot.web;

import com.yiyu.mergebot.config.FeishuProperties;
import com.yiyu.mergebot.config.YunxiaoProperties;
import com.yiyu.mergebot.feishu.CardFactory;
import com.yiyu.mergebot.feishu.FeishuMessageService;
import com.yiyu.mergebot.support.IdempotentStore;
import com.yiyu.mergebot.support.WebhookGuard;
import com.yiyu.mergebot.yunxiao.CommitItem;
import com.yiyu.mergebot.yunxiao.MrView;
import com.yiyu.mergebot.yunxiao.YunxiaoClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 接收云效流水线的调用。
 *
 * 为什么需要这个接口：
 * 流水线被"合并请求 新建/更新"事件触发后，内置变量里只有分支名、提交信息、提交人，
 * **没有合并请求编号**（没有 repoId、iid 这两个值）。而卡片按钮必须携带它们，
 * 否则点了不知道要合并哪一个。
 *
 * 所以这里做一次"补齐"：拿流水线传来的仓库名 + 源分支，
 * 反查云效的待合并列表，匹配出合并请求编号，再发卡片。
 *
 * 流水线里的调用命令（注意用 --data-urlencode，让 curl 负责转义，
 * 否则提交信息里出现一个双引号就会破坏 JSON）：
 *
 *   curl -s -X POST 'https://你的域名/webhook/flow' \
 *     -H 'X-Codeup-Token: 你的token' \
 *     --data-urlencode 'repoName=store-web-new' \
 *     --data-urlencode "sourceBranch=${CI_COMMIT_REF_NAME}" \
 *     --data-urlencode 'targetBranch=test' \
 *     --data-urlencode "author=${CI_COMMIT_AUTHOR}" \
 *     --data-urlencode "title=${CI_COMMIT_TITLE}" \
 *     --data-urlencode "runNo=${BUILD_NUMBER}" || true
 *
 * 结尾的 || true 是故意的：接口不通不该让流水线变红，
 * 否则你还要多查一个跟代码质量无关的失败。
 */
@Slf4j
@RestController
public class FlowWebhookController {

    private final YunxiaoProperties yunxiaoProps;
    private final FeishuProperties feishuProps;
    private final YunxiaoClient yunxiaoClient;
    private final CardFactory cardFactory;
    private final FeishuMessageService messageService;
    private final WebhookGuard guard;
    private final IdempotentStore idempotent;

    public FlowWebhookController(YunxiaoProperties yunxiaoProps,
                                 FeishuProperties feishuProps,
                                 YunxiaoClient yunxiaoClient,
                                 CardFactory cardFactory,
                                 FeishuMessageService messageService,
                                 WebhookGuard guard,
                                 IdempotentStore idempotent) {
        this.yunxiaoProps = yunxiaoProps;
        this.feishuProps = feishuProps;
        this.yunxiaoClient = yunxiaoClient;
        this.cardFactory = cardFactory;
        this.messageService = messageService;
        this.guard = guard;
        this.idempotent = idempotent;
    }

    @PostMapping("/webhook/flow")
    public ResponseEntity<String> onFlowTrigger(
            @RequestParam(value = "repoName", defaultValue = "") String repoName,
            @RequestParam(value = "sourceBranch", defaultValue = "") String sourceBranch,
            @RequestParam(value = "targetBranch", defaultValue = "") String targetBranch,
            @RequestParam(value = "author", defaultValue = "") String author,
            @RequestParam(value = "title", defaultValue = "") String title,
            @RequestParam(value = "runNo", defaultValue = "") String runNo,
            @RequestHeader HttpHeaders headers) {

        // 和代码库入口共用同一套取头规则（认 X-Codeup-Token 和 X-Gitlab-Token）
        if (!guard.pass(guard.tokenOf(headers))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("forbidden");
        }

        try {
            return handle(repoName, sourceBranch, targetBranch, author, title, runNo);
        } catch (Exception e) {
            // 一律回 200：流水线那边不该因为我们的问题变红
            log.error("处理流水线触发失败 repo={} branch={}", repoName, sourceBranch, e);
            return ResponseEntity.ok("error: " + e.getMessage());
        }
    }

    private ResponseEntity<String> handle(String repoName, String sourceBranch,
                                          String targetBranch, String author,
                                          String title, String runNo) {

        String repoId = resolveRepoId(repoName);
        if (!StringUtils.hasText(repoId)) {
            log.warn("流水线传来未知仓库 [{}]，请在 yunxiao.repos 里配 名字 -> 仓库ID 的映射", repoName);
            return ResponseEntity.ok("skip: unknown repo " + repoName);
        }
        if (!StringUtils.hasText(sourceBranch)) {
            log.warn("流水线没传 sourceBranch，无法定位合并请求");
            return ResponseEntity.ok("skip: no sourceBranch");
        }

        log.info("收到流水线触发 repo={}({}) branch={} -> {} runNo={} title={}",
                repoName, repoId, sourceBranch, targetBranch, runNo, title);

        // 补齐流水线拿不到的那个值：合并请求编号
        String localId = yunxiaoClient.findOpenMergeRequestId(repoId, sourceBranch);
        if (!StringUtils.hasText(localId)) {
            // 常见原因：还没真的创建合并请求（只是推了分支）、已被合并、或分支名对不上
            log.warn("按源分支 [{}] 没找到待合并的请求，跳过。"
                    + "若确认已有合并请求，检查分支名是否一致、以及是否有打开中的请求", sourceBranch);
            return ResponseEntity.ok("skip: no open mr for branch " + sourceBranch);
        }

        // 同一个合并请求只提醒一次。流水线每次 push 都会触发，
        // 不去重的话外包改五轮代码你会收到五张卡片
        String key = "flow:" + repoId + ":" + localId;
        if (!idempotent.firstTime(key)) {
            log.info("该合并请求已通知过，跳过 repo={} mr={}", repoId, localId);
            return ResponseEntity.ok("dup");
        }

        MrView mr = yunxiaoClient.getChangeRequest(repoId, localId);
        List<CommitItem> commits = yunxiaoClient.commitsFor(repoId,
                mr.getSourceBranch(), mr.getTargetBranch());
        String card = cardFactory.approvalCard(repoName, repoId, localId, mr, commits,
                yunxiaoClient.webUrlFor(repoName, localId, mr));
        String messageId = messageService.sendCard(feishuProps.getChatId(), card);
        log.info("流水线触发 -> 已推卡片 repo={} mr={} branch={} messageId={}",
                repoId, localId, sourceBranch, messageId);
        return ResponseEntity.ok("ok");
    }

    /**
     * 仓库名转仓库 ID。
     * 优先查 yunxiao.repos 映射；没配的话，如果传过来的本身就是数字，直接当 ID 用。
     */
    private String resolveRepoId(String repoName) {
        if (!StringUtils.hasText(repoName)) {
            return "";
        }
        Map<String, String> repos = yunxiaoProps.getRepos();
        if (repos != null && StringUtils.hasText(repos.get(repoName))) {
            return repos.get(repoName);
        }
        if (repoName.matches("\\d+")) {
            return repoName;
        }
        return "";
    }
}
