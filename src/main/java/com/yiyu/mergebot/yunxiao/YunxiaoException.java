package com.yiyu.mergebot.yunxiao;

/**
 * 云效接口调用失败。
 */
public class YunxiaoException extends RuntimeException {

    public YunxiaoException(String message) {
        super(message);
    }

    public YunxiaoException(String message, Throwable cause) {
        super(message, cause);
    }
}
