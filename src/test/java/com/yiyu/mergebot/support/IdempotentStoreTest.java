package com.yiyu.mergebot.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    @DisplayName("三态：进门是 FIRST，没标完成之前一直是 IN_FLIGHT，标完才变 DONE")
    void claimGoesFirstInFlightDone() {
        IdempotentStore store = new IdempotentStore();

        assertEquals(IdempotentStore.Claim.FIRST, store.claim("yx:dlv:1", IdempotentStore.DEFAULT_TTL_MILLIS));
        // 处理中的这段时间里，无论来多少次都只能答"还在处理"
        assertEquals(IdempotentStore.Claim.IN_FLIGHT, store.claim("yx:dlv:1", IdempotentStore.DEFAULT_TTL_MILLIS));
        assertEquals(IdempotentStore.Claim.IN_FLIGHT, store.claim("yx:dlv:1", IdempotentStore.DEFAULT_TTL_MILLIS));

        store.markDone("yx:dlv:1");
        assertEquals(IdempotentStore.Claim.DONE, store.claim("yx:dlv:1", IdempotentStore.DEFAULT_TTL_MILLIS));
    }

    @Test
    @DisplayName("撤销之后重新变成 FIRST——失败重投要靠这个，否则一次网络抖动就把消息钉死")
    void forgetReopens() {
        IdempotentStore store = new IdempotentStore();
        assertEquals(IdempotentStore.Claim.FIRST, store.claim("yx:dlv:2", IdempotentStore.DEFAULT_TTL_MILLIS));

        store.forget("yx:dlv:2");

        assertEquals(IdempotentStore.Claim.FIRST, store.claim("yx:dlv:2", IdempotentStore.DEFAULT_TTL_MILLIS));
    }

    @Test
    @DisplayName("标完成时把有效期重新开始算，不让一条处理很久的消息在处理完之前就把门放开")
    void markDoneRenewsTtl() throws Exception {
        IdempotentStore store = new IdempotentStore();
        assertEquals(IdempotentStore.Claim.FIRST, store.claim("k", 120));
        Thread.sleep(80);                      // 从"进门"起已经过了一半有效期
        store.markDone("k");
        Thread.sleep(80);                      // 若从进门算，此刻早已过期

        // 从"完成"重新计时，所以还应该记得这件事已经做完了
        assertEquals(IdempotentStore.Claim.DONE, store.claim("k", 120));
    }

    @Test
    @DisplayName("firstTime 的两态行为不变：卡片点击那一路还在用它，不能因为加了三态就变")
    void firstTimeStillTwoState() {
        IdempotentStore store = new IdempotentStore();
        assertTrue(store.firstTime("card-3:merge_ok", IdempotentStore.CLICK_TTL_MILLIS));
        // 没有 markDone 的调用方，重来一次仍要被挡住（挡的是连点和飞书重推）
        assertFalse(store.firstTime("card-3:merge_ok", IdempotentStore.CLICK_TTL_MILLIS));
    }
}
