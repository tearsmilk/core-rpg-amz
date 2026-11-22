# Общие настройки стенда tools/devclient (подключается остальными скриптами: . env.sh).
# Всё переопределяется переменными окружения. Стенд целиком локальный: боевой сервер и panel.hoka.ink не трогает.

DEVCLIENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOKA_REPO="$(cd "$DEVCLIENT_DIR/../.." && pwd)"            # hoka-odyssey/
CLIENT_MODULES="$HOKA_REPO/client-modules"

# Рабочая папка стенда: dev-ключ, копия ядра с dev-ключом, сервер, клиент, снимки. НЕ в репозитории.
HOKA_DEV_DIR="${HOKA_DEV_DIR:-${TMPDIR:-/tmp}/hoka-devclient}"
# Клон ядра SakuraMesd/HokaOdyssey (только читаем; копия для сборки — в $HOKA_DEV_DIR/core-src).
# Патченое ядро (docs/core-client-hooks) — укажите папку с наложенным патчем.
HOKA_CORE_DIR="${HOKA_CORE_DIR:-$HOKA_REPO/.hoka-core}"
# Ванильный клиент 1.21.11 (client.jar, libraries, classpath.txt, assets) — делает fetch-client.sh
MC_CLIENT_DIR="${MC_CLIENT_DIR:-$HOKA_DEV_DIR/mcclient}"

HOKA_PORT="${HOKA_PORT:-25601}"                  # порт локального сервера
HOKA_RESPACK_PORT="${HOKA_RESPACK_PORT:-25602}"  # HTTP-порт модуля respack (локальный)
HOKA_DISPLAY="${HOKA_DISPLAY:-:42}"              # дисплей Xvfb
HOKA_PLAYER="${HOKA_PLAYER:-DevBot}"             # ник (сервер стенда — без проверки аккаунтов)
HOKA_WIDTH="${HOKA_WIDTH:-1280}"
HOKA_HEIGHT="${HOKA_HEIGHT:-720}"
# Серверные модули этого репозитория, которые кладутся на сервер стенда (пусто — ни одного)
HOKA_OUR_MODULES="${HOKA_OUR_MODULES-gravity gravzones oxygen items spacesky coreshaders}"

JAVA21="${JAVA21:-/usr/lib/jvm/java-21-openjdk-amd64/bin/java}"   # клиент (MC 1.21.11 — Java 21)
JAVA25_HOME="${JAVA25_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"  # сервер и сборки Gradle

MC_VERSION=1.21.11
FABRIC_LOADER=0.19.5
FABRIC_API=0.141.6+1.21.11

# Второй (третий…) клиент: HOKA_INSTANCE=2 HOKA_PLAYER=DevBot2 HOKA_DISPLAY=:43 run-client.sh — своя папка игры,
# лог и pid; на своём дисплее, чтобы окна не перекрывались на снимках.
HOKA_INSTANCE="${HOKA_INSTANCE:-}"

SRV_DIR="$HOKA_DEV_DIR/server"
GAME_DIR="$HOKA_DEV_DIR/game$HOKA_INSTANCE"
SHOTS_DIR="$HOKA_DEV_DIR/shots"
CLIENT_LOG="$HOKA_DEV_DIR/client$HOKA_INSTANCE.log"
CLIENT_PID="$HOKA_DEV_DIR/client$HOKA_INSTANCE.pid"
XVFB_PID="$HOKA_DEV_DIR/xvfb${HOKA_DISPLAY#:}.pid"
SERVER_LOG="$SRV_DIR/server.log"

mkdir -p "$HOKA_DEV_DIR"

# Gradle с повтором: Maven Central иногда отвечает 429 — один поток и до трёх попыток
gradle_retry() {
    local dir="$1"; shift
    local i
    for i in 1 2 3; do
        if (cd "$dir" && JAVA_HOME="$JAVA25_HOME" ./gradlew --no-daemon --console=plain --max-workers=1 "$@"); then
            return 0
        fi
        echo "gradle: попытка $i не удалась, повтор через $((i * 20)) с" >&2
        sleep $((i * 20))
    done
    return 1
}

# ждать строку в файле: wait_for <файл> <regex> <секунды>
wait_for() {
    local f="$1" re="$2" t="$3" i
    for ((i = 0; i < t; i++)); do
        grep -qE "$re" "$f" 2>/dev/null && return 0
        sleep 1
    done
    return 1
}
