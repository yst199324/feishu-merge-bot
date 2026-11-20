package com.yiyu.mergebot.yunxiao;

/**
 * 云效接口调用失败。
 *
 * 为什么要带类别：调用方需要区分"等一会儿再点就好"和"再点一百次也没用"。
 * 以前靠 {@code msg.contains("HTTP 401")} 这种字符串匹配判断令牌问题，
 * 云效改一次错误文案就失效，而且表达不了"服务不可用"这一档。
 * 能同时拿到状态码和异常类型的只有云效调用层，别处只能猜，
 * 所以判定放在那里做、跟着异常一起往上带。
 *
 * ⚠️ 这套分类**只覆盖云效调用**。发飞书卡片失败抛的是 {@link IllegalStateException}，
 * 不在其中——它在分类眼里是"其他"，但那件事重试一次往往就好了。
 * 因此内部入口的 400/503 判定必须走白名单（见设计文档 6.2），不能拿类别去猜。
 */
public class YunxiaoException extends RuntimeException {

    /**
     * 失败类别。调用方据此决定"能不能重试"，不要再解析 message。
     */
    public enum Kind {
        /** 云效暂时联系不上：连接被拒、读写超时、HTTP 502/503/504、返回体不是能解析的 JSON */
        SERVICE_UNAVAILABLE,
        /** 令牌问题：HTTP 401 / 403。令牌不会自己变好，重试无用，要人去换 */
        AUTH,
        /** 其余：其它状态码、业务拒绝（冲突、卡点未过）、参数校验失败 */
        OTHER
    }

    private final Kind kind;

    /** HTTP 状态码；0 表示不是 HTTP 层失败（网络不通、参数校验等）。用来把 401 和 403 分开提示 */
    private final int httpStatus;

    /**
     * 不带类别的构造，一律归"其他"。
     * 留给"确定不是网络也不是令牌"的场合（参数校验、接口通了但返回里没字段）。
     */
    public YunxiaoException(String message) {
        this(Kind.OTHER, 0, message, null);
    }

    public YunxiaoException(String message, Throwable cause) {
        this(Kind.OTHER, 0, message, cause);
    }

    public YunxiaoException(Kind kind, String message) {
        this(kind, 0, message, null);
    }

    public YunxiaoException(Kind kind, String message, Throwable cause) {
        this(kind, 0, message, cause);
    }

    public YunxiaoException(Kind kind, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind == null ? Kind.OTHER : kind;
        this.httpStatus = httpStatus;
    }

    public Kind getKind() {
        return kind;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** 只是"暂时联系不上"，等一会儿重试有意义 */
    public boolean isServiceUnavailable() {
        return kind == Kind.SERVICE_UNAVAILABLE;
    }

    /** 令牌问题，重试无用 */
    public boolean isAuth() {
        return kind == Kind.AUTH;
    }
}
