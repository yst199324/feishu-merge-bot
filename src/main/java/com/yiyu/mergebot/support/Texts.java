package com.yiyu.mergebot.support;

/**
 * 文本小工具。
 *
 * 只有"真的被两处以上用到、且逻辑完全一样"的才放进来。
 * 目前就一个：取 id 末几位。飞书卡片和日志里都要显示"末 6 位"，
 * 各写一遍容易出现"一处 6 位、一处 8 位"的不一致。
 */
public final class Texts {

    /** 取不到值时的占位，保证卡片和日志里不会出现空括号 */
    public static final String UNKNOWN_TAIL = "??????";

    private Texts() {
    }

    /**
     * 取字符串末尾 n 个字符。
     *
     * @param n 期望长度；串比 n 短就原样返回
     * @return 末 n 位；入参为空返回 {@link #UNKNOWN_TAIL}
     */
    public static String tail(String s, int n) {
        if (s == null || s.trim().isEmpty()) {
            return UNKNOWN_TAIL;
        }
        String t = s.trim();
        return t.length() <= n ? t : t.substring(t.length() - n);
    }
}
