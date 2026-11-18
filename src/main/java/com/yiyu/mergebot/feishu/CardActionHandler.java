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
 * 按实际执行顺序，一共六道关，缺一层都会出问题：
 *  1. 通道层：走长连接，服务主动连出去，飞书借着这条连接推事件，不需要验签；
 *  2. 人员层：白名单 + 本群成员。卡片发在群里，不校验等于谁拿到卡片都能合代码；
 *  3. 串行层：按合并请求加锁，同一条请求同时只允许一次操作（`MrLocks`）；
 *  4. 幂等层：同一张卡片的同一个动作只执行一次，防飞书重推和连点；
 *  5. 状态层：点之前重查一次 MR。卡片可能是半小时前发的，这期间代码可能又变了；
 *  6. 结果层：结果回写到卡片，群里所有人看到同一个结论，也不会有人再点。
 *
 * ⚠️ 第 3 层一定在第 4 层之前。反过来的话，拿锁失败也会消耗幂等名额，
 * 用户按提示再点一次就会被误判成"重复请求"，而实际一次都没执行。
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
        // ★ 顺序不能动：**先拿锁，再登记幂等**。
        // 反过来（原来就是反的）会出这种事：拿锁失败只弹一句"正在处理中"、卡片和按钮都不变，
        // 但幂等名额已经被占掉；用户按提示再点一次，就被当成"重复请求"直接返回，
        // 而这一次其实压根没执行。两个人同时点时最容易被当成"已经合过了"。
        ReentrantLock lock = mrLocks.of(repoId, localId);
        if (!lock.tryLock()) {
            resp.setToast(toast("warning", "这个合并请求正在处理中，请稍候再点"));
            return resp;
        }
        String idemKey = dupKey(messageId, repoId, localId, act);
        try {
            // 幂等层：飞书回调超时会重推，同一张卡片的同一个动作只执行一次。
            // 命中时不能一律回"已处理"——前面那次也可能失败过，
            // 所以按当前真实状态给结论：确实已合并才算成功。
            if (!idempotent.firstTime(idemKey)) {
                return alreadyHandled(resp, repoName, operator, repoId, localId, true);
            }

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
            // 接口返回成功不等于真合上了，再查一次确认。
            // 查不到就立刻再查一次（不加等待）——云效状态偶尔慢半拍，
            // 但这里不能 sleep：飞书要求卡片回调尽快返回，拖太久会被判超时并重推。
            MrView latest = yunxiao.getChangeRequest(repoId, localId);
            if (!"MERGED".equals(latest.getStatus())) {
                latest = yunxiao.getChangeRequest(repoId, localId);
            }
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
            // 这次没成功（超时 / 网络 / 云效报错），把登记撤掉，让审批人能立刻重试。
            // 不撤的话他会被告知"重复点击"，其实一次都没执行。
            idempotent.forget(idemKey);
            log.error("调云效合并失败 repo={} mr={}", repoId, localId, e);
            return fail(resp, repoName, operator, safeGet(repoId, localId), "云效返回失败：" + rootMessage(e));
        } finally {
            lock.unlock();
        }
    }

    private P2CardActionTriggerResponse doClose(P2CardActionTriggerResponse resp, String messageId,
                                                String operator, String repoName,
                                                String repoId, String localId, String act) throws Exception {
        // 同上：先锁后幂等，顺序不能反
        ReentrantLock lock = mrLocks.of(repoId, localId);
        if (!lock.tryLock()) {
            resp.setToast(toast("warning", "这个合并请求正在处理中，请稍候再点"));
            return resp;
        }
        String idemKey = dupKey(messageId, repoId, localId, act);
        try {
            if (!idempotent.firstTime(idemKey)) {
                return alreadyHandled(resp, repoName, operator, repoId, localId, false);
            }
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
            // 同上：没成功就撤销登记，允许立刻重试
            idempotent.forget(idemKey);
            log.error("调云效关闭失败 repo={} mr={}", repoId, localId, e);
            return fail(resp, repoName, operator, safeGet(repoId, localId), "云效返回失败：" + rootMessage(e));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 去重键。
     *
     * 正常用「卡片消息编号 + 动作」，同一张卡片的同一个动作只执行一次。
     * 消息编号缺失时（回调结构变化、转发卡片等）退化成「仓库 + 请求编号 + 动作」——
     * **不能直接用空值拼**，否则所有请求共用同一个键，
     * 第一个点完之后这台服务上其它所有请求都会被当成重复，再也合不了。
     */
    private String dupKey(String messageId, String repoId, String localId, String act) {
        if (StringUtils.hasText(messageId)) {
            return messageId + ":" + act;
        }
        log.warn("卡片回调没带消息编号，改用仓库+请求编号去重 repo={} mr={} act={}", repoId, localId, act);
        return "nb:" + repoId + ":" + localId + ":" + act;
    }

    /**
     * 重复点击的处理。
     *
     * 关键点：**结论必须按云效当前真实状态给**，不能一律报成功。
     * 原来这里固定渲染绿色「操作成功」卡（内容是"已经处理过，当前状态 X"），
     * 会让审批人以为合进去了，实际可能上一次根本没执行。
     *
     * @param merging true = 这次点的是合并；false = 点的是拒绝
     */
    private P2CardActionTriggerResponse alreadyHandled(P2CardActionTriggerResponse resp, String repoName,
                                                       String operator, String repoId, String localId,
                                                       boolean merging) throws Exception {
        MrView mr = safeGet(repoId, localId);
        boolean done = merging ? "MERGED".equals(mr.getStatus()) : "CLOSED".equals(mr.getStatus());
        String target = blank(mr.getTargetBranch(), "目标分支");

        resp.setToast(toast(done ? "success" : "info",
                done ? "这个请求已经" + (merging ? "合并" : "关闭") + "完成了"
                        : "这次点击和刚才重复，没有重复执行"));
        setCard(resp, cardFactory.resultCard(repoName, mr.getTitle(), done, operator,
                done ? "已经" + (merging ? "合并进 " + target : "拒绝（关闭）") + "，本次点击未重复执行"
                        : "重复点击，未重复执行。当前状态：" + blank(mr.getStatus(), "未知"),
                mr, mr.getDetailUrl()));
        log.info("重复点击已忽略 repo={} mr={} operator={} 当前状态={}",
                repoId, localId, operator, mr.getStatus());
        return resp;
    }

    private P2CardActionTriggerResponse fail(P2CardActionTriggerResponse resp, String repoName,
                                             String operator, MrView mr, String message) throws Exception {
        resp.setToast(toast("error", message));
        setCard(resp, cardFactory.resultCard(repoName, mr.getTitle(), false, operator,
                message, mr, mr.getDetailUrl()));
        return resp;
    }

    /** 查询失败时也要能出卡片，不能让异常把提示吞掉；但失败原因要留痕，否则只会看到一张空卡片 */
    private MrView safeGet(String repoId, String localId) {
        try {
            return yunxiao.getChangeRequest(repoId, localId);
        } catch (Exception e) {
            log.warn("查合并请求详情失败，卡片上部分字段会为空 repo={} mr={}：{}", repoId, localId, rootMessage(e));
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

    private String blank(String s, String fallback) {
        return StringUtils.hasText(s) ? s : fallback;
    }

    /**
     * 取最根本的那句错误说明。
     *
     * 原来的写法是"只有当前层没有 message 时才往下找"，但包装异常通常都有 message
     * （比如"发卡片失败: xxx"），于是永远停在最外层，真正的原因反而看不到。
     * 这里改成一路走到最里层，取最深的一句。
     */
    private String rootMessage(Throwable e) {
        String deepest = null;
        Throwable t = e;
        while (t != null) {
            if (StringUtils.hasText(t.getMessage())) {
                deepest = t.getMessage();
            }
            if (t.getCause() == null || t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }
        return deepest == null ? e.getClass().getSimpleName() : deepest;
    }
}
