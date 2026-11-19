package com.yiyu.mergebot.config;

import com.yiyu.mergebot.support.Texts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动前校验配置，缺关键项直接不让启动。
 *
 * 为什么要在启动时炸而不是运行时报错：
 * 这套服务部署在公网，最危险的失败模式是"看起来在跑，实际上门是开的"。
 * 比如 webhook-token 忘了填，服务照常启动、日志一切正常，
 * 但任何人都能 POST 进来让机器人在群里发卡片。靠人记住"上线前检查配置"不可靠。
 *
 * 本地调试嫌麻烦的话，webhook-token 随便填一个值就行（比如 dev-local），
 * 只要云效 Webhook 配置里填同一个值就能通。
 */
@Slf4j
@Component
public class StartupValidator implements InitializingBean {

    private final YunxiaoProperties yunxiao;
    private final FeishuProperties feishu;
    private final PollProperties poll;

    public StartupValidator(YunxiaoProperties yunxiao, FeishuProperties feishu, PollProperties poll) {
        this.yunxiao = yunxiao;
        this.feishu = feishu;
        this.poll = poll;
    }

    @Override
    public void afterPropertiesSet() {
        require("yunxiao.token", yunxiao.getToken());
        require("feishu.app-id", feishu.getAppId());
        require("feishu.app-secret", feishu.getAppSecret());
        require("feishu.chat-id", feishu.getChatId());

        // 触发方式至少要有一条，否则服务起来了也永远不会发卡片
        boolean hasWebhookToken = !Texts.isPlaceholder(yunxiao.getWebhookToken());
        if (!poll.isEnabled() && !hasWebhookToken) {
            throw new IllegalStateException("没有任何触发方式，服务不启动。二选一：\n"
                    + "  1) 没有公网：把 mergebot.poll.enabled 改成 true，并填 mergebot.poll.repo-ids；\n"
                    + "  2) 有公网：填 yunxiao.webhook-token（任意非空字符串），"
                    + "并去云效代码库 -> 设置 -> WebHooks 填同一个值");
        }
        if (poll.isEnabled() && (poll.getRepoIds() == null || poll.getRepoIds().isEmpty())) {
            log.warn("轮询已开启但 mergebot.poll.repo-ids 是空的，什么都不会做。"
                    + "填仓库 ID：打开云效合并请求，URL 里 /repositories/{这串数字}/changeRequests/");
        }
        if (!hasWebhookToken) {
            log.info("yunxiao.webhook-token 未配置：/webhook/yunxiao 会拒绝所有请求（这是安全默认值）。"
                    + "只用轮询触发的话不用管这条");
        }

        if (!StringUtils.hasText(yunxiao.getOrgId())) {
            log.warn("yunxiao.org-id 为空，接口会走 Region 版路径。"
                    + "如果你用的是 codeup.aliyun.com 的组织，这一项必须填组织 ID");
        }
        if (feishu.getAllowedOpenIds() == null || feishu.getAllowedOpenIds().isEmpty()) {
            if (feishu.isAllowGroupMembers()) {
                log.info("feishu.allowed-open-ids 为空，已开启群成员可点：审批群里的人都能操作。"
                        + "前提是应用打开了查看群成员的权限，否则所有人都点不动");
            } else {
                log.warn("feishu.allowed-open-ids 为空，且 allow-group-members 已关闭："
                        + "任何人在群里点按钮都会被拒绝。先随便点一次，气泡里会显示你的 open_id");
            }
        }
        if (yunxiao.getRepos() == null || yunxiao.getRepos().isEmpty()) {
            log.debug("yunxiao.repos 为空：只影响 /webhook/flow 那条路，其余功能不受影响");
        }
        log.info("配置校验通过，开始连接飞书");
    }

    private void require(String key, String value) {
        if (Texts.isPlaceholder(value)) {
            throw new IllegalStateException("配置项 [" + key + "] 未填写，服务不启动。"
                    + "本地开发改项目根目录的 .env（该文件不进仓库，模板见 .env.example）；"
                    + "服务器上改 jar 同级的 application.yml（start.sh 用的是那份）。"
                    + "其中 yunxiao.webhook-token 填任意非空字符串即可，"
                    + "但必须和云效 Webhook 配置里的 Secret Token 完全一致");
        }
    }
}
