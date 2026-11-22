#!/bin/bash
# Остановить стенд: stop.sh [client|server|xvfb|all] (по умолчанию all; client и xvfb — текущих HOKA_INSTANCE и
# HOKA_DISPLAY, all — все клиенты и дисплеи стенда). Трогает только процессы этого стенда
# (по pid-файлам и путям $HOKA_DEV_DIR).
. "$(dirname "$0")/env.sh"
what="${1:-all}"

kill_pidfile() {   # kill_pidfile <файл> <сек на мягкую остановку>
    local f="$1" t="$2" pid i
    [[ -f "$f" ]] || return 0
    pid="$(cat "$f")"
    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null
        for ((i = 0; i < t * 2; i++)); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
        kill -9 "$pid" 2>/dev/null
    fi
    rm -f "$f"
}

if [[ $what == client ]]; then
    kill_pidfile "$CLIENT_PID" 5                     # клиент $HOKA_INSTANCE
elif [[ $what == all ]]; then
    for f in "$HOKA_DEV_DIR"/client*.pid; do kill_pidfile "$f" 5; done   # все клиенты
fi
if [[ $what == server || $what == all ]]; then
    if [[ -p "$SRV_DIR/console.fifo" && -f "$SRV_DIR/server.pid" ]] && kill -0 "$(cat "$SRV_DIR/server.pid")" 2>/dev/null; then
        timeout 2 bash -c "echo stop > '$SRV_DIR/console.fifo'" 2>/dev/null
    fi
    kill_pidfile "$SRV_DIR/server.pid" 15
    pkill -f "tail -f $SRV_DIR/console.fifo" 2>/dev/null
fi
if [[ $what == xvfb ]]; then
    kill_pidfile "$XVFB_PID" 3
elif [[ $what == all ]]; then
    for f in "$HOKA_DEV_DIR"/xvfb*.pid; do kill_pidfile "$f" 3; done
fi
true
