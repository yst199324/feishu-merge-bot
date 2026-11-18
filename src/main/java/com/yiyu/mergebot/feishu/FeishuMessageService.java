package com.yiyu.mergebot.feishu;

import com.lark.oapi.Client;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 飞书消息发送 + 审批群成员判断。
 */
@Slf4j
@Service
public class FeishuMessageService {

    /** 翻页上限：审批群不会超过这个规模，防止接口异常时死循环 */
    private static final int MAX_MEMBER_PAGES = 5;
    private static final int PAGE_SIZE = 100;

    /** 姓名缓存有效期。姓名不常变，10 分钟足够；到点后重新拉，改名最多 10 分钟生效 */
    private static final int NAME_CACHE_TTL_MINUTES = 10;

    /** 姓名缓存容量上限。审批的人不会超过这个数，纯粹防内存无上限增长 */
    private static final int NAME_CACHE_MAX_SIZE = 500;

    /** 查姓名的重试间隔：失败后先等 500ms，再 1s，再 2s，共 3 次重试机会 */
    private static final long[] NAME_RETRY_DELAY_MS = {500L, 1000L, 2000L};

    /** 群成员名单的最小刷新间隔。缓存没命中时最多这么频繁打一次接口，防止被反复点击放大 */
    private static final long MEMBER_MIN_REFRESH_MS = 60 * 1000L;

    private final Client client;
    private final FeishuProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * open_id -> 姓名。
     *
     * 用 Guava 的 `refreshAfterWrite` 而不是手写"存时间戳 + 每次判断过期"，因为它天生就是
     * 这个需求要的语义：
     *   - 写入后 10 分钟内直接返回缓存，不打接口；
     *   - 超过 10 分钟后的第一次读取会**同步**去飞书拉新名字（所以改名最多 10 分钟生效）；
     *   - **刷新失败时保留旧值并用旧值返回**——一次接口抖动不该让卡片上的名字变成"未知用户"；
     *   - 顺带限制容量，不会因为来的人多而无限涨内存。
     *
     * load 里查不到名字就抛异常、不返回空串：Guava 会把返回值缓存住，
     * 返回空串等于把"这人查不到"也缓存了 10 分钟，权限补好后要等过期才生效。
     */
    private final LoadingCache<String, String> nameCache;

    /** 上次拉群成员名单的时间，用来限制刷新频率 */
    private volatile long memberLastRefreshAt;

    /** 审批群成员 open_id 集合，整批缓存 */
    private volatile Set<String> memberIds = Collections.emptySet();

    /** 上面这份缓存的有效期截止时刻 */
    private volatile long memberCacheExpireAt = 0L;

    public FeishuMessageService(Client client, FeishuProperties props) {
        this.client = client;
        this.props = props;
        this.nameCache = CacheBuilder.newBuilder()
                .maximumSize(NAME_CACHE_MAX_SIZE)
                .refreshAfterWrite(NAME_CACHE_TTL_MINUTES, TimeUnit.MINUTES)
                .build(new CacheLoader<String, String>() {
                    @Override
                    public String load(String openId) {
                        String name = fetchUserName(openId);
                        if (!StringUtils.hasText(name)) {
                            // 抛出去 = 这个结论不缓存，下次点击还会再试一次
                            throw new IllegalStateException("查不到姓名 openId=" + openId);
                        }
                        return name;
                    }
                });
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

    /**
     * 发纯文本。
     *
     * 内容必须走 JSON 序列化：原先是手写 {"text":"..."}，只把双引号替换掉，
     * 一旦文本里有换行、反斜杠或制表符，拼出来就是非法 JSON，飞书直接拒收。
     * 令牌提醒那种多行消息必然带换行，所以这里改掉。
     */
    public void sendText(String chatId, String text) {
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("text", text);
            CreateMessageReq req = CreateMessageReq.newBuilder()
                    .receiveIdType("chat_id")
                    .createMessageReqBody(CreateMessageReqBody.newBuilder()
                            .receiveId(chatId)
                            .msgType("text")
                            .content(mapper.writeValueAsString(payload))
                            .build())
                    .build();
            client.im().message().create(req);
        } catch (Exception e) {
            log.error("发文本失败：{}", e.getMessage());
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

    /**
     * 用 open_id 换人名。查不到返回空字符串，由 {@link #displayName} 兜底成"未知用户（末 6 位）"。
     *
     * 缓存行为全交给 Guava（见 nameCache 的注释）：10 分钟内直接命中；
     * 超过 10 分钟后第一次读会去飞书拉新名字，**拉取失败保留旧名**。
     */
    public String userName(String openId) {
        if (!StringUtils.hasText(openId)) {
            return "";
        }
        try {
            String name = nameCache.get(openId);
            return StringUtils.hasText(name) ? name : "";
        } catch (Exception e) {
            // 首次加载就失败（load 里抛了异常，所以不会缓存）。
            // 具体原因 fetchUserName 已经打过 warn，这里只补一句便于串流程
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.debug("查用户姓名失败，本次不缓存 openId={}：{}", openId, cause.getMessage());
            return "";
        }
    }

    /**
     * 调通讯录接口取姓名，失败按 500ms / 1s / 2s 递增重试。
     *
     * 只对**异常**（网络、超时、服务端 5xx）重试；像权限不足、用户不存在这种业务错误，
     * 重试多少次结果都一样，白等——所以 resp 不成功时直接返回。
     *
     * ⚠️ 最坏情况会阻塞约 3.5 秒（三段间隔之和）。这只发生在"缓存过期 + 接口刚好故障"时，
     * 很少见。如果真遇到回调超时，把 NAME_RETRY_DELAY_MS 最后一项去掉即可。
     */
    private String fetchUserName(String openId) {
        for (int attempt = 0; ; attempt++) {
            try {
                GetUserResp resp = client.contact().user().get(GetUserReq.newBuilder()
                        .userId(openId)
                        .userIdType("open_id")
                        .build());
                if (resp.success() && resp.getData() != null && resp.getData().getUser() != null) {
                    String name = resp.getData().getUser().getName();
                    if (StringUtils.hasText(name)) {
                        return name;
                    }
                }
                log.warn("查用户姓名失败 openId={} code={} msg={}（业务错误，不重试）。"
                                + "常见原因：应用没开通「通讯录 → 获取用户基本信息」，或该用户不在应用可用范围内",
                        openId, resp.getCode(), resp.getMsg());
                return "";
            } catch (Exception e) {
                if (attempt >= NAME_RETRY_DELAY_MS.length) {
                    log.warn("查用户姓名异常，重试 {} 次仍失败 openId={}：{}",
                            NAME_RETRY_DELAY_MS.length, openId, e.getMessage());
                    return "";
                }
                long delay = NAME_RETRY_DELAY_MS[attempt];
                log.warn("查用户姓名异常（{}），{}ms 后第 {} 次重试 openId={}",
                        e.getMessage(), delay, attempt + 1, openId);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return "";
                }
            }
        }
    }

    /**
     * 卡片上显示的操作人：**姓名 + open_id 末 6 位**。
     *
     * 为什么带 id 尾巴：群里可能有两个同名的人（同名同事、两个都叫"小王"的外包），
     * 只写姓名分不清是谁点的；写完整 open_id 又太长、也没人看得懂。
     * 末 6 位足够区分，而且和审计日志里的完整 id 能对上。
     */
    public String displayName(String openId) {
        String tail = tail(openId, 6);
        String name = userName(openId);
        return StringUtils.hasText(name) ? name + "（" + tail + "）" : "未知用户（" + tail + "）";
    }

    private String tail(String s, int n) {
        if (!StringUtils.hasText(s)) {
            return "??????";
        }
        String t = s.trim();
        return t.length() <= n ? t : t.substring(t.length() - n);
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
        // 缓存里没命中会走 force 刷新（为了照顾刚进群的人），但要限频率：
        // 否则一个人反复点按钮，每次都触发一次全量拉取（最多 5 页），接口压力被放大
        if (force && now - memberLastRefreshAt < MEMBER_MIN_REFRESH_MS) {
            return;
        }
        memberLastRefreshAt = now;
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
