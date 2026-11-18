package com.yiyu.mergebot.feishu;

import lombok.Getter;

/**
 * 一次操作的结果类型，决定卡片头部的文字和颜色。
 *
 * 为什么不用 boolean success：以前只有"成功/失败"两档，结果
 * **已合并和已拒绝在群里长得一模一样**（都是绿色「操作成功」），
 * 刷过去完全分不清是合了还是拒了。分成三档之后一目了然：
 *
 *   已合并 = 绿色（好事，代码进主线了）
 *   已拒绝 = 灰色（正常决策，已结束，不用再管）
 *   失败   = 红色（要人介入）
 *
 * 「已拒绝」刻意不用黄色/橙色：黄色在飞书里是"警告、需要注意"的含义，
 * 而拒绝是一次正常的主动决策，标成警告会让人以为出了问题。
 */
@Getter
public enum ResultKind {

    /** 合并成功 */
    MERGED("已合并", "green"),

    /** 主动拒绝（关闭） */
    CLOSED("已拒绝", "grey"),

    /** 没做成：状态不满足、有冲突、卡点没过、接口报错 */
    FAILED("操作失败", "red");

    /** 卡片头部中文，如「已合并」 */
    private final String label;

    /** 飞书卡片 header 的模板颜色 */
    private final String color;

    ResultKind(String label, String color) {
        this.label = label;
        this.color = color;
    }
}
