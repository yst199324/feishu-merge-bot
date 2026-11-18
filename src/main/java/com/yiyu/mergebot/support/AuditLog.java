package com.yiyu.mergebot.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 审计日志输出。
 *
 * 用独立的 logger 名（AUDIT），在 logback 里单独接一个文件 `logs/merge-bot-audit.log`，
 * 原因有两个：
 *  1. **可回溯**：审计记录不该混在调试日志里，被按级别或体积滚掉；
 *     这个文件单独保留一年，出问题往前翻能查到"谁在哪天把哪条请求合了"。
 *  2. **可统计**：一行一条、字段固定，直接 grep 或导表格就能做月度统计。
 *
 * 注意别把这里当成业务日志用——只记"谁对什么做了什么、结果如何"。
 */
@Slf4j(topic = "AUDIT")
@Component
public class AuditLog {

    public void write(AuditEntry entry) {
        if (entry == null) {
            return;
        }
        log.info(entry.line());
    }
}
