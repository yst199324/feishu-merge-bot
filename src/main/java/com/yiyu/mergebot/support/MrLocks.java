package com.yiyu.mergebot.support;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按合并请求加锁：同一个 MR 同时只允许一个合并/拒绝操作。
 * 防止连点两次触发并发调用云效接口。
 */
@Component
public class MrLocks {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock of(String repoId, String localId) {
        return locks.computeIfAbsent(repoId + "#" + localId, k -> new ReentrantLock());
    }
}
