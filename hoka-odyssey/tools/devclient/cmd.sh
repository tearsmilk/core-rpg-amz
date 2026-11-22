#!/bin/bash
# Команда в консоль сервера стенда: cmd.sh hoclient hello Привет
set -euo pipefail
. "$(dirname "$0")/env.sh"
[[ -p "$SRV_DIR/console.fifo" ]] || { echo "сервер стенда не запущен (нет $SRV_DIR/console.fifo)" >&2; exit 1; }
echo "$*" > "$SRV_DIR/console.fifo"
