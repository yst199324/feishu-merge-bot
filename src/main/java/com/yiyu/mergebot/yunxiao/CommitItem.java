package com.yiyu.mergebot.yunxiao;

import lombok.Getter;

/**
 * 一条提交记录，卡片上"本次提交内容"那一段用的。
 *
 * 只留群里看得懂的三样：短提交号、提交说明、谁提交的。
 * 完整 SHA 太长，卡片上占一行，截前 8 位（GitHub 也是这个习惯）。
 */
@Getter
public class CommitItem {

    /** 短提交号，前 8 位 */
    private final String shortId;
    /** 提交说明第一行，超长截断 */
    private final String message;
    private final String author;

    public CommitItem(String fullId, String message, String author) {
        this.shortId = shortenId(fullId);
        this.message = oneLine(message, 60);
        this.author = author == null ? "" : author;
    }

    private static String shortenId(String id) {
        if (id == null) {
            return "";
        }
        String s = id.trim();
        return s.length() <= 8 ? s : s.substring(0, 8);
    }

    /**
     * 提交说明经常是多行（标题 + 正文 + 一堆空行），卡片上只要第一行。
     * 太长的话截断——不截的话一条提交就能把整张卡片撑满。
     */
    private static String oneLine(String message, int max) {
        if (message == null) {
            return "";
        }
        String s = message.trim();
        int nl = s.indexOf('\n');
        if (nl >= 0) {
            s = s.substring(0, nl).trim();
        }
        if (s.isEmpty()) {
            return "（无说明）";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
