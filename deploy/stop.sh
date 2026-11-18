#!/bin/bash
##############################################################
# mergebot 停止脚本
#
# 放在 jar 同级目录，和 merge-bot.jar、start.sh 一起
#
# 用法：
#   chmod +x stop.sh
#   ./stop.sh
#
# 只做停止这一件事。启动用 ./start.sh，重启用 ./restart.sh。
##############################################################

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

APP_NAME="merge-bot"
APP_JAR="$APP_HOME/${APP_NAME}.jar"
PID_FILE="$APP_HOME/${APP_NAME}.pid"

# 等进程退出的最长时间（秒）。Spring Boot 优雅关闭通常 1~3 秒，
# 超过这个时间就强制 kill -9
STOP_TIMEOUT=30

# ---------- 找出所有要停的进程 ----------
# 两个来源都查：pid 文件（正常情况）、进程名（pid 文件被误删的情况）
PIDS=""

if [ -f "$PID_FILE" ]; then
    P="$(cat "$PID_FILE" 2>/dev/null | tr -d ' \n')"
    if [ -n "$P" ] && kill -0 "$P" 2>/dev/null; then
        PIDS="$P"
    fi
fi

if command -v pgrep >/dev/null 2>&1; then
    for P in $(pgrep -f "$APP_JAR" 2>/dev/null); do
        case " $PIDS " in
            *" $P "*) ;;                 # 已经在列表里，跳过
            *) PIDS="$PIDS $P" ;;
        esac
    done
fi

# 去掉首尾空格
PIDS="$(echo "$PIDS" | sed 's/^ *//; s/ *$//')"

if [ -z "$PIDS" ]; then
    echo "[提示] 没有在运行的 mergebot 进程"
    rm -f "$PID_FILE"
    exit 0
fi

# ---------- 先礼后兵 ----------
echo "----------------------------------------"
echo " 停止进程 : $PIDS"
echo "----------------------------------------"

# SIGTERM：让 Spring Boot 走正常的关闭流程，连接池、长连接都会断开
kill $PIDS 2>/dev/null

WAITED=0
while [ $WAITED -lt $STOP_TIMEOUT ]; do
    ALIVE=""
    for P in $PIDS; do
        if kill -0 "$P" 2>/dev/null; then
            ALIVE="$ALIVE $P"
        fi
    done
    if [ -z "$ALIVE" ]; then
        echo "[成功] 已停止，耗时 ${WAITED} 秒"
        rm -f "$PID_FILE"
        exit 0
    fi
    sleep 1
    WAITED=$((WAITED + 1))
done

# ---------- 超时强杀 ----------
echo "[警告] ${STOP_TIMEOUT} 秒还没退出，强制结束：$ALIVE"
kill -9 $ALIVE 2>/dev/null
sleep 1

LEFT=""
for P in $ALIVE; do
    if kill -0 "$P" 2>/dev/null; then
        LEFT="$LEFT $P"
    fi
done

if [ -n "$LEFT" ]; then
    echo "[失败] 以下进程杀不掉，需要人工处理：$LEFT"
    echo "       可能是权限问题（不是启动它的那个用户在执行本脚本）"
    exit 1
fi

echo "[成功] 已强制停止"
rm -f "$PID_FILE"
exit 0
