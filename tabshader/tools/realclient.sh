#!/bin/bash
# Real Minecraft 1.21.11 client test: builds the pack, starts mc-skilltest + client under Xvfb, takes screenshots.
# Usage: tools/realclient.sh [--ref HEAD|worktree] [--pack none] [--overlay DIR] [--gui 2] [--size 1280x800] [--out DIR] [--shots all|quick|positions] [--no-jars] [--keep]
# positions: for each "tab:board" pair in RC_POSITIONS edits plugins/tabshader/config.yml (backup restored at the end),
# runs /tsh reload and takes the HUD and the held tab; RC_CONFIG adds "section.key=value" edits.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$PROJ/.." && pwd)"
SERVER="${RC_SERVER:-/home/user/mc-skilltest}"
CLIENT="${RC_CLIENT:-/home/user/mc-client}"
CLIENT_JAR_SRC="${RC_CLIENT_JAR:-$REPO/core-npc/tools/.cache/client-1.21.11.jar}"
PORT="${RC_PORT:-25601}"
DISPLAY_NUM="${RC_DISPLAY:-:99}"
REF="worktree"
PACKMODE="pack"
GUI="2"
SIZE="1280x800"
OUT="$PROJ/preview/real"
JARS=1
KEEP=0
SHOTS="all"
OVERLAY=""
while [ $# -gt 0 ]; do
    case "$1" in
        --ref) REF="$2"; shift 2 ;;
        --pack) PACKMODE="$2"; shift 2 ;;
        --gui) GUI="$2"; shift 2 ;;
        --size) SIZE="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --no-jars) JARS=0; shift ;;
        --keep) KEEP=1; shift ;;
        --shots) SHOTS="$2"; shift 2 ;;
        --overlay) OVERLAY="$(cd "$2" && pwd)"; shift 2 ;;
        *) echo "unknown arg: $1"; exit 1 ;;
    esac
done
W="${SIZE%x*}"
H="${SIZE#*x}"
GAME="$CLIENT/game"
PACKNAME="tabshader-test.zip"
LOG="$OUT/run.log"
mkdir -p "$OUT" "$GAME/resourcepacks"
: > "$LOG"
say() { echo "[realclient] $*" | tee -a "$LOG"; }

XVFB_PID=""
CLIENT_PID=""
STARTED_SERVER=0
TIME_CHANGED=0

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
    if [ "$TIME_CHANGED" = 1 ] && pgrep -f 'mc-skilltest-marker' >/dev/null; then
        con "time set 18000"
        sleep 1
    fi
    if [ "$STARTED_SERVER" = 1 ]; then
        "$SERVER/stop.sh" >> "$LOG" 2>&1
    fi
    if [ -n "$XVFB_PID" ] && kill -0 "$XVFB_PID" 2>/dev/null; then
        kill "$XVFB_PID" 2>/dev/null
    fi
    say "stopped"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

python3 -I "$HERE/realclient/setup_client.py" "$CLIENT" "$CLIENT_JAR_SRC" | tee -a "$LOG" || exit 1

if [ "$PACKMODE" != "none" ]; then
    python3 -I "$HERE/realclient/build_pack.py" "$REPO" "$GAME/resourcepacks/$PACKNAME" "$REF" "$OVERLAY" | tee -a "$LOG"
    [ "${PIPESTATUS[0]}" = 0 ] || { say "pack build failed"; exit 1; }
    cp "$GAME/resourcepacks/$PACKNAME" "$OUT/pack.zip"
    PACKARG="$PACKNAME"
else
    PACKARG="none"
fi
python3 -I "$HERE/realclient/options.py" "$GAME/options.txt" "$GUI" "$PACKARG" "en_us"

if [ "$JARS" = 1 ]; then
    for p in tabshader textshader; do
        say "build $p jar"
        (cd "$REPO/$p" && gradle --offline -q jar >> "$LOG" 2>&1) || { say "gradle jar failed for $p"; exit 1; }
        src="$REPO/$p/build/libs/$p-1.0.0.jar"
        [ -f "$src" ] || { say "missing $src"; exit 1; }
        cp "$src" "$SERVER/plugins/$p-1.0.0.jar"
    done
fi

if ! pgrep -f 'mc-skilltest-marker' >/dev/null; then
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
else
    say "server already running, reusing it"
fi
SLOG_START=$(wc -l < "$SERVER/server.log")

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
say "start client ${W}x${H} gui=$GUI pack=$PACKARG ref=$REF"
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

con "time set 6000"
TIME_CHANGED=1
con "weather clear"
con "gamemode survival TestBot"
con "clear TestBot"
con "give TestBot diamond_sword"
con "give TestBot stone 64"
con "give TestBot oak_log 12"
con "give TestBot bread 5"
con "effect clear TestBot"
con "tp TestBot 0 -60 0 -20 -8"
sleep 20

shot() {
    import -window root "$OUT/$1.png" 2>> "$LOG"
    say "shot $1"
}
crop() {
    convert "$OUT/$1.png" -crop "$3" +repage -filter point -resize 300% "$OUT/$2.png" 2>> "$LOG"
}
key() { xdotool key --delay 80 "$@"; }

if [ "$SHOTS" = "positions" ]; then
    CFG="$SERVER/plugins/tabshader/config.yml"
    [ -f "$CFG" ] || { say "missing $CFG"; exit 1; }
    cp "$CFG" "$OUT/config_backup.yml"
    for combo in ${RC_POSITIONS:-center:right top-left:right top-right:left bottom-left:top-right bottom-right:top-left center:bottom-left center:bottom-right top-left:top-left top-right:right bottom-left:left}; do
        tp="${combo%%:*}"
        bp="${combo#*:}"
        python3 -I "$HERE/realclient/set_config.py" "$CFG" "tab.position=$tp" "board.position=$bp" ${RC_CONFIG:-} >> "$LOG" 2>&1 \
            || { say "config edit failed"; break; }
        con "tsh reload"
        sleep 3
        shot "pos_${tp}__${bp}_hud"
        xdotool keydown Tab
        sleep 2
        shot "pos_${tp}__${bp}_tab"
        xdotool keyup Tab
        sleep 1
    done
    cp "$OUT/config_backup.yml" "$CFG"
    con "tsh reload"
    sleep 2
    grep -E 'Shader|shader|GLSL|Couldn.t|Failed|ERROR|Exception' "$OUT/client.log" | head -40 > "$OUT/client_errors.txt"
    say "done: $OUT"
    exit 0
fi

shot 01_hud
xdotool keydown Tab
sleep 2
shot 02_tab
xdotool keyup Tab
sleep 1
if [ "$SHOTS" = "all" ]; then
con "tsh test-title TestBot"
sleep 2
shot 03_title
sleep 6
con "tsh test-actionbar TestBot"
sleep 1.5
shot 04_actionbar
sleep 4
key e
sleep 2
shot 05_inventory
key Escape
sleep 1
key t
sleep 1
xdotool type --delay 60 "/ts preview sparkle"
key Return
sleep 1.5
key t
sleep 1
xdotool type --delay 60 "hello chat"
sleep 1
shot 06_chat
key Escape
sleep 1
key Escape
sleep 2
shot 07_pause
key Escape
sleep 1
xdotool keydown Tab
sleep 1.5
shot 08_tab_again
xdotool keyup Tab
fi

crop 02_tab 02_tab_zoom "$((220 * GUI))x$((60 * GUI))+$((W / 2 - 110 * GUI))+0"
crop 01_hud 01_sidebar_zoom "$((90 * GUI))x$((100 * GUI))+$((W - 90 * GUI))+$((H / 2 - 50 * GUI))"
crop 01_hud 01_hotbar_zoom "$((200 * GUI))x$((45 * GUI))+$((W / 2 - 100 * GUI))+$((H - 45 * GUI))"
crop 01_hud 01_bossbar_zoom "$((200 * GUI))x$((24 * GUI))+$((W / 2 - 100 * GUI))+0"
grep -E 'Shader|shader|GLSL|Couldn.t|Failed|ERROR|Exception' "$OUT/client.log" | head -40 > "$OUT/client_errors.txt"
say "done: $OUT"
