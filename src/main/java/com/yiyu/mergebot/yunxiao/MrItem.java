package com.yiyu.mergebot.yunxiao;

import lombok.Getter;

/**
 * 列表接口的一条合并请求：编号 + 详情视图。
 *
 * 云效列表接口的编号字段名在不同版本里是 iid / localId / id 之一，
 * 取值逻辑统一放在 YunxiaoClient 里，这里只做承载。
 */
@Getter
public class MrItem {

    private final String localId;
    private final MrView mr;

    public MrItem(String localId, MrView mr) {
        this.localId = localId;
        this.mr = mr;
    }

    public String fingerprint() {
        return mr.fingerprint();
    }
}
