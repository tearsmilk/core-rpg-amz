#!/bin/bash
# Real Minecraft 1.21.11 client test for chatshader: textshader, tabshader and chatshader jars on mc-skilltest write the
# pack themselves (pack.dir of each plugin points to OUT/nexo/assets, configs are restored at the end), the pack is
# zipped from there, a real client under Xvfb joins, and for every position in RC_POSITIONS chat.position is set,
# /csh reload rewrites cs_config.glsl, the client reloads the pack (F3+T), clears the chat (F3+D) and screenshots are
# taken: chat closed with fresh /csh test lines, chat open with history and typed text, command suggestions, a chat
# message of the player (ClientboundPlayerChatPacket).
# Usage: tools/realclient.sh [--gui 2] [--size 1280x800] [--out ABS_DIR] [--margin 0] [--start bottom-left]
#        [--plugins textshader,tabshader,chatshader] [--regress top-left] [--bg 0.5] [--keep]
# --bg: textBackgroundOpacity of the client (0.816 gives chat line alpha 208, the suggestion list alpha).
# Every position also gets three shots while the fresh lines fade out (9-10 s after /csh test).
# Config backups of mc-skilltest plugins go to a temporary directory outside the repo and are removed after restore.
# --start: chat.position at server start (tests the marker on new connections; later positions use /csh reload).
# --regress: positions where tab, title, inventory, pause menu and F3 are also shot.
# The chatshader jar and plugins/chatshader are removed at the end if the script created them.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$PROJ/.." && pwd)"
SERVER="${RC_SERVER:-/home/user/mc-skilltest}"
CLIENT="${RC_CLIENT:-/home/user/mc-client}"
CLIENT_JAR_SRC="${RC_CLIENT_JAR:-$REPO/core-npc/tools/.cache/client-1.21.11.jar}"
PORT="${RC_PORT:-25601}"
DISPLAY_NUM="${RC_DISPLAY:-:98}"
POSITIONS="${RC_POSITIONS:-bottom-left top-left top-right bottom-right}"
PLUGINS="textshader,tabshader,chatshader"
GUI="2"
SIZE="1280x800"
OUT="$PROJ/preview/real"
MARGIN="0"
START="bottom-left"
REGRESS="bottom-left top-left"
BG="0.5"
KEEP=0
while [ $# -gt 0 ]; do
    case "$1" in
        --gui) GUI="$2"; shift 2 ;;
        --size) SIZE="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --margin) MARGIN="$2"; shift 2 ;;
        --start) START="$2"; shift 2 ;;
        --plugins) PLUGINS="$2"; shift 2 ;;
        --regress) REGRESS="$2"; shift 2 ;;
        --bg) BG="$2"; shift 2 ;;
        --keep) KEEP=1; shift ;;
        *) echo "unknown arg: $1"; exit 1 ;;
    esac
done
case "$OUT" in /*) ;; *) echo "--out must be absolute"; exit 1 ;; esac
W="${SIZE%x*}"
H="${SIZE#*x}"
GAME="$CLIENT/game"
PACKNAME="chatshader-test.zip"
LOG="$OUT/run.log"
NEXO_DIR="$OUT/nexo/assets"
CS_JAR="$SERVER/plugins/chatshader-1.0.0.jar"
CS_DIR="$SERVER/plugins/chatshader"
CS_JAR_CREATED=0
CS_DIR_CREATED=0
CONFIGS_CHANGED=""
XVFB_PID=""
CLIENT_PID=""
STARTED_SERVER=0
BACKUP="$(mktemp -d)"
mkdir -p "$OUT" "$GAME/resourcepacks"
: > "$LOG"
say() { echo "[realclient] $*" | tee -a "$LOG"; }
con() { echo "$*" > "$SERVER/console.in"; }

cleanup() {
    if [ "$KEEP" = 1 ]; then
        say "keep: client pid=$CLIENT_PID xvfb pid=$XVFB_PID display=$DISPLAY_NUM"
        return
    fi
    if [ -n "$CLIENT_PID" ] && kill -0 "$CLIENT_PID" 2>/dev/null; then
        kill "$CLIENT_PID" 2>/dev/null
        for _ in $(seq 20); do kill -0 "$CLIENT_PID" 2>/dev/null || break; sleep 0.5; done
        kill -9 "$CLIENT_PID" 2>/dev/null
    fi
    if [ "$STARTED_SERVER" = 1 ]; then
        con "time set 18000"
        sleep 1
        "$SERVER/stop.sh" >> "$LOG" 2>&1
        for _ in $(seq 60); do pgrep -f 'mc-skilltest-marker' >/dev/null || break; sleep 1; done
    fi
    for p in $CONFIGS_CHANGED; do
        if [ -f "$BACKUP/${p}_config_backup.yml" ]; then
            cp "$BACKUP/${p}_config_backup.yml" "$SERVER/plugins/$p/config.yml" && rm -f -- "$BACKUP/${p}_config_backup.yml"
            say "restored plugins/$p/config.yml"
        fi
    done
    rmdir -- "$BACKUP" 2>/dev/null
    if ! pgrep -f 'mc-skilltest-marker' >/dev/null; then
        if [ "$CS_JAR_CREATED" = 1 ] && [ -f "$CS_JAR" ]; then rm -f -- "$CS_JAR"; say "removed $CS_JAR"; fi
        if [ "$CS_DIR_CREATED" = 1 ] && [ -d "$CS_DIR" ] && [ "$CS_DIR" = "$SERVER/plugins/chatshader" ]; then
            [ -f "$CS_DIR/config.yml" ] && rm -f -- "$CS_DIR/config.yml"
            rmdir -- "$CS_DIR" 2>/dev/null && say "removed $CS_DIR"
        fi
    fi
    if [ -n "$XVFB_PID" ] && kill -0 "$XVFB_PID" 2>/dev/null; then kill "$XVFB_PID" 2>/dev/null; fi
    say "stopped"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

pgrep -f 'mc-skilltest-marker' >/dev/null && { say "stop the server first (plugins install the pack at start)"; exit 1; }
python3 -I "$HERE/realclient/setup_client.py" "$CLIENT" "$CLIENT_JAR_SRC" | tee -a "$LOG" || exit 1

for p in textshader tabshader chatshader; do
    case ",$PLUGINS," in *,$p,*) ;; *) continue ;; esac
    say "build $p jar"
    (cd "$REPO/$p" && gradle --offline -q jar >> "$LOG" 2>&1) || { say "gradle jar failed for $p"; exit 1; }
    if [ "$p" = chatshader ] && [ ! -e "$CS_JAR" ]; then CS_JAR_CREATED=1; fi
    cp "$REPO/$p/build/libs/$p-1.0.0.jar" "$SERVER/plugins/$p-1.0.0.jar"
done
if [ -d "$OUT/nexo" ]; then rm -rf -- "$OUT/nexo"; fi
mkdir -p "$NEXO_DIR"
for p in textshader tabshader; do
    cfg="$SERVER/plugins/$p/config.yml"
    [ -f "$cfg" ] || continue
    cp "$cfg" "$BACKUP/${p}_config_backup.yml"
    CONFIGS_CHANGED="$CONFIGS_CHANGED $p"
    python3 -I "$HERE/realclient/set_config.py" "$cfg" "pack.dir=\"$NEXO_DIR\"" >> "$LOG" 2>&1 || { say "config edit failed: $p"; exit 1; }
done
case ",$PLUGINS," in
    *,chatshader,*)
        if [ ! -e "$CS_DIR" ]; then
            CS_DIR_CREATED=1
            mkdir -p "$CS_DIR"
            cp "$PROJ/src/main/resources/config.yml" "$CS_DIR/config.yml"
        else
            cp "$CS_DIR/config.yml" "$BACKUP/chatshader_config_backup.yml"
            CONFIGS_CHANGED="$CONFIGS_CHANGED chatshader"
        fi
        python3 -I "$HERE/realclient/set_config.py" "$CS_DIR/config.yml" "pack.dir=\"$NEXO_DIR\"" "chat.position=$START" "chat.margin=$MARGIN" >> "$LOG" 2>&1 \
            || { say "config edit failed: chatshader"; exit 1; }
        ;;
    *)
        if [ -e "$CS_JAR" ]; then say "chatshader jar present but not in --plugins: remove it first"; exit 1; fi
        ;;
esac

build_pack() {
    python3 -I "$HERE/realclient/build_pack.py" "$GAME/resourcepacks/$PACKNAME" "$NEXO_DIR" | tee -a "$LOG"
    cp "$GAME/resourcepacks/$PACKNAME" "$OUT/pack_$1.zip"
}

say "start server"
T0=$(date +%s)
"$SERVER/start.sh" >> "$LOG" 2>&1
STARTED_SERVER=1
ok=0
for _ in $(seq 150); do
    sleep 1
    [ "$(stat -c %Y "$SERVER/server.log")" -ge "$T0" ] || continue
    head -3 "$SERVER/server.log" | grep -q 'JAVA_TOOL_OPTIONS\|Starting' || continue
    grep -q 'Done (' "$SERVER/server.log" && { ok=1; break; }
done
[ "$ok" = 1 ] || { say "server did not start"; exit 1; }
SLOG_START=$(wc -l < "$SERVER/server.log")
grep -E 'Шейдеры|шейдер|Чужой|Чат:|chatshader' "$SERVER/server.log" | tee -a "$LOG" > "$OUT/server_install.txt"
build_pack start
python3 -I "$HERE/realclient/options.py" "$GAME/options.txt" "$GUI" "$PACKNAME" "en_us" "$BG"

Xvfb "$DISPLAY_NUM" -screen 0 "${W}x${H}x24" -nolisten tcp >> "$LOG" 2>&1 &
XVFB_PID=$!
sleep 2
export DISPLAY="$DISPLAY_NUM"
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
NATIVES="$CLIENT/natives"
mkdir -p "$NATIVES"
CP="$(cat "$CLIENT/classpath.txt")"
AIDX="$(cat "$CLIENT/assetindex.txt")"
say "start client ${W}x${H} gui=$GUI"
(cd "$GAME" && exec java -Xmx3G -Xss2M -Djava.library.path="$NATIVES" -Dorg.lwjgl.system.SharedLibraryExtractPath="$NATIVES" \
    -Djna.tmpdir="$NATIVES" -Dio.netty.native.workdir="$NATIVES" -Dminecraft.launcher.brand=realclient -Dminecraft.launcher.version=1 \
    -cp "$CP" net.minecraft.client.main.Main --username TestBot --version 1.21.11 --gameDir "$GAME" --assetsDir "$CLIENT/assets" \
    --assetIndex "$AIDX" --accessToken 0 --versionType release --width "$W" --height "$H" \
    --quickPlayMultiplayer "localhost:$PORT" > "$OUT/client.log" 2>&1) &
CLIENT_PID=$!
joined=0
for _ in $(seq 240); do
    if tail -n +"$SLOG_START" "$SERVER/server.log" | grep -q 'TestBot joined the game'; then joined=1; break; fi
    kill -0 "$CLIENT_PID" 2>/dev/null || break
    sleep 1
done
[ "$joined" = 1 ] || { say "client did not join"; tail -30 "$OUT/client.log" | tee -a "$LOG"; exit 1; }
say "joined"
WIN=""
for _ in $(seq 30); do
    WIN="$(xdotool search --name 'Minecraft' 2>/dev/null | head -1)"
    [ -n "$WIN" ] && break
    sleep 1
done
[ -n "$WIN" ] && xdotool windowfocus "$WIN" 2>/dev/null

con "op TestBot"
con "time set 6000"
con "weather clear"
con "gamemode survival TestBot"
con "clear TestBot"
con "give TestBot diamond_sword"
con "give TestBot stone 64"
con "give TestBot oak_log 12"
con "effect clear TestBot"
con "tp TestBot 0 -60 0 -20 -8"
sleep 20

shot() {
    import -window root "$OUT/$1.png" 2>> "$LOG"
    say "shot $1"
}
key() { xdotool key --delay 80 "$@"; }
reload_pack() {
    xdotool key --delay 200 F3+t
    sleep 14
}
clear_chat() {
    xdotool key --delay 200 F3+d
    sleep 1
}

first=1
for pos in $POSITIONS; do
    tag="${pos}_m${MARGIN}"
    if [ "$first" = 1 ] && [ "$pos" = "$START" ]; then
        first=0
    else
        python3 -I "$HERE/realclient/set_config.py" "$CS_DIR/config.yml" "chat.position=$pos" "chat.margin=$MARGIN" >> "$LOG" 2>&1 || { say "config edit failed"; break; }
        con "csh reload"
        sleep 3
        build_pack "$tag"
        reload_pack
        first=0
    fi
    con "csh status"
    sleep 1
    clear_chat
    con "csh test TestBot"
    T_TEST=$(date +%s.%N)
    sleep 1.5
    shot "${tag}_01_closed"
    sleep "$(python3 -c "import time; print(max(0.0, $T_TEST + 9.15 - time.time()))")"
    shot "${tag}_02_fading_a"
    shot "${tag}_02_fading_b"
    shot "${tag}_02_fading_c"
    sleep 1
    shot "${tag}_02_faded"
    key t
    sleep 1
    xdotool type --delay 40 "hello chat"
    sleep 1
    shot "${tag}_03_open"
    for _ in $(seq 10); do key BackSpace; done
    xdotool type --delay 60 "/csh "
    sleep 1.5
    shot "${tag}_04_suggest"
    for _ in $(seq 5); do key BackSpace; done
    xdotool type --delay 60 "/ts set "
    sleep 1.5
    shot "${tag}_05_usage"
    key ctrl+a BackSpace
    xdotool type --delay 40 "player chat line"
    key Return
    sleep 1.5
    shot "${tag}_06_player"
    case " $REGRESS " in
        *" $pos "*)
            xdotool keydown Tab; sleep 2; shot "${tag}_10_tab"; xdotool keyup Tab; sleep 1
            con "tsh test-title TestBot"
            sleep 2
            shot "${tag}_11_title"
            sleep 6
            con "csh test TestBot"
            sleep 1
            key e
            sleep 2
            shot "${tag}_12_inventory"
            key Escape
            sleep 1
            key Escape
            sleep 2
            shot "${tag}_13_pause"
            key Escape
            sleep 1
            key F3
            sleep 1.5
            shot "${tag}_14_f3"
            key F3
            sleep 1
            con "ts title TestBot wave:red Заголовок волной | подзаголовок"
            sleep 1.2
            shot "${tag}_15_fxtitle"
            con "title TestBot title $(python3 -I "$HERE/realclient/gradient.py" "Градиент из многих цветов")"
            sleep 1.2
            shot "${tag}_16_gradient"
            sleep 5
            key t
            sleep 0.5
            xdotool type --delay 40 "/ts menu"
            key Return
            sleep 2
            SW=$(( W / GUI )); SH=$(( H / GUI ))
            for slot in "1 1" "4 2" "8 3"; do
                set -- $slot
                MX=$(( ((SW - 176) / 2 + 8 + 18 * $1 + 8) * GUI )); MY=$(( ((SH - 222) / 2 + 18 + 18 * $2 + 8) * GUI ))
                xdotool mousemove "$MX" "$MY"
                sleep 1.2
                shot "${tag}_17_menu_tip_$1_$2"
            done
            key Escape
            sleep 1
            ;;
    esac
done
tail -n +"$SLOG_START" "$SERVER/server.log" | grep -E 'chatshader|Чат:|Метка|Позиция|Шейдеры|ERROR|Exception' | head -80 > "$OUT/server_tail.txt"
grep -E 'Shader|shader|GLSL|Couldn.t|Failed|ERROR|Exception' "$OUT/client.log" | head -40 > "$OUT/client_errors.txt"
say "done: $OUT"
