package com.yiyu.mergebot.yunxiao;

import lombok.Getter;

/**
 * 代码库列表里的一条记录。
 *
 * 用途很窄：云效新版页面的地址栏里已经不带仓库 ID 了
 * （形如 /{orgId}/{组名}/{仓库名}/changes/128），
 * 而合并、查询接口都必须要仓库 ID。所以提供"仓库名 -> 仓库 ID"的查询。
 */
@Getter
public class RepoItem {

    /** 仓库 ID，纯数字，接口里叫 id */
    private final String id;

    /** 仓库名，如 source-admin-new */
    private final String name;

    /** 带路径的全名，如 1998java/source-admin-new；接口给的可能是命名空间 ID 形式 */
    private final String pathWithNamespace;

    /** 是否已归档。归档仓库不能再提合并请求，抄 ID 时容易抄到同名的旧库 */
    private final boolean archived;

    public RepoItem(String id, String name, String pathWithNamespace, boolean archived) {
        this.id = id;
        this.name = name;
        this.pathWithNamespace = pathWithNamespace;
        this.archived = archived;
    }
}
