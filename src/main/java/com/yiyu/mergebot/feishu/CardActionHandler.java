package com.yiyu.mergebot.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.CallBackCard;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.yiyu.mergebot.config.FeishuProperties;
import com.yiyu.mergebot.support.IdempotentStore;
import com.yiyu.mergebot.support.MrLocks;
import com.yiyu.mergebot.yunxiao.MrView;
import com.yiyu.mergebot.yunxiao.YunxiaoClient;
import com.yiyu.mergebot.yunxiao.YunxiaoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 卡片按钮点击回调处理。
 *
 * 五层校验，缺一层都会出问题：
 *  1. 通道层：走长连接，服务主动连出去，飞书借着这条连接推事件，不需要验签；
 *  2. 人员层：白名单 + 本群成员。卡片发在群里，不校验等于谁拿到卡片都能合代码；
 *  3. 状态层：点之前重查一次 MR。卡片可能是半小时前发的，这期间代码可能又变了；
 *  4. 幂等层：同一张卡片的同一个动作只处理一次，防重推和连点；
 *  5. 结果层：结果回写到卡片，群里所有人看到同一个结论，也不会有人再点。
 */
@Slf4j
@Component
public class CardActionHandler extends P2CardActionTriggerHandler {

    private final FeishuProperties props;
    private final CardFactory cardFactory;
    private final YunxiaoClient yunxiao;
    private final FeishuMessageService messageService;
    private final IdempotentStore idempotent;
    private final MrLocks mrLocks;
    private final ObjectMapper mapper = new ObjectMapper();

    public CardActionHandler(FeishuProperties props,
                             CardFactory cardFactory,
                             YunxiaoClient yunxiao,
                             FeishuMessageService messageService,
                             IdempotentStore idempotent,
                             MrLocks mrLocks) {
        this.props = props;
        this.cardFactory = cardFactory;
        this.yunxiao = yunxiao;
        this.messageService = messageService;
        this.idempotent = idempotent;
        this.mrLocks = mrLocks;
    }

    @Override
    public P2CardActionTriggerResponse handle(P2CardActionTrigger event) throws Exception {
        P2CardActionTriggerResponse resp = new P2CardActionTriggerResponse();
        try {
            P2CardActionTriggerData data = event.getEvent();
            if (data == null) {
                resp.setToast(toast("error", "回调内容为空"));
                return resp;
            }

            String openId = data.getOperator() == null ? "" : data.getOperator().getOpenId();
            String messageId = data.getContext() == null ? "" : data.getContext().getOpenMessageId();
            Map<String, Object> value = data.getAction() == null || data.getAction().getValue() == null
                    ? Collections.<String, Object>emptyMap()
                    : data.getAction().getValue();

            String act = str(value.get("act"));
            String repoId = str(value.get("repo"));
            String localId = str(value.get("mr"));
            String repoName = str(value.get("name"));

            log.info("卡片回调 act={} repo={} mr={} openId={} messageId={}",
                    act, repoId, localId, openId, messageId);

            // 人员层：白名单或本群成员。顺带把 open_id 回给对方，方便排查和加白名单
            if (!allowed(openId)) {
                log.warn("无权限用户点击 act={} openId={}", act, openId);
                resp.setToast(toast("error", "你没有合并权限。你的 open_id 是 " + openId));
                return resp;
            }

            if (!StringUtils.hasText(act) || !StringUtils.hasText(repoId) || !StringUtils.hasText(localId)) {
                resp.setToast(toast("error", "卡片参数缺失，请让提交人重新发起合并请求"));
                return resp;
            }

            String operator = messageService.userName(openId);

            switch (act) {
                case "merge": {
                    // 合并是不可逆动作，先弹确认卡片，不动代码
                    MrView mr = yunxiao.getChangeRequest(repoId, localId);
                    setCard(resp, cardFactory.confirmCard(repoName, repoId, localId, mr));
                    return resp;
                }
                case "cancel": {
                    MrView mr = yunxiao.getChangeRequest(repoId, localId);
                    // 取消合并后把卡片换回待审批态，内容和初次发卡保持一致，
                    // 否则会出现"刚发的卡片有提交列表、点一下取消就没了"的怪异现象
                    setCard(resp, cardFactory.approvalCard(repoName, repoId, localId, mr,
                            yunxiao.commitsFor(repoId, mr.getSourceBranch(), mr.getTargetBranch()),
                            yunxiao.webUrlFor(repoName, localId, mr)));
                    resp.setToast(toast("info", "已取消"));
                    return resp;
                }
                case "merge_ok":
                    return doMerge(resp, messageId, operator, repoName, repoId, localId, act);
                case "close":
                    return doClose(resp, messageId, operator, repoName, repoId, localId, act);
                default:
                    resp.setToast(toast("warning", "未知操作：" + act));
                    return resp;
            }
        } catch (Exception e) {
            log.error("处理卡片回调失败", e);
            resp.setToast(toast("error", "处理失败：" + rootMessage(e)));
            return resp;
        }
    }

    private P2CardActionTriggerResponse doMerge(P2CardActionTriggerResponse resp, String messageId,
                                                String operator, String repoName,
                                                String repoId, String localId, String act) throws Exception {
        if (!idempotent.firstTime(messageId + ":" + act)) {
            MrView mr = yunxiao.getChangeRequest(repoId, localId);
            resp.setToast(toast("info", "这个操作已经处理过了"));
            setCard(resp, cardFactory.resultCard(repoName, mr.getTitle(), true, operator,
                    "已经处理过，当前状态 " + mr.getStatus(), mr, mr.getDetailUrl()));
            return resp;
        }

        ReentrantLock lock = mrLocks.of(repoId, localId);
        if (!lock.tryLock()) {
            resp.setToast(toast("warning", "这个合并请求正在处理中，请稍候"));
            return resp;
        }
        try {
            // 状态层：卡片可能是很久以前发的，落笔前必须重查一次
            MrView mr = yunxiao.getChangeRequest(repoId, localId);
            if (!mr.mergeable()) {
                return fail(resp, repoName, operator, mr, "状态已变化，当前是 " + mr.getStatus() + "，不能再合并");
            }
            if (mr.hasConflict()) {
                return fail(resp, repoName, operator, mr, "存在代码冲突，必须由提交人自己解决，审批人不能代解");
            }
            if (!mr.isAllRequirementsPass()) {
                return fail(resp, repoName, operator, mr, "合并卡点未全部通过（评审 / 流水线 / 评论），先去云效处理");
            }

            yunxiao.merge(repoId, localId);
            // 接口返回成功不等于真合上了，再查一次确认
            MrView latest = yunxiao.getChangeRequest(repoId, localId);
            if ("MERGED".equals(latest.getStatus())) {
                resp.setToast(toast("success", "已合并"));
                setCard(resp, cardFactory.resultCard(repoName, latest.getTitle(), true, operator,
                        "已合并进 " + latest.getTargetBranch(), latest, latest.getDetailUrl()));
                log.info("合并完成 repo={} mr={} operator={}", repoId, localId, operator);
            } else {
                return fail(resp, repoName, operator, latest, "合并未生效，当前状态 " + latest.getStatus());
            }
            return resp;
        } catch (YunxiaoException e) {
            log.error("调云效合并失败 repo={} mr={}", repoId, localId, e);
            return fail(resp, repoName, operator, safeGet(repoId, localId), "云效返回失败：" + rootMessage(e));
        } finally {
            lock.unlock();
        }
    }

    private P2CardActionTriggerResponse doClose(P2CardActionTriggerResponse resp, String messageId,
                                                String operator, String repoName,
                                                String repoId, String localId, String act) throws Exception {
        if (!idempotent.firstTime(messageId + ":" + act)) {
            resp.setToast(toast("info", "这个操作已经处理过了"));
            return resp;
        }
        ReentrantLock lock = mrLocks.of(repoId, localId);
        if (!lock.tryLock()) {
            resp.setToast(toast("warning", "这个合并请求正在处理中，请稍候"));
            return resp;
        }
        try {
            MrView mr = yunxiao.getChangeRequest(repoId, localId);
            if ("CLOSED".equals(mr.getStatus()) || "MERGED".equals(mr.getStatus())) {
                return fail(resp, repoName, operator, mr, "已经是 " + mr.getStatus() + " 状态，不能再拒绝");
            }
            MrView after = yunxiao.close(repoId, localId);
            resp.setToast(toast("success", "已拒绝"));
            setCard(resp, cardFactory.resultCard(repoName, mr.getTitle(), true, operator,
                    "已拒绝（关闭）该合并请求", after, mr.getDetailUrl()));
            log.info("已拒绝 repo={} mr={} operator={}", repoId, localId, operator);
            return resp;
        } catch (YunxiaoException e) {
            log.error("调云效关闭失败 repo={} mr={}", repoId, localId, e);
            return fail(resp, repoName, operator, safeGet(repoId, localId), "云效返回失败：" + rootMessage(e));
        } finally {
            lock.unlock();
        }
    }

    private P2CardActionTriggerResponse fail(P2CardActionTriggerResponse resp, String repoName,
                                             String operator, MrView mr, String message) throws Exception {
        resp.setToast(toast("error", message));
        setCard(resp, cardFactory.resultCard(repoName, mr.getTitle(), false, operator,
                message, mr, mr.getDetailUrl()));
        return resp;
    }

    /** 查询失败时也要能出卡片，不能让异常把提示吞掉 */
    private MrView safeGet(String repoId, String localId) {
        try {
            return yunxiao.getChangeRequest(repoId, localId);
        } catch (Exception e) {
            return MrView.of(null);
        }
    }

    /**
     * 人员层：谁能点。
     *
     * 两条放行通道，满足任一条即可：
     *  1. 白名单——固定那几个人，写在配置里，不受群成员变动影响；
     *  2. 本群成员——卡片发在哪个群，那个群里的人都能点（feishu.allow-group-members 控制）。
     *
     * 加第二条的原因：维护名单是纯人力成本，加一个人要改配置、重启服务。
     * 换成"群里的人"之后，加人减人自动生效，最迟一个缓存周期。
     *
     * 不提供"完全不校验"的开关：卡片可以被转发，放开等于任何拿到卡片的人都能合代码。
     */
    private boolean allowed(String openId) {
        if (!StringUtils.hasText(openId)) {
            return false;
        }
        // 通道一：白名单
        if (props.getAllowedOpenIds() != null && props.getAllowedOpenIds().contains(openId)) {
            return true;
        }
        // 通道二：是不是这个群的成员
        if (!props.isAllowGroupMembers()) {
            return false;
        }
        if (messageService.isGroupMember(openId)) {
            return true;
        }
        log.warn("既不在白名单、也不在审批群 openId={}", openId);
        return false;
    }

    private void setCard(P2CardActionTriggerResponse resp, String cardJson) throws Exception {
        CallBackCard card = new CallBackCard();
        card.setType("raw");
        card.setData(mapper.readValue(cardJson, Map.class));
        resp.setCard(card);
    }

    private CallBackToast toast(String type, String content) {
        CallBackToast t = new CallBackToast();
        t.setType(type);
        t.setContent(content);
        return t;
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getMessage() == null) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }
}
