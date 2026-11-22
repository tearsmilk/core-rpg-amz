#!/bin/bash
# Проверка стенда целиком: сборка → сервер → Fabric-клиент с dev-ядром заходит → получает клиентские модули →
# hello показывает текст с сервера → devprobe выполняет скрипт → снимки экрана. Код выхода 0 — всё дошло
# (вход, модули hello и devprobe, кадр skyspike, если он раздан, ответ hello на /hoclient hello, снимок devprobe).
# Флаги: --no-build (взять уже собранное), --keep (не останавливать стенд в конце).
set -uo pipefail
. "$(dirname "$0")/env.sh"

BUILD=1; KEEP=0
for a in "$@"; do
    case "$a" in
        --no-build) BUILD=0 ;;
        --keep) KEEP=1 ;;
    esac
done
fail() { echo "ПРОВАЛ: $*" >&2; [[ $KEEP == 1 ]] || "$DEVCLIENT_DIR/stop.sh"; exit 1; }

if [[ $BUILD == 1 ]]; then
    "$DEVCLIENT_DIR/build-devcore.sh" || fail "сборка"
fi
"$DEVCLIENT_DIR/run-server.sh" || fail "сервер"
"$DEVCLIENT_DIR/run-client.sh" || fail "клиент"

echo "== жду вход и модули (до 600 с: llvmpipe и первая перекодировка MC Fabric-ом небыстрые)"
wait_for "$CLIENT_LOG" 'модуль hello .* загружен' 600 || {
    grep -E 'ERROR|Exception|Hoka Odyssey' "$CLIENT_LOG" | tail -20 >&2; fail "клиент не получил модуль hello"; }
grep -E 'Hoka Odyssey: (модулей|модуль)|devprobe: загружен|skyspike' "$CLIENT_LOG" | sed 's/^/  клиент: /'

if [[ -f "$HOKA_DEV_DIR/clientmods/skyspike.jar" ]]; then
    wait_for "$CLIENT_LOG" 'skyspike: первый кадр' 120 || fail "skyspike раздан, но не нарисовал ни одного кадра в START_MAIN"
fi
sleep 8                                                    # дать догрузиться чанкам
"$DEVCLIENT_DIR/screenshot.sh" smoke-join
# Окно поверх игры (например «скачать ресурспак?» — respack иногда успевает до HELLO мода) — закрыть Esc
echo screen | "$DEVCLIENT_DIR/input.sh" script - >/dev/null || fail "devprobe не выполнил скрипт"
if grep 'devprobe: экран' "$CLIENT_LOG" | tail -1 | grep -qv 'экран нет'; then
    echo "  $(grep 'devprobe: экран' "$CLIENT_LOG" | tail -1 | sed 's/.*devprobe: //') — закрываю (Esc)"
    "$DEVCLIENT_DIR/input.sh" key Escape
    sleep 2
    "$DEVCLIENT_DIR/screenshot.sh" smoke-ingame
fi

"$DEVCLIENT_DIR/cmd.sh" hoclient hello "Привет со стенда"
if wait_for "$SERVER_LOG" 'показал' 30; then
    grep 'показал' "$SERVER_LOG" | tail -1 | sed 's/^/  сервер: /'
    "$DEVCLIENT_DIR/screenshot.sh" smoke-hello
else
    fail "модуль hello не ответил на /hoclient hello (связь сервер ↔ клиентский модуль не работает?)"
fi

rm -f "$GAME_DIR/screenshots/devprobe-smoke.png"
printf 'hud off\nwait 5\nshot devprobe-smoke\nwait 20\nhud on\n' | "$DEVCLIENT_DIR/input.sh" script - \
    || fail "devprobe не выполнил скрипт со снимком"
[[ -s "$GAME_DIR/screenshots/devprobe-smoke.png" ]] || fail "снимок devprobe не появился"
echo "  снимок devprobe: $GAME_DIR/screenshots/devprobe-smoke.png"

echo "ГОТОВО: снимки в $SHOTS_DIR и $GAME_DIR/screenshots"
[[ $KEEP == 1 ]] || "$DEVCLIENT_DIR/stop.sh"
