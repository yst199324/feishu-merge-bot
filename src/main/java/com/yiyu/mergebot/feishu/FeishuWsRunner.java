package com.yiyu.mergebot.feishu;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.ws.Client;
import com.yiyu.mergebot.config.FeishuProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 飞书长连接。
 *
 * 为什么用长连接：服务主动连出去，飞书借着这条连接把"有人点了按钮"推回来，
 * 所以不需要公网 IP、不需要备案域名、也不用手写验签和 AES 解密。
 *
 * ⚠️⚠️ 这里有一个**必须理解清楚**的 SDK 行为，踩过坑不要改回去：
 *
 *   {@code wsClient.start()} **不是阻塞方法**。
 *   它内部做完 connect() 之后，把 ping 循环丢进线程池就 return 了；
 *   断线重连由 SDK 自带的 autoReconnect 负责（连接配置里默认开启）。
 *
 *   第一版代码误以为 start() 会一直阻塞，于是在外面套了一层 while：
 *   一看到 start() 返回就认为"连接退出了"，接着 new 一个新 Client 再连一次。
 *   结果是**每 5 秒新建一条长连接，旧的没人关**，十几分钟就撞满
 *   飞书"每个应用最多 50 个长连接"的上限，之后永远报
 *   1000040350: the number of connections exceeded the limit。
 *   现象是"服务器上只有一个进程，却提示连接数超限"——因为这一个进程自己在漏。
 *
 * 所以正确写法是：
 *   1. 连上（start() 返回）之后，这个线程就**挂住别动**，什么都不做；
 *   2. 只有 start() **抛异常**（根本没连上）才退避重试，那时建新 Client 是安全的，
 *      因为上一次并没有建立起连接。
 */
@Slf4j
@Component
public class FeishuWsRunner implements ApplicationRunner {

    /**
     * 连上之后每隔多久醒一次、检查线程是否该退出。
     * 这纯粹是为了能响应中断，**不是重连间隔**——重连由 SDK 自己管。
     */
    private static final long KEEPALIVE_CHECK_MS = 60_000L;

    private final FeishuProperties props;
    private final CardActionHandler cardActionHandler;

    /** 持有当前连接，避免被 GC；也方便将来加健康检查 */
    private volatile Client current;

    public FeishuWsRunner(FeishuProperties props, CardActionHandler cardActionHandler) {
        this.props = props;
        this.cardActionHandler = cardActionHandler;
    }

    @Override
    public void run(ApplicationArguments args) {
        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                // 长连接模式下这两个参数保持空字符串
                .onP2CardActionTrigger(cardActionHandler)
                .build();

        // 握手阶段（取连接地址 + 建 WebSocket）是阻塞的，所以放独立线程，
        // 不然会把 Spring 启动流程卡住。
        Thread thread = new Thread(() -> {
            long backoffMs = 5000L;

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Client client = new Client.Builder(props.getAppId(), props.getAppSecret())
                            .eventHandler(dispatcher)
                            .build();
                    current = client;

                    log.info("正在连接飞书长连接...");
                    client.start();

                    // ★ 走到这里代表"已经连上了"，不是"退出了"。
                    // 千万别 return 回循环顶部——那会每轮新建一条连接，最后撞 50 上限。
                    log.info("飞书长连接已建立，断线由 SDK 自动重连，本线程保持存活不再新建连接");
                    backoffMs = 5000L;

                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(KEEPALIVE_CHECK_MS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable e) {
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    if (isConnectionLimited(msg)) {
                        // 这条错误堆栈对排查没帮助，只打一句人能看懂的话 + 该去查什么
                        log.error("飞书长连接被拒：同一应用连接数超限（1000040350）。"
                                + "飞书规定每个应用最多 50 个长连接，本服务只会用 1 条，"
                                + "所以超限说明有别的客户端在连着同一个 app-id"
                                + "（同事机器、旧部署目录、或本服务的历史进程还没退干净）。"
                                + "排查：ps -ef | grep java 确认只剩一个实例。{} 秒后重试", backoffMs / 1000);
                    } else {
                        log.error("飞书长连接异常，{} 秒后重试：{}", backoffMs / 1000, msg, e);
                    }
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    backoffMs = Math.min(backoffMs * 2, 60_000L);
                }
            }
        }, "feishu-ws");
        thread.setDaemon(false);
        thread.start();
    }

    /** 飞书的"连接数超限"错误码。单独识别出来，是为了打一句有用的话而不是甩堆栈 */
    private boolean isConnectionLimited(String msg) {
        if (msg == null) {
            return false;
        }
        return msg.contains("1000040350")
                || msg.toLowerCase().contains("connections exceeded");
    }
}
