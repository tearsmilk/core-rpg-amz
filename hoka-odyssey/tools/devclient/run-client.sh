#!/bin/bash
# Fabric-клиент 1.21.11 с dev-ядром Hoka Odyssey под Xvfb на программном GL (Mesa llvmpipe), в фоне.
# Сразу заходит на localhost:$HOKA_PORT (quick play). Лог — $CLIENT_LOG.
#   - ванильный клиент: $MC_CLIENT_DIR (fetch-client.sh);
#   - Fabric Loader $FABRIC_LOADER и его библиотеки (по профилю meta.fabricmc.net) — кэш $HOKA_DEV_DIR/fabric;
#   - моды: Fabric API + dev-ядро из $HOKA_DEV_DIR/mods (build-devcore.sh).
# Флаг --no-join: только главное меню.
set -euo pipefail
. "$(dirname "$0")/env.sh"

JOIN=1
[[ "${1:-}" == "--no-join" ]] && JOIN=0

[[ -f "$MC_CLIENT_DIR/classpath.txt" ]] || { echo "нет ванильного клиента в $MC_CLIENT_DIR — fetch-client.sh" >&2; exit 1; }
ls "$HOKA_DEV_DIR"/mods/hoka-odyssey-*.jar >/dev/null 2>&1 || { echo "нет dev-ядра в $HOKA_DEV_DIR/mods — build-devcore.sh" >&2; exit 1; }

# --- Fabric Loader и библиотеки (один раз) ---
FAB="$HOKA_DEV_DIR/fabric"
mkdir -p "$FAB/libs"
PROFILE="$FAB/profile-$MC_VERSION-$FABRIC_LOADER.json"
if [[ ! -s "$PROFILE" ]]; then
    curl -fsSL --retry 3 -o "$PROFILE.tmp" \
        "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION/$FABRIC_LOADER/profile/json"
    mv "$PROFILE.tmp" "$PROFILE"
fi
FABRIC_CP=""
while read -r url rel; do
    f="$FAB/libs/$rel"
    if [[ ! -s "$f" ]]; then
        mkdir -p "$(dirname "$f")"
        # «a && b» внутри цикла set -e не останавливает: без явной проверки недокачанная библиотека молча выпадала
        # из classpath, и клиент падал потом с непонятной ошибкой
        curl -fsSL --retry 3 -o "$f.tmp" "$url" || { echo "не скачалась библиотека Fabric: $url" >&2; exit 1; }
        mv "$f.tmp" "$f"
    fi
    FABRIC_CP="$FABRIC_CP$f:"
done < <(python3 -I - "$PROFILE" <<'PY'
import json, sys
p = json.load(open(sys.argv[1]))
for lib in p["libraries"]:
    g, a, v = lib["name"].split(":")[:3]
    rel = f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.jar"
    print(lib.get("url", "https://maven.fabricmc.net/").rstrip("/") + "/" + rel, rel)
PY
)
MAIN_CLASS="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["mainClass"])' "$PROFILE")"

# --- моды ---
mkdir -p "$GAME_DIR/mods"
FAPI="$FAB/fabric-api-$FABRIC_API.jar"
if [[ ! -s "$FAPI" ]]; then
    curl -fsSL --retry 3 -o "$FAPI.tmp" \
        "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FABRIC_API/fabric-api-$FABRIC_API.jar"
    mv "$FAPI.tmp" "$FAPI"
fi
rm -f "$GAME_DIR/mods/"*.jar
cp "$FAPI" "$HOKA_DEV_DIR"/mods/*.jar "$GAME_DIR/mods/"

# --- настройки игры: без обучалок, без паузы при потере фокуса, малая дальность (llvmpipe медленный) ---
if [[ ! -f "$GAME_DIR/options.txt" ]]; then
    cat > "$GAME_DIR/options.txt" <<EOF
version:4671
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
tutorialStep:none
pauseOnLostFocus:false
narrator:0
renderDistance:6
simulationDistance:5
maxFps:60
enableVsync:false
soundCategory_master:0.0
guiScale:2
EOF
fi

# --- Xvfb ---
# Дисплей жив, если есть сокет и жив процесс из lock-файла X-сервера. Проверять через xdotool нельзя: без xdotool
# (он необязателен) проверка всегда «не жив», и скрипт удалял сокет и lock работающего Xvfb.
DISP_N="${HOKA_DISPLAY#:}"
x_alive() {
    [[ -S /tmp/.X11-unix/X$DISP_N && -f /tmp/.X$DISP_N-lock ]] && kill -0 "$(tr -dc 0-9 < "/tmp/.X$DISP_N-lock")" 2>/dev/null
}
if ! x_alive; then
    rm -f "/tmp/.X$DISP_N-lock" "/tmp/.X11-unix/X$DISP_N"           # остатки упавшего X-сервера
    Xvfb "$HOKA_DISPLAY" -screen 0 "${HOKA_WIDTH}x${HOKA_HEIGHT}x24" -nolisten tcp > "$HOKA_DEV_DIR/xvfb${DISP_N}.log" 2>&1 &
    echo $! > "$XVFB_PID"
    for _ in $(seq 1 40); do x_alive && break; sleep 0.25; done
    x_alive || { echo "Xvfb $HOKA_DISPLAY не поднялся — лог $HOKA_DEV_DIR/xvfb${DISP_N}.log" >&2; exit 1; }
fi

"$DEVCLIENT_DIR/stop.sh" client >/dev/null 2>&1 || true

ARGS=(--username "$HOKA_PLAYER" --version "$MC_VERSION" --gameDir "$GAME_DIR"
      --assetsDir "$MC_CLIENT_DIR/assets" --assetIndex "$(cat "$MC_CLIENT_DIR/assetindex.txt")"
      --accessToken 0 --versionType release --width "$HOKA_WIDTH" --height "$HOKA_HEIGHT")
[[ $JOIN == 1 ]] && ARGS+=(--quickPlayMultiplayer "localhost:$HOKA_PORT")

mkdir -p "$MC_CLIENT_DIR/natives"
cd "$GAME_DIR"
DISPLAY="$HOKA_DISPLAY" LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
    "$JAVA21" -Xmx3G -Xss2M \
    -XX:+UnlockDiagnosticVMOptions -XX:-UseBASE64Intrinsics \
    -Djava.library.path="$MC_CLIENT_DIR/natives" -Dorg.lwjgl.system.SharedLibraryExtractPath="$MC_CLIENT_DIR/natives" \
    -cp "$FABRIC_CP$(cat "$MC_CLIENT_DIR/classpath.txt")" "$MAIN_CLASS" "${ARGS[@]}" > "$CLIENT_LOG" 2>&1 &
echo $! > "$CLIENT_PID"
echo "клиент запущен (pid $(cat "$CLIENT_PID"), дисплей $HOKA_DISPLAY, лог $CLIENT_LOG)"
