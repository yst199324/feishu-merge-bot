#!/bin/bash
##############################################################
# mergebot 启动脚本（nohup 后台常驻）
#
# 放在 jar 同级目录，和下面三个文件一起：
#   /bigdata/work/test/mergebot/merge-bot.jar
#   /bigdata/work/test/mergebot/application.yml
#   /bigdata/work/test/mergebot/start.sh
#
# 用法：
#   chmod +x start.sh
#   ./start.sh
#
# 只做启动这一件事。停止用脚本末尾打印出来的那条 kill 命令。
##############################################################

# ---------- 路径 ----------
# 不管从哪个目录执行，都切到脚本所在的目录，避免相对路径的日志、配置找错位置
APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

APP_NAME="merge-bot"
APP_JAR="$APP_HOME/${APP_NAME}.jar"
LOG_DIR="$APP_HOME/logs"
CONSOLE_LOG="$LOG_DIR/console.log"
PID_FILE="$APP_HOME/${APP_NAME}.pid"

# ---------- JDK ----------
# 用 JDK17。这个项目（Spring Boot 2.7.18 + 飞书 SDK 2.4.19）在 17 上实跑验证过：
# 启动无告警、无反射限制报错，编译产物是 Java 8 字节码，17 直接跑。
# JDK8 也能跑（字节码就是 8 的），需要退回就把下面两行对调。
JAVA_BIN="/root/jdk/jdk-17.0.17/bin/java"
# JAVA_BIN="/root/jdk/jdk1.8.0_291/bin/java"

# ---------- JVM 参数 ----------
# 这个服务只做转发和调接口，内存给 512M 上限足够
JVM_OPTS="-server -Xms256m -Xmx512m"
JVM_OPTS="$JVM_OPTS -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${LOG_DIR}"
JVM_OPTS="$JVM_OPTS -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

# ---------- 前置检查 ----------
if [ ! -x "$JAVA_BIN" ]; then
    echo "[失败] 找不到 JDK：$JAVA_BIN"
    echo "       改用 JDK8 就编辑本脚本，把 JAVA_BIN 换成 /root/jdk/jdk1.8.0_291/bin/java"
    exit 1
fi

if [ ! -f "$APP_JAR" ]; then
    echo "[失败] 找不到 jar：$APP_JAR"
    echo "       把 merge-bot.jar 放到 $APP_HOME 下再执行"
    exit 1
fi

if [ ! -f "$APP_HOME/application.yml" ]; then
    echo "[失败] 找不到配置文件：$APP_HOME/application.yml"
    echo "       这个文件必须和 jar 同级，否则连不上飞书和云效"
    exit 1
fi

# 已经在跑就别重复启动，否则两个进程抢同一个端口，日志也会混在一起
if [ -f "$PID_FILE" ]; then
    OLD_PID="$(cat "$PID_FILE")"
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        echo "[跳过] 已经在运行，PID=$OLD_PID"
        echo "       要重启：kill $OLD_PID  然后等两秒再执行本脚本"
        exit 0
    fi
    echo "[提示] 残留的 pid 文件（进程已不在），继续启动"
    rm -f "$PID_FILE"
fi

# pid 文件被手动删掉时，再用进程名兜底查一遍，避免起两份抢同一个端口
if command -v pgrep >/dev/null 2>&1; then
    RUN_PID="$(pgrep -f "$APP_JAR" | head -1)"
    if [ -n "$RUN_PID" ]; then
        echo "[跳过] 检测到已在运行的进程，PID=$RUN_PID（pid 文件丢了，顺手补上）"
        echo "$RUN_PID" > "$PID_FILE"
        echo "       要重启：kill $RUN_PID  然后等两秒再执行本脚本"
        exit 0
    fi
fi

mkdir -p "$LOG_DIR"

# ---------- 启动 ----------
echo "----------------------------------------"
echo " 启动目录 : $APP_HOME"
echo " 使用 JDK : $JAVA_BIN"
echo " 日志目录 : $LOG_DIR"
echo "----------------------------------------"

# 后台常驻。控制台输出单独留一份，启动阶段报错（logback 还没生效时）只能从这里看
nohup "$JAVA_BIN" $JVM_OPTS \
    -DLOG_PATH="$LOG_DIR" \
    -jar "$APP_JAR" \
    --spring.config.additional-location=file:"$APP_HOME"/ \
    > "$CONSOLE_LOG" 2>&1 &

PID=$!
echo "$PID" > "$PID_FILE"

# 给 Spring Boot 启动留点时间，再看进程还在不在
sleep 8

if kill -0 "$PID" 2>/dev/null; then
    echo "[成功] 已启动，PID=$PID"
    echo ""
    echo " 看日志   : tail -f $LOG_DIR/${APP_NAME}-info.log"
    echo " 看报错   : tail -f $LOG_DIR/${APP_NAME}-error.log"
    echo " 启动输出 : tail -f $CONSOLE_LOG"
    echo " 停止服务 : $APP_HOME/stop.sh   （或 kill $PID）"
    echo " 重启服务 : $APP_HOME/restart.sh"
    echo " 验证存活 : grep -a \"Started MergeBotApplication\" $LOG_DIR/${APP_NAME}-info.log"
else
    echo "[失败] 进程已退出，最后 40 行启动日志："
    echo "----------------------------------------"
    tail -n 40 "$CONSOLE_LOG"
    echo "----------------------------------------"
    rm -f "$PID_FILE"
    exit 1
fi
