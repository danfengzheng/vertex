#!/bin/bash

# --- 配置信息 ---
APP_NAME="admin-web-1.0.0.jar"
# 指定运行环境为 prod
PROFILES_ACTIVE="prod"
# 内存调整为 3G
JVM_OPTS="-server -Xms2g -Xmx3g -XX:+UseG1GC -Dfile.encoding=utf-8 -Dspring.profiles.active=$PROFILES_ACTIVE -Dvertex.trading.telegram.enabled=false -Dvertex.chain.bnb.enabled=false -Dvertex.chain.solana.enabled=false -Dvertex.chain.binance-alpha.enabled=false -Dvertex.chain.bsc-trending.enabled=false  -Dvertex.strategy.telegram.enabled=true"
LOG_FILE="admin.log"

# --- 核心逻辑 ---
usage() {
    echo "Usage: sh admin.sh [start|stop|restart|status]"
    exit 1
}

is_exist() {
    pid=`ps -ef | grep $APP_NAME | grep -v grep | awk '{print $2}'`
    if [ -z "${pid}" ]; then
        return 1
    else
        return 0
    fi
}

start() {
    is_exist
    if [ $? -eq 0 ]; then
        echo ">>> ${APP_NAME} 已经在运行中 (PID=${pid}) <<<"
    else
        echo ">>> 正在启动 ${APP_NAME} (内存限制: 3G) ... <<<"
        nohup java $JVM_OPTS -jar $APP_NAME > $LOG_FILE 2>&1 &
        echo ">>> 启动成功，查看日志: tail -f $LOG_FILE <<<"
    fi
}

stop() {
    is_exist
    if [ $? -eq "0" ]; then
        echo ">>> 正在停止 ${APP_NAME} (PID=${pid}) ... <<<"
        kill -15 $pid
        sleep 2
        is_exist
        if [ $? -eq "0" ]; then
            echo ">>> 进程未响应，强制关闭 (kill -9) <<<"
            kill -9 $pid
        fi
        echo ">>> 已停止 <<<"
    else
        echo ">>> ${APP_NAME} 未运行 <<<"
    fi
}

status() {
    is_exist
    if [ $? -eq "0" ]; then
        echo ">>> ${APP_NAME} 正在运行 (PID=${pid}) <<<"
    else
        echo ">>> ${APP_NAME} 未运行 <<<"
    fi
}

restart() {
    stop
    start
}

case "$1" in
    "start") start ;;
    "stop") stop ;;
    "status") status ;;
    "restart") restart ;;
    *) usage ;;
esac
