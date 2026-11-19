package com.yiyu.mergebot.feishu;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.ws.Client;
import com.yiyu.mergebot.config.FeishuProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
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
 *      因为上一次并没有建立起连接；
 *   3. 连上之后**不做额外的存活探测**——断线重连交给 SDK。
 *      这里是有取舍的：自己再建连接会和 SDK 的重连抢名额，正是上面那个坑的成因。
 *      所以观察到"卡片能收、点按钮没反应"且日志里没有任何异常时，
 *      先把日志级别调到 DEBUG 看有没有重连动作，必要时重启服务，别急着加自动重建。
 */
@Slf4j
@Component
public class FeishuWsRunner implements ApplicationRunner, DisposableBean {

    /**
     * 连上之后每隔多久醒一次、检查线程是否该退出。
     * 这纯粹是为了能响应中断，**不是重连间隔**——重连由 SDK 自己管。
     */
    private static final long KEEPALIVE_CHECK_MS = 60_000L;

    private final FeishuProperties props;
    private final CardActionHandler cardActionHandler;

    /** 长连接线程。关闭时要中断它，否则非守护线程会让 JVM 退不掉，只能被 kill -9 */
    private volatile Thread wsThread;

    /**
     * 长连接是否已经建立。
     *
     * 供 /test/doctor 读取。这个状态没法从别处推断：卡片能不能发出去跟它无关
     * （发消息走的是另一条 HTTP 通道），只有"点按钮没反应"才跟它有关。
     * 所以自检必须能直接看到这一位，否则只能靠人去日志里搜那行中文。
     */
    private volatile boolean connected;

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

                    log.info("正在连接飞书长连接...");
                    client.start();

                    // ★ 走到这里代表"已经连上了"，不是"退出了"。
                    // 千万别 return 回循环顶部——那会每轮新建一条连接，最后撞 50 上限。
                    log.info("飞书长连接已建立，断线由 SDK 自动重连，本线程保持存活不再新建连接");
                    connected = true;
                    backoffMs = 5000L;

                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(KEEPALIVE_CHECK_MS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable e) {
                    // 连不上、或用着用着断了都会走到这里，自检据此报"按钮点不动"
                    connected = false;
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    if (isConnectionLimited(msg)) {
                        // 这条错误堆栈对排查没帮助，只打一句人能看懂的话 + 该去查什么
                        log.error("飞书长连接被拒：同一应用连接数超限（1000040350）。"
                                + "飞书规定每个应用最多 50 个长连接。本服务正常只占 1 条，所以按这个顺序查："
                                + "① 跑的是不是修过的版本（旧版有个 bug 会每 5 秒新建一条连接、旧的不断开，"
                                + "十几分钟就能撞满）；② ps -ef | grep java 看有没有别的实例或残留进程"
                                + "连着同一个 app-id。{} 秒后重试", backoffMs / 1000);
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
        wsThread = thread;
        thread.start();
    }

    /**
     * Spring 关闭时中断长连接线程。
     *
     * 这个线程是非守护的，而且连上之后一直在 sleep 保活。
     * 不主动中断的话，JVM 会认为"还有活干"而拒绝退出，
     * stop.sh 只能干等 30 秒再 kill -9：既不优雅（正在处理的点击直接断在半路），
     * 也会让人觉得"服务停不下来"。
     */
    @Override
    public void destroy() {
        Thread t = wsThread;
        if (t != null && t.isAlive()) {
            log.info("正在关闭飞书长连接线程...");
            t.interrupt();
        }
        connected = false;
    }

    /** 供 /test/doctor 判断"按钮点了会不会有反应" */
    public boolean isConnected() {
        return connected;
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
