package com.yiyu.mergebot.yunxiao;

/**
 * 仓库名 / 路径的匹配规则。
 *
 * 单独抽出来是因为这段逻辑容易出错，而 Controller 依赖 Spring 不好单独验证：
 * 仓库名在组织内**不唯一**（1998java/source-admin-new 和 test-group/source-admin-new
 * 是两个不同的仓库），只按名字比对会抄错 ID。
 */
public final class RepoMatcher {

    private RepoMatcher() {
    }

    /**
     * 搜索关键字。传了完整路径就只取最后一段（仓库名）去调云效接口。
     *
     * 为什么不把带 / 的完整路径直接塞进 search 参数：
     * 编码后是 %2F，部分网关会拦；而且云效的 search 是模糊匹配，
     * 精确判定放在本地做更可靠。
     */
    public static String keyword(String path, String name) {
        String p = path == null ? "" : path.trim();
        String n = name == null ? "" : name.trim();
        if (p.contains("/")) {
            return p.substring(p.lastIndexOf('/') + 1);
        }
        return p.isEmpty() ? n : p;
    }

    /**
     * 路径比对。
     *
     * 云效的 pathWithNamespace 有两种形态："组名/仓库名" 或 "命名空间ID/仓库名"
     * （文档示例给的就是组织 ID 开头），所以允许"结尾一致"。
     * 万一接口没返回 path 字段，退回比仓库名，避免把结果全过滤掉。
     */
    public static boolean pathEquals(RepoItem repo, String expected) {
        String e = normalize(expected);
        if (e.isEmpty() || repo == null) {
            return false;
        }
        String actual = normalize(repo.getPathWithNamespace());
        if (actual.isEmpty()) {
            String lastSegment = e.substring(e.lastIndexOf('/') + 1);
            return normalize(repo.getName()).equals(lastSegment);
        }
        return actual.equals(e) || actual.endsWith("/" + e);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replace(" ", "").toLowerCase();
    }
}
