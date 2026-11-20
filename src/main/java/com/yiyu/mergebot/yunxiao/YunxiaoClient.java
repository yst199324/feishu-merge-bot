package com.yiyu.mergebot.yunxiao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yiyu.mergebot.config.YunxiaoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 云效 Codeup 合并请求接口封装。
 *
 *   GET  {root}/changeRequests?projectIds={repoId}                          列表（仓库 ID 在查询参数里，不在路径里）
 *   GET  {root}/repositories/{repoId}/changeRequests/{localId}              详情
 *   POST .../repositories/{repoId}/changeRequests/{localId}/merge           合并
 *   POST .../repositories/{repoId}/changeRequests/{localId}/close           关闭
 *
 * {root} = {domain}/oapi/v1/codeup[/organizations/{orgId}]
 * 中心版带 organizations/{orgId}，Region 版不带，由 yunxiao.org-id 是否为空自动切换。
 *
 * 注意列表接口和详情/合并的路径形态不同，别按同一种样子拼，否则 404。
 */
@Slf4j
@Component
public class YunxiaoClient {

    private final YunxiaoProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate rest;

    /**
     * 仓库 ID -> 仓库名 的缓存。
     * 仓库名基本不变，没必要每个 Webhook 事件都去查一次云效。
     */
    private final Map<String, String> repoNameCache = new ConcurrentHashMap<>();

    public YunxiaoClient(YunxiaoProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(20000);
        this.rest = new RestTemplate(factory);
    }

    /**
     * 查仓库名。
     *
     * 为什么需要：云效 Webhook 报文里不一定带 project.name，缺了的话
     * 卡片上只能显示一串数字 repoId，群里根本看不出是哪个项目。
     * 这里调一次"查询代码库"接口补上，结果缓存住，不用每个事件都查。
     *
     * 查不到只记一条 warn，不抛异常——名字缺失只影响卡片可读性，
     * 不该把整条通知流程搞断。
     */
    public String getRepositoryName(String repoId) {
        if (!StringUtils.hasText(repoId)) {
            return "";
        }
        String cached = repoNameCache.get(repoId);
        if (cached != null) {
            return cached;
        }

        // 先看配置里有没有 name -> id 的映射，有就直接反查，省一次接口调用
        Map<String, String> repos = props.getRepos();
        if (repos != null) {
            for (Map.Entry<String, String> e : repos.entrySet()) {
                if (repoId.equals(e.getValue())) {
                    repoNameCache.put(repoId, e.getKey());
                    return e.getKey();
                }
            }
        }

        try {
            JsonNode data = call(HttpMethod.GET, base(repoId), null);
            String name = stripOrgPrefix(firstText(data, "pathWithNamespace", "path_with_namespace", "name"));
            if (StringUtils.hasText(name)) {
                repoNameCache.put(repoId, name);
                return name;
            }
            log.warn("查仓库名返回里没有 name 字段 repoId={}，卡片上会显示成 repoId", repoId);
        } catch (Exception e) {
            log.warn("查仓库名失败 repoId={}：{}。卡片上会显示成 repoId，不影响合并", repoId, e.getMessage());
        }
        return "";
    }

    /**
     * 自检专用：直接查一次仓库详情，**不读名字缓存、也不读配置里的名字映射**。
     *
     * 为什么不复用 {@link #getRepositoryName}：那个方法为了少调接口，
     * 先查 yunxiao.repos 映射、再查内存缓存，查不到还会吞掉异常返回空串。
     * 自检要的是"这个编号在云效那边到底存不存在、这个令牌到底有没有权限"，
     * 任何一层缓存都会让结果变成"看起来通过"，所以这里必须直连。
     *
     * @return 仓库显示名
     * @throws YunxiaoException 仓库不存在（404）、没权限（403）、或网络不通
     */
    public String probeRepository(String repoId) {
        if (!StringUtils.hasText(repoId)) {
            throw new YunxiaoException(YunxiaoException.Kind.OTHER, "仓库编号是空的");
        }
        JsonNode data = call(HttpMethod.GET, base(repoId), null);
        String name = stripOrgPrefix(firstText(data, "pathWithNamespace", "path_with_namespace", "name"));
        if (!StringUtils.hasText(name)) {
            throw new YunxiaoException(YunxiaoException.Kind.OTHER,
                    "接口通了但返回里没有仓库名，编号可能写错了");
        }
        return name;
    }

    /**
     * 按名称模糊查代码库，用来把"仓库名"换成"仓库 ID"。
     *
     * 为什么需要：云效新版页面的地址栏里已经不带仓库 ID 了
     * （形如 /{orgId}/{组名}/{仓库名}/changes/128），
     * 而查询、合并接口都必须要 ID，只能从接口反查。
     *
     * search 参数匹配的是仓库**路径**（含组名），传仓库名就够。
     * 接口：GET {domain}/oapi/v1/codeup[/organizations/{orgId}]/repositories
     */
    public List<RepoItem> listRepositories(String search) {
        String url = orgRoot()
                + "/repositories"
                + "?page=1&perPage=100&orderBy=last_activity_at&sort=desc&archived=false"
                + (StringUtils.hasText(search) ? "&search=" + urlEncode(search) : "");

        JsonNode root = call(HttpMethod.GET, url, null);

        List<RepoItem> out = new ArrayList<>();
        for (JsonNode n : asList(root)) {
            String id = firstText(n, "id", "repositoryId");
            if (!StringUtils.hasText(id)) {
                continue;
            }
            out.add(new RepoItem(
                    id,
                    firstText(n, "name", "path"),
                    firstText(n, "pathWithNamespace", "path_with_namespace", "nameWithNamespace"),
                    n.path("archived").asBoolean(false)));
        }
        return out;
    }

    /** 查询合并请求详情 */
    public MrView getChangeRequest(String repoId, String localId) {
        return MrView.of(call(HttpMethod.GET, mrPath(repoId, localId), null));
    }

    /**
     * 查这个合并请求带了哪些提交（卡片上"本次提交"那一段）。
     *
     * 用的是"比较两个分支"接口，不是合并请求接口——云效没有
     * "查某个合并请求的提交列表"这种接口，只能拿源分支和目标分支比一次。
     *
     * ⚠️ from / to 的顺序是 **git 命令行的语义**（`git log from..to`），
     * 和我们平时说"从 A 合到 B"的顺序**相反**：
     * 要拿"源分支比目标分支多的提交"，必须 `from=目标分支`、`to=源分支`。
     * 传反了结果恒为空，且不报错——这种错最难查，所以写在这里。
     *
     * 失败一律返回空列表，不抛异常：提交列表只是让卡片更好读，
     * 查不到不该让整张卡片发不出去。
     */
    public List<CommitItem> commitsFor(String repoId, String sourceBranch, String targetBranch) {
        List<CommitItem> out = new ArrayList<>();
        if (!StringUtils.hasText(repoId)
                || !StringUtils.hasText(sourceBranch)
                || !StringUtils.hasText(targetBranch)) {
            return out;
        }

        String url = base(repoId) + "/compares"
                + "?from=" + urlEncode(targetBranch)
                + "&to=" + urlEncode(sourceBranch)
                + "&sourceType=branch&targetType=branch&straight=false";
        try {
            JsonNode root = call(HttpMethod.GET, url, null);
            JsonNode arr = root.path("commits");
            if (!arr.isArray()) {
                // 有些版本直接把数组当返回体
                arr = root.isArray() ? root : null;
            }
            if (arr == null) {
                log.warn("比较分支返回里没有 commits 数组 repo={} {}..{}：{}",
                        repoId, targetBranch, sourceBranch, root);
                return out;
            }
            for (JsonNode n : arr) {
                String id = firstText(n, "id", "sha", "commitId");
                if (!StringUtils.hasText(id)) {
                    continue;
                }
                String msg = firstText(n, "message", "title", "shortMessage");
                String author = firstText(n.path("author"), "name", "username");
                out.add(new CommitItem(id, msg, author));
            }
        } catch (Exception e) {
            // 常见原因：令牌没勾「代码管理 → 代码比较 → 只读」，或分支已被删除
            log.warn("查本次提交列表失败 repo={} {}..{}：{}。卡片上不显示提交内容，不影响合并",
                    repoId, targetBranch, sourceBranch, e.getMessage());
        }
        return out;
    }

    /**
     * 拼合并请求在云效网页上的地址，形如
     *   https://codeup.aliyun.com/{组织ID}/{组名}/{仓库名}/change/{编号}
     *
     * 优先用云效详情接口自己返回的 detailUrl（那是官方给的，最准），
     * 只有它缺了才自己拼。
     */
    public String webUrlFor(String repoName, String localId, MrView mr) {
        if (mr != null && StringUtils.hasText(mr.getDetailUrl())) {
            return mr.getDetailUrl();
        }
        if (!StringUtils.hasText(localId)) {
            return "";
        }
        String path = stripOrgPrefix(repoName);
        if (!StringUtils.hasText(path)) {
            return "";
        }
        String web = props.getWebBase();
        if (!StringUtils.hasText(web)) {
            return "";
        }
        if (!web.endsWith("/")) {
            web = web + "/";
        }
        String org = props.getOrgId();
        return web + (StringUtils.hasText(org) ? org + "/" : "") + path + "/change/" + localId;
    }

    /** 合并合并请求。返回合并后的详情。 */
    public MrView merge(String repoId, String localId) {
        Map<String, Object> body = new HashMap<>();
        body.put("mergeType", props.getMergeType());
        body.put("removeSourceBranch", props.isRemoveSourceBranch());
        String url = mrPath(repoId, localId) + "/merge";
        JsonNode data = call(HttpMethod.POST, url, body);
        // 有些版本合并接口返回的是操作结果而不是合并请求详情，此时补一次查询
        if (!data.has("status")) {
            return getChangeRequest(repoId, localId);
        }
        return MrView.of(data);
    }

    /** 关闭（拒绝）合并请求 */
    public MrView close(String repoId, String localId) {
        String url = mrPath(repoId, localId) + "/close";
        JsonNode data = call(HttpMethod.POST, url, new HashMap<String, Object>());
        if (!data.has("status")) {
            return getChangeRequest(repoId, localId);
        }
        return MrView.of(data);
    }

    /**
     * 列出某个仓库里所有"待合并"的合并请求。
     *
     * 轮询方案的核心方法：定时调它，跟上一轮比对，有变化就发卡片。
     *
     * ⚠️ 这个接口的路径和详情/合并**不一样**：
     *   列表：GET {root}/changeRequests?projectIds={repoId}   ← 仓库 ID 是查询参数
     *   详情：GET {root}/repositories/{repoId}/changeRequests/{localId}
     * 列表接口如果照抄详情的样子拼成 .../repositories/{repoId}/changeRequests，
     * 云效直接返回 404 NotFound（看着像权限问题，其实是路径不存在）。
     *
     * 状态过滤走服务端：传 state=opened。实测不传的话会把这个仓库
     * **全部历史**（已合并/已关闭的）一起返回，本地再想过滤也没有依据——
     * 因为列表接口的返回项里根本没有 status 字段，只有 state（opened/merged/closed）。
     * 这个是踩过的坑：按详情接口的 status=TO_BE_MERGED 去过滤列表，
     * 结果永远是 0 条。
     */
    public List<MrItem> listOpenChangeRequests(String repoId) {
        JsonNode root = call(HttpMethod.GET, changeRequestsUrl(repoId, "opened"), null);

        List<MrItem> out = new ArrayList<>();
        for (JsonNode n : asList(root)) {
            // 服务端已经按 state=opened 过滤过，这里只是兜底：
            // 万一某个版本忽略了 state 参数，返回里带的是 merged/closed，
            // 也靠 mergeable() 挡掉，不会给已合并的请求再发卡片。
            // 状态字段缺失时按"待合并"处理——服务端过滤结果就是可信来源。
            MrView view = MrView.of(n, "TO_BE_MERGED");
            if (!view.mergeable()) {
                continue;
            }
            String id = firstText(n, "iid", "localId", "id");
            if (!StringUtils.hasText(id)) {
                continue;
            }
            out.add(new MrItem(id, view));
        }
        return out;
    }

    /**
     * 拼列表接口的 URL。抽出来是为了排错时能把"到底请求了哪个地址"原样打出来——
     * 很多 404 / 空列表的问题，看一眼 URL 就明白了。
     *
     * 不传 state：查全部状态，排错时要的就是这个全貌。
     */
    public String changeRequestsUrl(String repoId) {
        return changeRequestsUrl(repoId, null);
    }

    /**
     * 拼列表接口的 URL。
     *
     * @param state opened / merged / closed，null = 全部状态。
     *              **这个参数是服务端过滤，勾的是状态枚举；
     *              而详情接口用的是 status=TO_BE_MERGED，两者不是一回事。**
     */
    public String changeRequestsUrl(String repoId, String state) {
        return orgRoot() + "/changeRequests"
                + "?projectIds=" + urlEncode(repoId)
                + "&page=1&perPage=100&orderBy=updated_at&sort=desc"
                + (StringUtils.hasText(state) ? "&state=" + urlEncode(state) : "");
    }

    /**
     * 排错专用：原样返回云效的响应字符串，**不做 unwrap、不做字段抽取、不做状态过滤**。
     *
     * 为什么需要：正常流程会把响应拆成 data、按 status 过滤，最后只剩 0 条时，
     * "仓库真没数据"和"字段名对不上"看起来一模一样。排错时必须看原文才能分辨。
     * HTTP 层的错误（401/403/404）照常抛，那个信息本身就有用。
     */
    public String rawGet(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-yunxiao-token", props.getToken());
        HttpEntity<Object> entity = new HttpEntity<>(null, headers);
        try {
            String body = rest.exchange(url, HttpMethod.GET, entity, String.class).getBody();
            log.info("云效原文 GET {} -> {} 字节", url, body == null ? 0 : body.length());
            return body;
        } catch (HttpStatusCodeException e) {
            throw httpFailure(e);
        }
    }

    /**
     * 按源分支查"待合并"的合并请求编号。
     *
     * 用途：云效流水线的内置变量里没有合并请求编号，只给了源分支名，
     * 这里反查一次把编号补齐，卡片才能带上正确的参数。
     *
     * @return 合并请求编号，找不到返回 null
     */
    public String findOpenMergeRequestId(String repoId, String sourceBranch) {
        for (MrItem item : listOpenChangeRequests(repoId)) {
            if (sourceBranch != null && sourceBranch.equals(item.getMr().getSourceBranch())) {
                return item.getLocalId();
            }
        }
        log.debug("未找到源分支 [{}] 对应的待合并请求，仓库 {}", sourceBranch, repoId);
        return null;
    }

    /**
     * 去掉仓库路径前面的组织 ID 前缀。
     *
     * 云效接口返回的 pathWithNamespace 是三段：{orgId}/{组名}/{仓库名}，
     * 其中第一段是一长串十六进制 ID。直接显示在卡片上又长又没意义，
     * 标题还会被截断成半截 ID，所以剥掉，只留"组名/仓库名"。
     */
    private String stripOrgPrefix(String path) {
        if (!StringUtils.hasText(path)) {
            return path;
        }
        String org = props.getOrgId();
        if (StringUtils.hasText(org) && path.startsWith(org + "/")) {
            return path.substring(org.length() + 1);
        }
        return path;
    }

    /** 列表返回体可能是裸数组，也可能包在 list / items / result 里，全都兼容 */
    private List<JsonNode> asList(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        if (root.isArray()) {
            root.forEach(out::add);
            return out;
        }
        for (String field : new String[]{"list", "items", "result", "changeRequests", "repositories"}) {
            JsonNode v = root.get(field);
            if (v != null && v.isArray()) {
                v.forEach(out::add);
                return out;
            }
        }
        return out;
    }

    private String firstText(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = text(n, f);
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return "";
    }

    private String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    /** 查询参数编码。仓库名一般是 ASCII，编码一下更保险 */
    private String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private String base(String repoId) {
        String root = props.getDomain() + "/oapi/v1/codeup";
        String path = "/repositories/" + safeSegment(repoId, "仓库编号");
        return StringUtils.hasText(props.getOrgId())
                ? root + "/organizations/" + props.getOrgId() + path
                : root + path;
    }

    /** 合并请求详情/操作的公共路径，顺便把编号的合法性校验收在一处 */
    private String mrPath(String repoId, String localId) {
        return base(repoId) + "/changeRequests/" + safeSegment(localId, "合并请求编号");
    }

    /**
     * 路径段校验。
     *
     * 仓库编号和合并请求编号会被**直接拼进 URL 路径**，而它们的来源是
     * Webhook 报文和卡片按钮参数。不校验的话，带 `../` 或 `?` 的值
     * 能让服务拿着机器人令牌去请求同一个云效域名下的别的接口。
     *
     * 云效这两个值实际都是数字，这里放宽松一点（字母、数字、下划线、短横），
     * 既能挡住注入，又不至于哪天云效换了编号格式就全线报错。
     */
    private String safeSegment(String value, String what) {
        if (!StringUtils.hasText(value) || !value.matches("[A-Za-z0-9_-]+")) {
            throw new YunxiaoException(YunxiaoException.Kind.OTHER, what + "不合法，已拒绝：" + value);
        }
        return value;
    }

    /**
     * 组织级根路径，用于"不带 repositories 段"的接口（查代码库列表、查合并请求列表）。
     * 中心版带 organizations/{orgId}，Region 版不带。
     */
    private String orgRoot() {
        String root = props.getDomain() + "/oapi/v1/codeup";
        return StringUtils.hasText(props.getOrgId())
                ? root + "/organizations/" + props.getOrgId()
                : root;
    }

    private JsonNode call(HttpMethod method, String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-yunxiao-token", props.getToken());

        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        String raw;
        try {
            raw = rest.exchange(url, method, entity, String.class).getBody();
        } catch (HttpStatusCodeException e) {
            throw httpFailure(e);
        } catch (RestClientException e) {
            // 连接超时、读超时、DNS 失败、连接被重置都走这里。
            // 归"服务不可用"：这类问题过一会儿重试往往就好了，
            // 卡片那边据此保留原卡片、内部入口据此返回 503 让队列重试。
            throw new YunxiaoException(YunxiaoException.Kind.SERVICE_UNAVAILABLE,
                    "云效接口调用失败（网络或超时）：" + e.getMessage(), e);
        }
        // 只记长度，不记响应全文。
        // 响应里有提交人姓名、邮箱、合并请求说明，属于个人信息；
        // 原来这条 debug 会把全文写进日志，等于"关了 log-webhook-raw 也没真关掉"。
        // 真要逐字核对云效返回，用 /test/mrs-raw 那个排错接口。
        log.debug("云效 {} {} -> {} 字节", method, url, raw == null ? 0 : raw.length());
        return unwrap(raw);
    }

    /**
     * 云效返回体有两种形态：直接返回对象，或包一层 {"success":true,"data":{...}}。
     * 这里两种都兼容，避免因为版本差异解析不到字段。
     */
    private JsonNode unwrap(String raw) {
        if (!StringUtils.hasText(raw)) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode node = mapper.readTree(raw);
            if (node.has("success") && !node.path("success").asBoolean(true)) {
                // 接口本身通了，是云效拒绝了这个操作（业务拒绝），不是网络问题
                throw new YunxiaoException(YunxiaoException.Kind.OTHER, "云效返回失败：" + raw);
            }
            JsonNode data = node.get("data");
            if (data != null && !data.isNull()) {
                return data;
            }
            return node;
        } catch (IOException e) {
            // 网关挂了时常见这种：返回体是一段 HTML 错误页而不是 JSON。
            // 归"服务不可用"，因为换一次请求就可能好
            throw new YunxiaoException(YunxiaoException.Kind.SERVICE_UNAVAILABLE,
                    "云效返回内容无法解析：" + raw, e);
        }
    }

    /**
     * 把 HTTP 层失败包成带类别的异常。
     *
     * 放在一处做，是因为状态码只有这里拿得到；散在几个 catch 里各写一套，
     * 迟早出现"一处给了类别、另一处忘了"，而忘了的那处表现成"偶发不可重试"。
     */
    private YunxiaoException httpFailure(HttpStatusCodeException e) {
        int status = e.getRawStatusCode();
        return new YunxiaoException(kindOfStatus(status), status,
                "云效接口失败 HTTP " + status + "，" + e.getResponseBodyAsString(), e);
    }

    /**
     * 按状态码定类别。
     *
     * 401/403 是令牌问题：令牌不会自己变好，重试无用，得人去换。
     * 502/503/504 是网关或后端临时不可用：等一会儿再试就有意义。
     * 其余（400 参数错、404 不存在、409 冲突）是业务拒绝：重试同样无用，
     * 但也不该被当成"网络抖动"，所以归"其他"而不是"服务不可用"。
     */
    private static YunxiaoException.Kind kindOfStatus(int status) {
        if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
            return YunxiaoException.Kind.AUTH;
        }
        if (status == HttpStatus.BAD_GATEWAY.value()
                || status == HttpStatus.SERVICE_UNAVAILABLE.value()
                || status == HttpStatus.GATEWAY_TIMEOUT.value()) {
            return YunxiaoException.Kind.SERVICE_UNAVAILABLE;
        }
        return YunxiaoException.Kind.OTHER;
    }
}
