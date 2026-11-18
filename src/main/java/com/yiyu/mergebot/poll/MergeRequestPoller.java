package com.yiyu.mergebot.poll;

import com.yiyu.mergebot.config.FeishuProperties;
import com.yiyu.mergebot.config.PollProperties;
import com.yiyu.mergebot.feishu.CardFactory;
import com.yiyu.mergebot.feishu.FeishuMessageService;
import com.yiyu.mergebot.yunxiao.CommitItem;
import com.yiyu.mergebot.yunxiao.MrItem;
import com.yiyu.mergebot.yunxiao.MrView;
import com.yiyu.mergebot.yunxiao.YunxiaoClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合并请求轮询器。
 *
 * 它解决的问题：云效的 Webhook 只能往公网 URL 推送。服务在内网时收不到事件，
 * 于是反过来——服务每隔一段时间主动问云效"有没有新的待合并请求"。
 *
 * 这样整条链路**一个公网端口都不需要开**：
 *   出站 1：服务 -> 云效，查询待合并列表
 *   出站 2：服务 -> 飞书，发交互卡片
 *   入站 0：飞书按钮回调走长连接（服务主动连出去，不是别人连进来）
 *
 * 触发一次合并的完整路径：
 *   外包提 MR -> 下一轮轮询发现 -> 发带「合并/拒绝」的卡片 -> 你手机点合并
 *   -> 长连接收到 card.action.trigger -> CardActionHandler 调云效 merge 接口
 *
 * 去重方式：按「合并请求编号 + 内容指纹」记录。指纹没变就不重复发，
 * 代码推了新提交（ahead 变）才重发一张新卡，避免每 60 秒刷一次屏。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mergebot.poll", name = "enabled", havingValue = "true")
public class MergeRequestPoller {

    private final YunxiaoClient yunxiao;
    private final FeishuMessageService feishu;
    private final CardFactory cards;
    private final PollProperties poll;
    private final FeishuProperties feishuProps;

    /** key = 仓库ID:合并请求编号，value = 上次发卡片时的内容指纹 */
    private final Map<String, String> lastSent = new ConcurrentHashMap<>();

    public MergeRequestPoller(YunxiaoClient yunxiao, FeishuMessageService feishu,
                              CardFactory cards, PollProperties poll, FeishuProperties feishuProps) {
        this.yunxiao = yunxiao;
        this.feishu = feishu;
        this.cards = cards;
        this.poll = poll;
        this.feishuProps = feishuProps;
    }

    @Scheduled(initialDelay = 15_000, fixedDelayString = "${mergebot.poll.interval-ms:60000}")
    public void scan() {
        if (poll.getRepoIds().isEmpty()) {
            log.warn("轮询已开启，但 mergebot.poll.repo-ids 是空的，什么都不会做。请填仓库 ID（URL 里那串数字）");
            return;
        }
        if (!StringUtils.hasText(feishuProps.getChatId())) {
            log.warn("轮询已开启，但 feishu.chat-id 是空的，卡片没地方发");
            return;
        }

        Set<String> seen = new HashSet<>();

        for (String repoId : poll.getRepoIds()) {
            try {
                scanRepo(repoId, seen);
            } catch (Exception e) {
                // 单个仓库失败不能中断整轮，否则一个仓库 ID 写错就全都不工作了
                log.error("轮询仓库 {} 失败：{}", repoId, e.getMessage(), e);
            }
        }

        // 已经从列表里消失的（已合并/已关闭）不再占位，清理掉，
        // 这样同一个合并请求将来被重新打开时还能再发一张卡
        lastSent.keySet().retainAll(seen);
    }

    private void scanRepo(String repoId, Set<String> seen) {
        String repoName = poll.getRepoNames().getOrDefault(repoId, repoId);
        List<MrItem> items = yunxiao.listOpenChangeRequests(repoId);
        log.debug("仓库 {} 待合并请求 {} 条", repoName, items.size());

        for (MrItem item : items) {
            String key = repoId + ":" + item.getLocalId();
            seen.add(key);

            MrView brief = item.getMr();

            if (!targetMatched(brief.getTargetBranch())) {
                continue;
            }

            String fingerprint = item.fingerprint();
            if (fingerprint.equals(lastSent.get(key))) {
                continue;   // 没变化，不重复打扰
            }

            // 列表接口带不带 allRequirementsPass / conflictCheckStatus 不确定，
            // 发卡片前补一次详情，让按钮能不能点、为什么不能点一次判断准确
            MrView full = brief;
            try {
                full = yunxiao.getChangeRequest(repoId, item.getLocalId());
            } catch (Exception e) {
                log.warn("查合并请求详情失败，用列表数据兜底 repo={} mr={}：{}",
                        repoId, item.getLocalId(), e.getMessage());
            }

            try {
                List<CommitItem> commits = yunxiao.commitsFor(repoId,
                        full.getSourceBranch(), full.getTargetBranch());
                String card = cards.approvalCard(repoName, repoId, item.getLocalId(), full, commits,
                        yunxiao.webUrlFor(repoName, item.getLocalId(), full));
                String messageId = feishu.sendCard(feishuProps.getChatId(), card);
                lastSent.put(key, fingerprint);
                log.info("已推送合并请求卡片 repo={} mr={} 分支 {} -> {} messageId={}",
                        repoName, item.getLocalId(), full.getSourceBranch(),
                        full.getTargetBranch(), messageId);
            } catch (Exception e) {
                // 发失败不写指纹，下一轮自动重试
                log.error("推送卡片失败 repo={} mr={}：{}", repoName, item.getLocalId(), e.getMessage());
            }
        }
    }

    /**
     * 目标分支过滤。
     *
     * 配置为空 = 不过滤，全都要。
     * 列表接口没返回 targetBranch 时**放行**——宁可多推一张卡，
     * 也不要因为字段缺失就静默漏掉一个真请求。
     */
    private boolean targetMatched(String targetBranch) {
        List<String> wanted = poll.getTargetBranches();
        if (wanted == null || wanted.isEmpty()) {
            return true;
        }
        if (!StringUtils.hasText(targetBranch)) {
            return true;
        }
        return wanted.contains(targetBranch);
    }

    /** 给 TestController 用：看看现在记录了哪些已经发过的 */
    public Map<String, String> snapshot() {
        return new ConcurrentHashMap<>(lastSent);
    }
}
