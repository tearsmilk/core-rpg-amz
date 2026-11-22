#!/bin/bash
# Локальный сервер стенда (Minestom, ядро из build-devcore.sh) на localhost:$HOKA_PORT, в фоне.
#   - модули: clientmods + respack ядра и наши ($HOKA_OUR_MODULES), всё из $HOKA_DEV_DIR/server-dist;
#   - клиентские модули: $HOKA_DEV_DIR/clientmods → module/clientmods/jars, подпись dev-ключом;
#   - $HOKA_PLAYER — оператор (ops.json); консоль сервера — cmd.sh <команда>.
# Мир и настройки модулей лежат в $HOKA_DEV_DIR/server и переживают перезапуск (чистый старт: удалить эту папку).
# Флаг --sync-only: только обновить jar клиентских модулей и сделать /hoclient reload на запущенном сервере.
set -euo pipefail
. "$(dirname "$0")/env.sh"

DIST="$HOKA_DEV_DIR/server-dist"
[[ -f "$DIST/server.jar" ]] || { echo "нет $DIST/server.jar — сначала build-devcore.sh" >&2; exit 1; }

sync_clientmods() {
    mkdir -p "$SRV_DIR/module/clientmods/jars"
    rm -f "$SRV_DIR/module/clientmods/jars/"*.jar
    cp "$HOKA_DEV_DIR/clientmods/"*.jar "$SRV_DIR/module/clientmods/jars/"
    install -m 600 "$HOKA_DEV_DIR/devkey/signing.key" "$SRV_DIR/module/clientmods/signing.key"
}

if [[ "${1:-}" == "--sync-only" ]]; then
    sync_clientmods
    "$DEVCLIENT_DIR/cmd.sh" hoclient reload
    echo "клиентские модули обновлены: $(ls "$SRV_DIR/module/clientmods/jars" | tr '\n' ' ')"
    exit 0
fi

"$DEVCLIENT_DIR/stop.sh" server >/dev/null 2>&1 || true

mkdir -p "$SRV_DIR/module"
cp "$DIST/server.jar" "$SRV_DIR/"
rm -f "$SRV_DIR/module/"*.jar
cp "$DIST/module/"*.jar "$SRV_DIR/module/"
sync_clientmods

# respack: локальный HTTP-порт и адрес (по умолчанию в конфиге — адрес боевого сервера)
RP="$SRV_DIR/module/respack/config.yml"
if [[ ! -f "$RP" ]]; then
    mkdir -p "$(dirname "$RP")"
    unzip -p "$SRV_DIR/module/respack.jar" config.yml > "$RP"
fi
sed -i -E "s#^(  port: ).*#\1$HOKA_RESPACK_PORT#; s#^(  public-url: ).*#\1\"http://127.0.0.1:$HOKA_RESPACK_PORT\"#" "$RP"
grep -q "127.0.0.1:$HOKA_RESPACK_PORT" "$RP" || { echo "не удалось выставить адрес respack в $RP" >&2; exit 1; }
# respack ждёт HELLO мода pack.join-delay-ticks (по умолчанию 30 тиков = 1,5 с) и только потом шлёт пак игрокам без мода.
# Клиент на llvmpipe здоровается позже — без запаса ему приходит окно «скачать ресурспак?». На стенде ждём 10 с.
grep -q 'join-delay-ticks' "$RP" || sed -i -E 's#^pack:\s*$#pack:\n  join-delay-ticks: 200#' "$RP"

[[ -f "$SRV_DIR/ops.json" ]] || printf '[\n  {"uuid": "", "name": "%s"}\n]\n' "$HOKA_PLAYER" > "$SRV_DIR/ops.json"

cd "$SRV_DIR"
rm -f console.fifo
mkfifo console.fifo
: > "$SERVER_LOG"
# tail держит fifo открытым: cmd.sh пишет туда команды консоли
(tail -f "$SRV_DIR/console.fifo" | SERVER_PORT="$HOKA_PORT" exec "$JAVA25_HOME/bin/java" \
    -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Xmx1G -Dhoka.devclient=1 \
    -cp "$SRV_DIR/server.jar" ink.hoka.odyssey.server.OdysseyServer >> "$SERVER_LOG" 2>&1 &
 echo $! > "$SRV_DIR/server.pid")
if wait_for "$SERVER_LOG" 'Done \(' 180; then
    echo "сервер стенда: localhost:$HOKA_PORT (лог $SERVER_LOG)"
    grep -E 'clientmods|клиентских модулей|ERROR' "$SERVER_LOG" | grep -v WARNING | tail -8 || true
else
    echo "сервер не поднялся за 180 с — лог $SERVER_LOG:" >&2
    tail -30 "$SERVER_LOG" >&2
    exit 1
fi
