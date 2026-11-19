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

    /**
     * 判断一个配置值是不是还停在模板占位状态。
     *
     * 判据只有一条：**真实凭据不可能含非 ASCII 字符**（`cli_` / `oc_` / `pt_` 开头、
     * 或是十六进制串），而模板里的占位值一律写成带中文的形式
     * （`cli_你的应用ID`、`在这里粘贴 App Secret`），一眼能看出没填。
     *
     * 这个方法启动校验（{@code StartupValidator}）和自检（{@code SelfCheckService}）
     * 都在用。以前两边各写一套：自检已经按"含中文即占位"判了，启动校验还只认
     * `在这里` / `xxxx`，结果照抄模板能启动、但接口一个都调不通。合并到这里就是为了
     * 不再出现第二次。
     */
    public static boolean isPlaceholder(String value) {
        if (value == null || value.trim().isEmpty()) {
            return true;
        }
        String v = value.trim();
        if (v.contains("xxxx") || v.contains("XXXX")) {
            return true;
        }
        // 含非 ASCII 就是中文占位值
        return !v.chars().allMatch(c -> c < 128);
    }
}
