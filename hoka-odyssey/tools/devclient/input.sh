#!/bin/bash
# Ввод в клиент стенда через xdotool (дисплей $HOKA_DISPLAY):
#   input.sh focus                  — щелчок в центр окна (захват мыши; закрывает «Нажмите, чтобы продолжить»)
#   input.sh key <клавиши…>         — нажать (имена xdotool: w, space, Escape, F1, F3, ctrl+r …)
#   input.sh hold <клавиша> <сек>   — держать клавишу
#   input.sh chat <текст|/команда>  — открыть чат, ввести, Enter
#   input.sh mouse <dx> <dy>        — сдвинуть мышь относительно (поворот камеры при захваченной мыши)
#   input.sh script <файл|->        — без xdotool: отдать скрипт модулю devprobe (см. DevProbeModule) и ждать конца
set -euo pipefail
. "$(dirname "$0")/env.sh"
export DISPLAY="$HOKA_DISPLAY"

cmd="${1:-}"; shift || true
case "$cmd" in
    focus)
        xdotool mousemove $((HOKA_WIDTH / 2)) $((HOKA_HEIGHT / 2)) click 1 ;;
    key)
        xdotool key --delay 80 "$@" ;;
    hold)
        xdotool keydown "$1"; sleep "$2"; xdotool keyup "$1" ;;
    chat)
        xdotool key t; sleep 0.4
        xdotool type --delay 20 -- "$*"; sleep 0.2
        xdotool key Return ;;
    mouse)
        xdotool mousemove_relative -- "$1" "$2" ;;
    script)
        d="$GAME_DIR/hoka_odyssey/data/devprobe"
        mkdir -p "$d"
        rm -f "$d/script.done"
        if [[ "${1:--}" == "-" ]]; then cat > "$d/script.txt.tmp"; else cp "$1" "$d/script.txt.tmp"; fi
        mv "$d/script.txt.tmp" "$d/script.txt"
        for _ in $(seq 1 600); do [[ -f "$d/script.done" ]] && { echo "скрипт выполнен"; exit 0; }; sleep 0.5; done
        echo "devprobe не выполнил скрипт за 300 с (модуль загружен?)" >&2; exit 1 ;;
    *)
        sed -n '2,9p' "$0"; exit 2 ;;
esac
