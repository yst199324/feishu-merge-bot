package com.yiyu.mergebot.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.service.contact.v3.model.GetUserReq;
import com.lark.oapi.service.contact.v3.model.GetUserResp;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.GetChatMembersReq;
import com.lark.oapi.service.im.v1.model.GetChatMembersResp;
import com.lark.oapi.service.im.v1.model.ListChatReq;
import com.lark.oapi.service.im.v1.model.ListChatResp;
import com.lark.oapi.service.im.v1.model.ListMember;
import com.yiyu.mergebot.config.FeishuProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 飞书消息发送 + 审批群成员判断。
 */
@Slf4j
@Service
public class FeishuMessageService {

    /** 翻页上限：审批群不会超过这个规模，防止接口异常时死循环 */
    private static final int MAX_MEMBER_PAGES = 5;
    private static final int PAGE_SIZE = 100;

    private final Client client;
    private final FeishuProperties props;

    /** 审批群成员 open_id 集合，整批缓存 */
    private volatile Set<String> memberIds = Collections.emptySet();

    /** 上面这份缓存的有效期截止时刻 */
    private volatile long memberCacheExpireAt = 0L;

    public FeishuMessageService(Client client, FeishuProperties props) {
        this.client = client;
        this.props = props;
    }

    /** 发送交互卡片，返回 message_id */
    public String sendCard(String chatId, String cardJson) {
        try {
            CreateMessageReq req = CreateMessageReq.newBuilder()
                    .receiveIdType("chat_id")
                    .createMessageReqBody(CreateMessageReqBody.newBuilder()
                            .receiveId(chatId)
                            .msgType("interactive")
                            .content(cardJson)
                            .build())
                    .build();
            CreateMessageResp resp = client.im().message().create(req);
            if (!resp.success()) {
                throw new IllegalStateException("发卡片失败 code=" + resp.getCode() + " msg=" + resp.getMsg());
            }
            return resp.getData().getMessageId();
        } catch (Exception e) {
            throw new IllegalStateException("发卡片失败: " + e.getMessage(), e);
        }
    }

    /** 发纯文本，排错时用 */
    public void sendText(String chatId, String text) {
        try {
            CreateMessageReq req = CreateMessageReq.newBuilder()
                    .receiveIdType("chat_id")
                    .createMessageReqBody(CreateMessageReqBody.newBuilder()
                            .receiveId(chatId)
                            .msgType("text")
                            .content("{\"text\":\"" + text.replace("\"", "\\\"") + "\"}")
                            .build())
                    .build();
            client.im().message().create(req);
        } catch (Exception e) {
            log.error("发文本失败", e);
        }
    }

    /** 机器人所在的群列表，用来查 chat_id */
    public ListChatResp listChats() throws Exception {
        ListChatResp resp = client.im().chat().list(ListChatReq.newBuilder().pageSize(50).build());
        // 接口失败时 getData() 是 null，直接取 items 会抛空指针，
        // 真实的飞书错误码就被埋掉了。这里主动转成带 code/msg 的异常。
        if (!resp.success() || resp.getData() == null) {
            throw new IllegalStateException("查群列表失败 code=" + resp.getCode()
                    + " msg=" + resp.getMsg()
                    + "（多为缺「获取群组信息」权限，或权限还没随版本发布生效）");
        }
        return resp;
    }

    /** 用 open_id 换人名，失败就退回 open_id 本身 */
    public String userName(String openId) {
        try {
            GetUserResp resp = client.contact().user().get(GetUserReq.newBuilder()
                    .userId(openId)
                    .userIdType("open_id")
                    .build());
            if (resp.success() && resp.getData() != null && resp.getData().getUser() != null) {
                return resp.getData().getUser().getName();
            }
        } catch (Exception e) {
            log.debug("查用户信息失败 openId={}: {}", openId, e.getMessage());
        }
        return openId;
    }

    /**
     * 判断某个用户是不是审批群的成员。
     *
     * 实现说明：飞书有个"判断用户是否在群里"的接口，但那个接口只带 chat_id，
     * 用应用身份调用时判断的是**机器人自己**在不在群里，判断不了任意用户。
     * 所以这里改成拉群成员列表在本地比对。
     *
     * 缓存策略（三句话）：
     *  1. 整批成员缓存 group-member-cache-seconds 秒，命中就不用打接口；
     *  2. 缓存里没找到时，强制刷一次再判断——刚被拉进群的人不用等缓存过期；
     *  3. 接口失败保留上一份缓存（一次网络抖动不该让所有人点不动），
     *     如果从来没有成功拿到过，就一律判为"不是成员"（宁可点不动，不可越权）。
     */
    public boolean isGroupMember(String openId) {
        String chatId = props.getChatId();
        if (!StringUtils.hasText(chatId) || !StringUtils.hasText(openId)) {
            return false;
        }
        if (System.currentTimeMillis() > memberCacheExpireAt) {
            refreshMembers(chatId);
        }
        if (memberIds.contains(openId)) {
            return true;
        }
        // 没命中：可能是刚进群的人，绕过缓存再查一次
        refreshMembers(chatId, true);
        return memberIds.contains(openId);
    }

    private synchronized void refreshMembers(String chatId) {
        refreshMembers(chatId, false);
    }

    /**
     * @param force true 表示不管缓存有没有过期都重新拉
     */
    private synchronized void refreshMembers(String chatId, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now < memberCacheExpireAt) {
            // 并发进来时，前一个线程刚刷过，直接用
            return;
        }
        Set<String> ids = new HashSet<>();
        String pageToken = null;
        try {
            for (int page = 0; page < MAX_MEMBER_PAGES; page++) {
                GetChatMembersReq.Builder builder = GetChatMembersReq.newBuilder()
                        .chatId(chatId)
                        .memberIdType("open_id")
                        .pageSize(PAGE_SIZE);
                if (StringUtils.hasText(pageToken)) {
                    builder.pageToken(pageToken);
                }
                GetChatMembersResp resp = client.im().chatMembers().get(builder.build());
                if (!resp.success() || resp.getData() == null) {
                    throw new IllegalStateException("code=" + resp.getCode() + " msg=" + resp.getMsg());
                }
                ListMember[] items = resp.getData().getItems();
                if (items != null) {
                    for (ListMember item : items) {
                        if (item != null && StringUtils.hasText(item.getMemberId())) {
                            ids.add(item.getMemberId());
                        }
                    }
                }
                if (!Boolean.TRUE.equals(resp.getData().getHasMore())) {
                    break;
                }
                pageToken = resp.getData().getPageToken();
                if (!StringUtils.hasText(pageToken)) {
                    break;
                }
            }
            int ttl = props.getGroupMemberCacheSeconds();
            memberIds = Collections.unmodifiableSet(ids);
            memberCacheExpireAt = now + Math.max(ttl, 0) * 1000L;
            log.info("审批群成员已刷新，共 {} 人，缓存 {} 秒", ids.size(), ttl);
        } catch (Exception e) {
            // 保留旧缓存，别因为一次抖动把所有人挡在外面
            log.warn("拉群成员列表失败，沿用上次结果（{} 人）: {}", memberIds.size(), e.getMessage());
            if (memberIds.isEmpty()) {
                // 从来没成功过，给一个很短的过期时间，下次点击立刻重试
                memberCacheExpireAt = now;
            }
        }
    }

    public String defaultChatId() {
        return props.getChatId();
    }
}
