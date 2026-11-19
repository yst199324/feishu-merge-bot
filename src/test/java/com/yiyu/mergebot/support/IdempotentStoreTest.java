package com.yiyu.mergebot.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等去重测试。
 *
 * 这层挡的是"飞书回调重推"和"手抖连点"。
 * 它和"先拿锁再登记幂等"的先后顺序是一套的——顺序反了会出现
 * "提示正在处理中、再点却被告知已处理"的假成功，这里至少保证去重本身行为正确。
 */
class IdempotentStoreTest {

    @Test
    @DisplayName("同一个键第一次放行、第二次拒绝")
    void sameKeyOnlyOnce() {
        IdempotentStore store = new IdempotentStore();
        assertTrue(store.firstTime("card-1:merge_ok"));
        assertFalse(store.firstTime("card-1:merge_ok"));
        assertFalse(store.firstTime("card-1:merge_ok"));
    }

    @Test
    @DisplayName("不同键互不影响")
    void differentKeysAreIndependent() {
        IdempotentStore store = new IdempotentStore();
        assertTrue(store.firstTime("card-1:merge_ok"));
        assertTrue(store.firstTime("card-2:merge_ok"));
        assertTrue(store.firstTime("card-1:close"));
    }

    @Test
    @DisplayName("有效期到了就放行，不会因为一条旧记录把用户永久锁死")
    void expiredKeyPassesAgain() throws Exception {
        IdempotentStore store = new IdempotentStore();
        assertTrue(store.firstTime("k", 50));
        assertFalse(store.firstTime("k", 50));
        Thread.sleep(80);
        assertTrue(store.firstTime("k", 50), "超过有效期后应该重新放行");
    }

    @Test
    @DisplayName("两类键各用自己的有效期，短窗口的记录不会影响长窗口")
    void ttlIsPerKey() {
        IdempotentStore store = new IdempotentStore();
        // 推送去重：默认 30 分钟
        assertTrue(store.firstTime("push-1"));
        // 按钮防连点：60 秒
        assertTrue(store.firstTime("click-1", IdempotentStore.CLICK_TTL_MILLIS));

        assertFalse(store.firstTime("push-1"));
        assertFalse(store.firstTime("click-1", IdempotentStore.CLICK_TTL_MILLIS));
    }

    @Test
    @DisplayName("同一个键短时间内的并发调用只有一个能过")
    void concurrentOnlyOneWins() throws Exception {
        IdempotentStore store = new IdempotentStore();
        java.util.concurrent.atomic.AtomicInteger passed = new java.util.concurrent.atomic.AtomicInteger();
        int threads = 8;
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                if (store.firstTime("card-9:merge_ok")) {
                    passed.incrementAndGet();
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        assertTrue(passed.get() == 1, "并发下应只有 1 次放行，实际 " + passed.get());
    }
}
