package com.yiyu.mergebot.web;

/**
 * 一次 Webhook 投递的处理结论。
 *
 * 为什么要单独抽一个类型：同一份业务逻辑要给两个入口用，而两个入口要的答案不一样——
 * 云效入口只需要 200 / 400 / 500 三种人话，监听服务的内部入口需要
 * "删文件 / 进死信 / 进重试"三态。把结论抽成值对象，两边各自映射，
 * 业务代码就不用关心返回码，将来再加第三个入口也不用动业务。
 *
 * {@code note} 是给人看的一句话，会原样写进 {@code /test/last-webhook}。
 * 排查"云效说推送成功、群里却没有卡片"时全靠它，所以措辞沿用改造前的写法，
 * 一个字都别丢（自检那边也在按"成功 / 忽略"开头判断结论）。
 */
public final class DispatchResult {

    /**
     * 结论种类。这是"该不该重试"的唯一依据。
     */
    public enum Outcome {
        /** 处理完了：发了卡片，或确认这条不需要处理（状态已经不是待合并） */
        DONE,
        /** 明确不用处理：重复投递、非合并请求事件、不该发卡的动作 */
        IGNORED,
        /**
         * 同一条投递**上一次还在处理中**，这次没有执行。
         *
         * ⚠️ 这一态绝对不能一律回 200：它和 DONE 的意思相反——DONE 是"别再来了"，
         * IN_FLIGHT 是"过一会儿再来"。监听服务把这两个都当成功就会删文件，
         * 万一第一次处理最后失败了，这条消息就静默消失——正是整套方案要消灭的那个洞。
         *
         * 两个入口口径不同：
         *  - 内部入口 → **503**，让队列按阶梯重试，绝不能删文件；
         *  - 云效入口 → **200**，因为云效不会自动重投，回失败只会在它后台多一条假失败记录。
         */
        IN_FLIGHT,
        /** 重试再多次也没用，要人工介入：报文解析不了、取不到编号、缺投递编号、云效凭据失效 */
        PERMANENT_FAIL,
        /** 现在处理不了，过一会儿可能就好了：云效连不上、发飞书卡片失败、其他未预期异常 */
        RETRYABLE
    }

    private final Outcome outcome;
    private final String note;

    private DispatchResult(Outcome outcome, String note) {
        this.outcome = outcome;
        this.note = note == null ? "" : note;
    }

    public static DispatchResult done(String note) {
        return new DispatchResult(Outcome.DONE, note);
    }

    public static DispatchResult ignored(String note) {
        return new DispatchResult(Outcome.IGNORED, note);
    }

    public static DispatchResult inFlight(String note) {
        return new DispatchResult(Outcome.IN_FLIGHT, note);
    }

    public static DispatchResult permanentFail(String note) {
        return new DispatchResult(Outcome.PERMANENT_FAIL, note);
    }

    public static DispatchResult retryable(String note) {
        return new DispatchResult(Outcome.RETRYABLE, note);
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getNote() {
        return note;
    }

    /** 这条投递要不要继续重试 */
    public boolean isRetryable() {
        return outcome == Outcome.RETRYABLE;
    }
}
