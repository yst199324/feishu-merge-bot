package com.yiyu.mergebot.feishu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiyu.mergebot.yunxiao.CommitItem;
import com.yiyu.mergebot.yunxiao.MrView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 卡片构造。全部使用卡片 JSON 2.0 结构。
 *
 * 两个关键点（写错就会出现"点了没反应"）：
 *  1. 2.0 结构不再支持 tag=action 的交互模块，按钮直接放进 body.elements；
 *  2. 按钮点击回调必须写在 behaviors 里（type=callback），不是按钮的 value 字段。
 */
@Component
public class CardFactory {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 待审批卡片（带提交内容和云效链接）。
     *
     * 注意这里**没有"不带 commits"的简化重载**：曾经有一个 4 参数的版本，
     * 签名上写着支持 mr 为 null，方法体里却直接调 mr.mergeable()，
     * 真为 null 就会空指针。反正没人用到，删掉比留着"看着安全其实会炸"强。
     *
     * @param commits 本次带的提交，可为空（查不到就不显示这一段，不影响卡片发出去）
     * @param webUrl  合并请求在云效网页上的地址，可为空（空则回落到详情接口返回的 detailUrl）
     */
    public String approvalCard(String repoName, String repoId, String localId, MrView mr,
                               List<CommitItem> commits, String webUrl) {
        return approvalCard(repoName, repoId, localId, mr, commits, webUrl, false);
    }

    /**
     * @param latestOnly true 表示 commits 里只有"最后一条提交"（来自 Webhook 报文的 last_commit），
     *                   不是完整列表。这时标题写「最近提交」而不是「本次提交（N 个）」，
     *                   并提示总条数——一次推了 5 个提交却只显示 1 条，写"1 个"会让人以为只改了一处。
     */
    public String approvalCard(String repoName, String repoId, String localId, MrView mr,
                               List<CommitItem> commits, String webUrl, boolean latestOnly) {
        List<Object> elements = new ArrayList<>();
        elements.add(markdown(summary(repoName, repoId, localId, mr)));

        // 说明：审核的人最先想知道"这个改动是干什么的"，没有的话明确写"未填写"，
        // 而不是悄悄省略——省略会让人以为是卡片渲染出了问题
        String desc = trim(mr == null ? null : mr.getDescription(), 200);
        if (!desc.isEmpty()) {
            elements.add(markdown("<font color='grey'>说明</font>\n" + esc(desc)));
        }

        // 本次提交。多的时候只列前 5 条，避免卡片被撑爆（飞书对卡片体积有上限）
        List<CommitItem> list = commits == null ? new ArrayList<>() : commits;
        if (!list.isEmpty()) {
            elements.add(markdown(commitsBlock(list, mr == null ? 0 : mr.getAhead(), latestOnly)));
        }

        boolean canMerge = mr.mergeable() && !mr.hasConflict() && mr.isAllRequirementsPass();

        Map<String, Object> mergeValue = value("merge", repoName, repoId, localId);
        Map<String, Object> closeValue = value("close", repoName, repoId, localId);

        Map<String, Object> mergeBtn = button("合并到 " + blank(mr.getTargetBranch(), "未知分支"),
                canMerge ? "primary" : "default", mergeValue);
        if (!canMerge) {
            mergeBtn.put("disabled", true);
            mergeBtn.put("disabled_tips", plain("存在冲突或卡点未通过，不能合并。点拒绝或让提交人处理"));
        }

        elements.add(buttons(mergeBtn, button("拒绝", "danger", closeValue)));

        // 云效链接。**纯文本单独一行**是故意的：
        // 手机上点按钮只能在本机浏览器打开，想"发到电脑上接着看"就得能复制，
        // 而按钮上的文字是复制不了的，只有正文里的链接可以长按复制。
        String url = blank(webUrl, mr == null ? "" : blank(mr.getDetailUrl(), ""));
        if (!url.isEmpty()) {
            elements.add(markdown("合并请求：" + url));
            elements.add(linkButton("在云效打开", "default", url));
        }

        elements.add(markdown("<font color='grey'>机器人自动检查：冲突 "
                + mr.conflictText() + "；卡点 " + mr.requirementsText()
                + behindHint(mr) + "</font>"));

        // 标题放 header 大字位置。
        //
        // 为什么不是把项目名放 header：群消息列表只显示这一行，
        // 而"这次要合的是什么改动"（标题）比"哪个仓库"更决定要不要点开。
        // 项目名移到正文第一行，点开就看见，多个项目共群也不会混。
        // 标题为空时才回落到项目名——否则会出来一张"待审批 · （无标题）"，
        // 群里完全看不出是哪个项目提的。
        String mrTitle = mr == null ? "" : blank(mr.getTitle(), "");
        String headTitle;
        if (!mrTitle.isEmpty()) {
            headTitle = "待审批 · " + shorten(mrTitle, 24);
        } else if (!blank(repoName, "").isEmpty()) {
            headTitle = "待审批 · " + shorten(repoName, 24);
        } else {
            headTitle = "合并请求待审批";
        }

        return toJson(card("blue", headTitle, elements));
    }

    /**
     * 本次提交那一段。
     *
     * 两种来源，文案必须区分开，否则会误导审核人：
     *   - 完整列表（调"比较两个分支"接口拿的）：写「本次提交（N 个）」，列前 5 条，多的写"另有 X 个"
     *   - 只有最后一条（Webhook 报文的 last_commit）：写「最近提交」，并明确说总共 N 个
     *
     * 只显示 1 条却写"本次提交（1 个）"是最坏的情况——一次推了 5 个提交，
     * 审核的人会以为只改了一处就点了合并。
     */
    private String commitsBlock(List<CommitItem> commits, int ahead, boolean latestOnly) {
        int total = Math.max(ahead, commits.size());
        StringBuilder sb = new StringBuilder();
        sb.append("<font color='grey'>")
                .append(latestOnly ? "最近提交" : "本次提交（" + total + " 个）")
                .append("</font>\n");

        int show = Math.min(commits.size(), 5);
        for (int i = 0; i < show; i++) {
            CommitItem c = commits.get(i);
            // 不用反引号包提交号：飞书卡片对行内代码的渲染各版本不一样，
            // 万一不认就会把反引号原样显示出来，很难看。用普通文本 + 空格分隔即可
            sb.append(c.getShortId()).append("　").append(esc(c.getMessage()));
            if (!c.getAuthor().isEmpty()) {
                sb.append("　").append(esc(c.getAuthor()));
            }
            sb.append("\n");
        }

        if (latestOnly) {
            // ahead 拿不到（0）时就不提总数，免得写出"共 1 个"这种反而错的话
            if (total > show) {
                sb.append("<font color='grey'>这条合并请求共 ").append(total)
                        .append(" 个提交，点下方链接看全部</font>");
            }
        } else if (total > show) {
            sb.append("<font color='grey'>…另有 ").append(total - show)
                    .append(" 个提交，点下方链接去云效看全部</font>");
        }
        return sb.toString().trim();
    }

    /** 目标分支已经往前走了，提示一句——不拦，但提交人可能要先合并一下目标分支 */
    private String behindHint(MrView mr) {
        if (mr == null || mr.getBehind() <= 0) {
            return "";
        }
        return "；目标分支已前进 " + mr.getBehind() + " 个提交";
    }

    /** 二次确认卡片：合并是不可逆动作，多一次点击换掉一整类误操作 */
    public String confirmCard(String repoName, String repoId, String localId, MrView mr) {
        List<Object> elements = new ArrayList<>();
        elements.add(markdown("**确认要把这个合并请求合进 "
                + esc(blank(mr.getTargetBranch(), "目标分支")) + " 吗？**\n"
                + "项目：" + esc(blank(repoName, "repoId=" + repoId)) + "\n"
                + "分支：" + esc(blank(mr.getSourceBranch(), "未知"))
                + " → **" + esc(blank(mr.getTargetBranch(), "未知")) + "**\n"
                + "发起人：" + esc(blank(mr.getAuthorName(), "未知")) + "\n"
                + "发起时间：" + esc(formatTime(mr.getCreatedAt()))));

        Map<String, Object> okValue = value("merge_ok", repoName, repoId, localId);
        Map<String, Object> cancelValue = value("cancel", repoName, repoId, localId);

        elements.add(buttons(
                button("确认合并", "primary_filled", okValue),
                button("取消", "default", cancelValue)));

        // header 也带上标题：合并是不可逆操作，确认时看错请求是最贵的一类错。
        // 大标题里写清楚"确认的是哪一件事"，比正文里描述一百字都顶用。
        String title = blank(mr.getTitle(), "");
        return toJson(card("orange", title.isEmpty() ? "二次确认" : "二次确认 · " + shorten(title, 20), elements));
    }

    /** 结果卡片：操作完成后回写，把按钮换成结论，避免重复点击 */
    public String resultCard(String repoName, String taskTitle, boolean success, String operator,
                             String message, MrView mr, String detailUrl) {
        List<Object> elements = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(esc(taskTitle)).append("**\n");
        sb.append("项目：").append(esc(blank(repoName, "未知"))).append("\n");
        if (mr != null) {
            sb.append("分支：").append(esc(mr.getSourceBranch()))
                    .append(" → ").append(esc(mr.getTargetBranch())).append("\n");
            sb.append("发起人：").append(esc(mr.getAuthorName())).append("\n");
            sb.append("发起时间：").append(esc(formatTime(mr.getCreatedAt()))).append("\n");
        }
        sb.append("结果：").append(esc(message)).append("\n");
        sb.append("操作人：").append(esc(operator));
        elements.add(markdown(sb.toString()));

        if (detailUrl != null && !detailUrl.isEmpty()) {
            elements.add(linkButton("去云效查看", "default", detailUrl));
        }

        return toJson(card(success ? "green" : "red", success ? "操作成功" : "操作未完成", elements));
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 按钮回传参数。repo / mr / name 直接塞进按钮，点击时原样带回，
     * 服务端就不用维护"卡片消息 id -> 合并请求"的对应表。
     */
    private Map<String, Object> value(String act, String repoName, String repoId, String localId) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("act", act);
        v.put("repo", repoId);
        v.put("mr", localId);
        v.put("name", repoName == null ? "" : repoName);
        return v;
    }

    /**
     * 卡片正文。
     *
     * 标题不在这里——它已经放到 header 大字位置了，正文再写一遍是重复。
     * 「从哪个分支合到哪个分支」必须一眼能看见：
     * 目标分支是提交人自己选的，可能是 master，看到不是 test 就直接点拒绝。
     *
     * 分支单独占一行、用箭头连起来，别拆成两行——拆开后看不出"谁合到谁"。
     */
    private String summary(String repoName, String repoId, String localId, MrView mr) {
        return "项目：" + esc(blank(repoName, "repoId=" + repoId)) + "\n"
                + "分支：" + esc(blank(mr.getSourceBranch(), "未知"))
                + " → **" + esc(blank(mr.getTargetBranch(), "未知")) + "**\n"
                + "发起人：" + esc(blank(mr.getAuthorName(), "未知")) + "\n"
                + "发起时间：" + esc(formatTime(mr.getCreatedAt())) + "\n"
                + "编号：" + esc(localId) + "　状态：" + esc(mr.getStatus());
    }

    private Map<String, Object> card(String template, String title, List<Object> elements) {
        Map<String, Object> headerTitle = new LinkedHashMap<>();
        headerTitle.put("tag", "plain_text");
        headerTitle.put("content", title);

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("template", template);
        header.put("title", headerTitle);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("elements", elements);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("update_multi", true);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "2.0");
        root.put("config", config);
        root.put("header", header);
        root.put("body", body);
        return root;
    }

    private Map<String, Object> markdown(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", "markdown");
        m.put("content", content);
        return m;
    }

    private Map<String, Object> plain(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", "plain_text");
        m.put("content", content);
        return m;
    }

    /** 两个按钮并排一行 */
    private Map<String, Object> buttons(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> col1 = new LinkedHashMap<>();
        col1.put("tag", "column");
        col1.put("width", "auto");
        col1.put("weight", 1);
        col1.put("vertical_align", "center");
        col1.put("elements", Arrays.asList(left));

        Map<String, Object> col2 = new LinkedHashMap<>();
        col2.put("tag", "column");
        col2.put("width", "auto");
        col2.put("weight", 1);
        col2.put("vertical_align", "center");
        col2.put("elements", Arrays.asList(right));

        Map<String, Object> set = new LinkedHashMap<>();
        set.put("tag", "column_set");
        set.put("flex_mode", "bisect");
        set.put("horizontal_spacing", "8px");
        set.put("columns", Arrays.asList(col1, col2));
        return set;
    }

    /** 回调按钮：点击后把 value 回传给服务端 */
    private Map<String, Object> button(String text, String type, Map<String, Object> callbackValue) {
        Map<String, Object> behavior = new LinkedHashMap<>();
        behavior.put("type", "callback");
        behavior.put("value", callbackValue);
        return rawButton(text, type, behavior);
    }

    /** 跳转按钮：只在卡片上打开链接，不回调服务端 */
    private Map<String, Object> linkButton(String text, String type, String url) {
        Map<String, Object> behavior = new LinkedHashMap<>();
        behavior.put("type", "open_url");
        behavior.put("default_url", url);
        return rawButton(text, type, behavior);
    }

    private Map<String, Object> rawButton(String text, String type, Map<String, Object> behavior) {
        Map<String, Object> btn = new LinkedHashMap<>();
        btn.put("tag", "button");
        btn.put("text", plain(text));
        btn.put("type", type);
        btn.put("width", "fill");
        btn.put("behaviors", Arrays.asList(behavior));
        return btn;
    }

    private String toJson(Map<String, Object> card) {
        try {
            return mapper.writeValueAsString(card);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("卡片 JSON 序列化失败", e);
        }
    }

    /** 卡片文本里带 & < > 会破坏结构，简单转义 */
    private String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String blank(String s, String fallback) {
        return s == null || s.trim().isEmpty() ? fallback : s;
    }

    /**
     * 截断长文本。合并请求说明经常是好几段加空行，
     * 原样贴进卡片会把按钮挤到看不见，所以超长截断、连续空行压成一个。
     */
    private String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\r\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    /** 卡片标题有长度限制，项目名太长就截断，超了飞书会拒收整张卡片 */
    private String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 把云效返回的时间变成群里看得懂的样子。
     *
     * 云效不同接口给的时间格式不一样，三种都见过：
     *   2026-11-18T06:20:33Z           UTC，要转成东八区才是本地时间
     *   2026-11-18T14:20:33+08:00      带偏移量
     *   2026-11-18 14:20:33            纯本地时间
     * 逐个试，全失败就原样截到分钟，不抛异常——时间显示不出来不该让卡片发不出去。
     */
    private String formatTime(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "未知";
        }
        String s = raw.trim();
        try {
            return OffsetDateTime.parse(s)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .format(TIME_FMT);
        } catch (Exception ignore) {
            // 不是带时区的格式，试下一种
        }
        try {
            return Instant.parse(s)
                    .atZone(ZoneId.systemDefault())
                    .format(TIME_FMT);
        } catch (Exception ignore) {
            // 同上
        }
        String t = s.replace('T', ' ');
        return t.length() >= 16 ? t.substring(0, 16) : t;
    }
}
