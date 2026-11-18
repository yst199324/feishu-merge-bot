package com.yiyu.mergebot.feishu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiyu.mergebot.yunxiao.CommitItem;
import com.yiyu.mergebot.yunxiao.MrView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
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
        // 最近一条提交。Webhook 入口传进来的就是报文里的 last_commit（只有一条），**不额外调云效接口**；
        // 把这一行塞进按钮值，结果卡替换原卡片时才能原样保留。
        List<CommitItem> list = commits == null ? new ArrayList<>() : commits;
        String commitBlock = list.isEmpty() ? ""
                : commitsBlock(list, mr == null ? 0 : mr.getAhead(), latestOnly);
        return approvalCardBody(repoName, repoId, localId, mr,
                commitBlock, latestCommitLine(list), webUrl);
    }

    /**
     * 用按钮带回来的「最近提交」文本恢复待审批卡。
     *
     * 点「取消」、或重复点击发现上次没生效时，服务端手里只有这一行文本，没有完整提交列表。
     * **不能为此再调一次云效接口**：一来多余，二来接口一失败，
     * 恢复出来的卡片就会凭空少一段提交内容——用户看到的就是"点了一下卡片变简单了"。
     */
    public String approvalCardFromCallback(String repoName, String repoId, String localId,
                                           MrView mr, String commitLine, String webUrl) {
        String line = commitLine == null ? "" : commitLine;
        String block = line.isEmpty() ? ""
                : "<font color='grey'>最近提交</font>\n" + esc(line);
        return approvalCardBody(repoName, repoId, localId, mr, block, line, webUrl);
    }

    /** 两种来源共用同一套卡片骨架，避免各写一份导致"恢复后的卡片和原卡片不一样" */
    private String approvalCardBody(String repoName, String repoId, String localId, MrView mr,
                                    String commitBlock, String commitLine, String webUrl) {
        List<Object> elements = new ArrayList<>();
        elements.add(markdown(summary(repoName, repoId, localId, mr)));

        // 说明：审核的人最先想知道"这个改动是干什么的"，没有的话明确写"未填写"，
        // 而不是悄悄省略——省略会让人以为是卡片渲染出了问题
        String desc = trim(mr == null ? null : mr.getDescription(), 200);
        if (!desc.isEmpty()) {
            elements.add(markdown("<font color='grey'>说明</font>\n" + esc(desc)));
        }
        if (!commitBlock.isEmpty()) {
            elements.add(markdown(commitBlock));
        }

        // 链接优先用 Webhook 报文里云效自己给的（调用方已经解析好传进来），
        // 拿不到才回落到详情接口/本地拼接。提前算好，因为还要塞进按钮值给结果卡用
        String url = blank(webUrl, mr == null ? "" : blank(mr.getDetailUrl(), ""));

        // 请求可能已经被别人在云效关掉或合掉了。飞书卡片是静态的，不会自动变，
        // 但只要有人点一下按钮，服务端重查状态就会把它换成终态卡（见 finishedResult）。
        // 这里处理的是"发卡时状态就已经是终态"的情况（轮询入口、重推等）。
        String status = mr.getStatus();
        boolean closed = "CLOSED".equals(status);
        boolean merged = "MERGED".equals(status);
        boolean finished = closed || merged;

        boolean canMerge = !finished && mr.mergeable() && !mr.hasConflict() && mr.isAllRequirementsPass();
        // 提示必须说真实原因。以前写死"存在冲突或卡点未通过"，
        // 状态明明是已关闭还让人"点拒绝或让提交人处理"，是在把人往错的方向引。
        String tips = finished
                ? "合并请求已" + (closed ? "关闭" : "合并") + "，不能再操作"
                : "存在冲突或卡点未通过，不能合并。点拒绝或让提交人处理";

        Map<String, Object> mergeValue = value("merge", repoName, repoId, localId, commitLine, url);
        Map<String, Object> closeValue = value("close", repoName, repoId, localId, commitLine, url);

        Map<String, Object> mergeBtn = button("合并", canMerge ? "primary" : "default", mergeValue);
        Map<String, Object> closeBtn = button("拒绝", finished ? "default" : "danger", closeValue);
        if (!canMerge) {
            mergeBtn.put("disabled", true);
            mergeBtn.put("disabled_tips", plain(tips));
        }
        if (finished) {
            // 请求都结束了，拒绝按钮也别亮着，否则看着像"还能再操作一次"
            closeBtn.put("disabled", true);
            closeBtn.put("disabled_tips", plain(tips));
        }

        elements.add(buttons(mergeBtn, closeBtn));

        // 云效按钮 + 底部一行灰字链接。
        //
        // 为什么不把 URL 单列一行放在正文：太占地方，而且大部分时候没人复制。
        // 但也不能完全去掉——手机上点按钮只能在本机浏览器打开，
        // 想"发到电脑上接着看"就得能复制，而按钮上的文字复制不了，只有正文文字可以长按复制。
        // 折中放在最底部灰字里：不干扰阅读，需要时还能长按。
        if (!url.isEmpty()) {
            elements.add(linkButton("在云效打开", "default", url));
        }

        elements.add(markdown("<font color='grey'>机器人自动检查：冲突 "
                + mr.conflictText() + "；卡点 " + mr.requirementsText()
                + behindHint(mr) + "</font>"));

        if (!url.isEmpty()) {
            elements.add(markdown("<font color='grey'>云效链接（长按可复制）：" + esc(url) + "</font>"));
        }

        // 终态用对应的颜色和标题：已关闭灰、已合并绿、待审批蓝。
        // 群里颜色是"要不要处理"的信号，蓝色的待审批卡配两个灰按钮会自相矛盾。
        String mrTitle = mr == null ? "" : blank(mr.getTitle(), "");
        String label = closed ? "已关闭" : (merged ? "已合并" : "待审批");
        String template = closed ? "grey" : (merged ? "green" : "blue");
        String headTitle = headOf(label, mrTitle, repoName);
        if (label.equals(headTitle)) {
            headTitle = "合并请求" + label;
        }

        return toJson(card(template, headTitle, elements));
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
    public String confirmCard(String repoName, String repoId, String localId, MrView mr,
                              String commitLine, String webUrl) {
        List<Object> elements = new ArrayList<>();
        elements.add(markdown("**确认要把这个合并请求合进 "
                + esc(blank(mr.getTargetBranch(), "目标分支")) + " 吗？**\n"
                + "项目：" + esc(blank(repoName, "repoId=" + repoId)) + "\n"
                + branchLine(mr) + "\n"
                + "发起人：" + esc(blank(mr.getAuthorName(), "未知")) + "\n"
                + "发起时间：" + esc(formatTime(mr.getCreatedAt()))));

        // 把最近提交和 Webhook 链接继续往下带：确认卡的按钮被点之后，结果卡还要用
        Map<String, Object> okValue = value("merge_ok", repoName, repoId, localId, commitLine, webUrl);
        Map<String, Object> cancelValue = value("cancel", repoName, repoId, localId, commitLine, webUrl);

        elements.add(buttons(
                button("确认合并", "primary_filled", okValue),
                button("取消", "default", cancelValue)));

        // header 也带上标题：合并是不可逆操作，确认时看错请求是最贵的一类错。
        // 大标题里写清楚"确认的是哪一件事"，比正文里描述一百字都顶用。
        // 和其它卡片共用 headOf，截断规则保持一致（按显示宽度，中文算两格）
        return toJson(card("orange", headOf("二次确认", mr.getTitle(), ""), elements));
    }

    /** 结果卡片：操作完成后回写，把按钮换成结论，避免重复点击 */
    /**
     * 结果卡：把一次操作的结果回写到卡片上。
     *
     * 四个设计点，都是踩过坑之后定下来的：
     *  1. **header 是「结果 · 标题」**。群消息列表只显示这一行，
     *     以前只写"操作成功"，刷过去之后完全不知道是哪条请求被合了；
     *  2. **已合并绿、已拒绝灰、失败红**。拒绝是正常决策不是异常，不能用黄/红；
     *  3. **正文第一行是结果**（加粗+着色），紧跟着是完整标题——
     *     header 里的标题会被截断到 24 字，正文这条给全的；
     *  4. **URL 只出现在底部灰字里**，不占正文版面，需要时还能长按复制。
     *
     * @param kind   这次是什么结果，决定头部文字和颜色
     * @param message 结果说明，如「已合并进 test」
     */
    public String resultCard(String repoName, String taskTitle, ResultKind kind, String operator,
                             String message, MrView mr, String detailUrl, String commitLine) {
        List<Object> elements = new ArrayList<>();

        StringBuilder sb = new StringBuilder();
        // 结果行放最前面。加「结果：」标签是有意的：不带标签时，
        // 一行彩色加粗的字容易被误读成标题，而它其实是操作结论。
        // 值保留加粗 + 按状态着色，标签本身用普通字重。
        sb.append("结果：**<font color='").append(kind.getColor()).append("'>")
                .append(esc(message)).append("</font>**\n");
        // 完整标题，**不截断**——header 那行放不下是必然的，正文就是用来看全的
        if (!blank(taskTitle, "").isEmpty()) {
            sb.append("标题：**").append(esc(taskTitle)).append("**\n");
        }
        // 最近提交紧跟标题下一行。用的是 Webhook 报文里的 last_commit，
        // 由按钮值透传过来，**不额外调云效接口**（合并后源分支可能已删，也查不到）
        if (!blank(commitLine, "").isEmpty()) {
            sb.append("\n<font color='grey'>最近提交</font>\n")
                    .append(esc(commitLine)).append("\n");
        }
        sb.append("\n");

        sb.append("项目：").append(esc(blank(repoName, "未知"))).append("\n");
        if (mr != null) {
            sb.append(branchLine(mr)).append("\n");
            // 发起时间和发起人并一行，省一行版面
            sb.append("发起人：").append(esc(blank(mr.getAuthorName(), "未知")))
                    .append(" · ").append(esc(shortTime(mr.getCreatedAt()))).append("\n");
        }
        // 谁在什么时候处理的。以前只有发起时间，回看时对不上"是哪次点的"
        sb.append("处理：").append(esc(blank(operator, "未知")))
                .append(" · ").append(esc(nowText()));
        elements.add(markdown(sb.toString()));

        String url = blank(detailUrl, mr == null ? "" : blank(mr.getDetailUrl(), ""));
        if (!url.isEmpty()) {
            elements.add(linkButton("去云效查看", "default", url));
            elements.add(markdown("<font color='grey'>云效链接（长按可复制）：" + esc(url) + "</font>"));
        }

        return toJson(card(kind.getColor(), headOf(kind.getLabel(), taskTitle, repoName), elements));
    }

    /**
     * 卡片头部那一行大概能放多少个"半角字符"（一个中文算两个）。
     *
     * 飞书 header 是单行 plain_text，**不支持换行**（换行符会被忽略），
     * 群消息列表也只显示这一行。所以标题再长也只能截，
     * 我们截得合理一点，别让飞书再截第二次。
     */
    private static final int HEAD_MAX_WIDTH = 38;

    /**
     * header 文案：「状态 · 标题」。
     *
     * 标题为空回落到项目名；两个都空就只显示状态。
     * 为什么一定要带标题：群消息列表只显示这一行，只写状态等于没说是哪条请求。
     *
     * 截断按**显示宽度**算，不是字符数：中文一个字占两位宽。
     * 以前按"24 个字符"截，中文标题实际占 48 宽（超出后被飞书再截一次，看着像被切了两次），
     * 而英文项目名只占 24 宽（白白浪费）。现在统一按宽度 38 算：
     * 中文标题能放 19 个字，`1998java/source-admin-new` 这种项目名能完整显示。
     */
    private String headOf(String status, String title, String repoName) {
        String t = blank(title, "");
        if (t.isEmpty()) {
            t = blank(repoName, "");
        }
        return t.isEmpty() ? status : status + " · " + shortenByWidth(t, HEAD_MAX_WIDTH);
    }

    /**
     * 按显示宽度截断：中文/全角算 2，其余算 1。
     *
     * 截断后加省略号，让读者知道"后面还有"。
     * 完整内容一定还会在正文里出现一次，不用担心里面的字丢了。
     */
    private String shortenByWidth(String s, int maxWidth) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int width = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int cw = isWide(c) ? 2 : 1;
            if (width + cw > maxWidth) {
                return sb.append("…").toString();
            }
            sb.append(c);
            width += cw;
        }
        return s;
    }

    /** 粗略判断是不是"占两格"的字符：CJK 汉字、假名、全角标点都在这个区间 */
    private boolean isWide(char c) {
        return c >= 0x1100 && (c <= 0x115F        // 韩文字母
                || c == 0x2329 || c == 0x232A
                || (c >= 0x2E80 && c <= 0xA4CF)   // CJK 部首、汉字、假名、全角符号
                || (c >= 0xAC00 && c <= 0xD7A3)   // 韩文音节
                || (c >= 0xF900 && c <= 0xFAFF)   // CJK 兼容汉字
                || (c >= 0xFE30 && c <= 0xFE6F)   // CJK 兼容形式
                || (c >= 0xFF00 && c <= 0xFF60)   // 全角 ASCII
                || (c >= 0xFFE0 && c <= 0xFFE6));
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 按钮回传参数。repo / mr / name 直接塞进按钮，点击时原样带回，
     * 服务端就不用维护"卡片消息 id -> 合并请求"的对应表。
     */
    /**
     * 按钮的回调值。
     *
     * `commit` 是「最近提交」那一行，`url` 是 Webhook 报文里云效自己给的合并请求链接。
     * 为什么要把展示用的东西塞进按钮值：结果卡是**整张替换**原卡片的，服务端没有旧卡片内容，
     * 而合并之后源分支可能已删、详情接口也可能不再返回链接字段。
     * 让按钮带回来，结果卡才能原样保留，且不额外调任何接口、不靠本地拼地址猜。
     */
    private Map<String, Object> value(String act, String repoName, String repoId, String localId,
                                      String commitLine, String webUrl) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("act", act);
        v.put("repo", repoId);
        v.put("mr", localId);
        v.put("name", repoName == null ? "" : repoName);
        v.put("commit", commitLine == null ? "" : commitLine);
        v.put("url", webUrl == null ? "" : webUrl);
        return v;
    }

    /** 只取最后一条提交，一行。结果卡要显示的就是这一行，不带整段列表 */
    private String latestCommitLine(List<CommitItem> commits) {
        if (commits == null || commits.isEmpty()) {
            return "";
        }
        CommitItem c = commits.get(0);
        String line = (blank(c.getShortId(), "") + "　" + blank(c.getMessage(), "")
                + "　" + blank(c.getAuthor(), "")).trim();
        // 三个字段都空时会拼出两个全角空格，绝不能当成"有提交"塞进卡片，
        // 否则会渲染出一行「最近提交」下面只有空格的怪东西
        return line.replace("\u3000", "").trim().isEmpty() ? "" : line;
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
    /**
     * 分支行，三类卡片共用：源分支蓝色、目标分支绿色，**都加粗**。
     *
     * 颜色表示"方向"：蓝=从哪来，绿=合到哪去，一眼看出箭头两头不是同一个东西。
     * 必须连在一行、用箭头接起来——拆成两行就看不出"谁合到谁"。
     */
    private String branchLine(MrView mr) {
        return "分支：<font color='blue'>**" + esc(blank(mr.getSourceBranch(), "未知"))
                + "**</font> → <font color='green'>**"
                + esc(blank(mr.getTargetBranch(), "未知")) + "**</font>";
    }

    private String summary(String repoName, String repoId, String localId, MrView mr) {
        return "项目：" + esc(blank(repoName, "repoId=" + repoId)) + "\n"
                + branchLine(mr) + "\n"
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

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 结果卡里用短格式：一群人看群消息，年份没什么用，省出来的位置给别的信息 */
    private static final DateTimeFormatter SHORT_TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 卡片生成时刻，即"处理时间"。卡片是同步渲染的，和真正处理完的时间差在毫秒级 */
    private String nowText() {
        return LocalDateTime.now().format(SHORT_TIME_FMT);
    }

    /** 把云效时间转成短格式（MM-dd HH:mm），解析不出来时退回原样 */
    private String shortTime(String raw) {
        String full = formatTime(raw);
        if ("未知".equals(full)) {
            return full;
        }
        try {
            return LocalDateTime.parse(full, TIME_FMT).format(SHORT_TIME_FMT);
        } catch (Exception ignore) {
            return full;
        }
    }

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
