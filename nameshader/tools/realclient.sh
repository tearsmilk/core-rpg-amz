#!/bin/bash
# Real Minecraft 1.21.11 client test for nameshader: builds the pack, starts mc-skilltest + client under Xvfb,
# a mineflayer bot (NameBot) stands in front of the camera, screenshots of nametags and vanilla text.
# Usage: tools/realclient.sh [--packs textshader,tabshader,nameshader] [--gui 2] [--size 1280x800] [--out DIR]
#        [--bg chat|everywhere] [--opacity 0.5] [--shots all|tags] [--packzip ZIP] [--keep]
# --packzip: use a ready pack zip (for example one written by Nexo's pack writer) instead of building one.
# --packs: plugins whose files go into the test pack, installed by tools/shaderhooks in this order (stubs for the
# plugins that are not listed); the content sha1 does not depend on the order. "none" runs without a resource pack.
# Everything the script places in the world (wall of white_wool, one oak_sign) is removed at the end; the nameshader
# jar and plugins/nameshader are removed at the end if the script created them.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$PROJ/.." && pwd)"
SERVER="${RC_SERVER:-/home/user/mc-skilltest}"
CLIENT="${RC_CLIENT:-$PROJ/tools/.cache/client}"
BASE_CLIENT="${RC_BASE_CLIENT:-/home/user/mc-client}"
CAM="${RC_CAM:-NsCam}"
CLIENT_JAR_SRC="${RC_CLIENT_JAR:-$REPO/core-npc/tools/.cache/client-1.21.11.jar}"
PORT="${RC_PORT:-25601}"
DISPLAY_NUM="${RC_DISPLAY:-:97}"
PACKS="textshader,tabshader,nameshader"
GUI="2"
SIZE="1280x800"
OUT="$PROJ/preview/real"
KEEP=0
PACKZIP=""
SHOTS="all"
BG="chat"
OPACITY="0.5"
X0="${RC_X:-40}"
Z0="${RC_Z:-40}"
while [ $# -gt 0 ]; do
    case "$1" in
        --packs) PACKS="$2"; shift 2 ;;
        --gui) GUI="$2"; shift 2 ;;
        --size) SIZE="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --bg) BG="$2"; shift 2 ;;
        --opacity) OPACITY="$2"; shift 2 ;;
        --shots) SHOTS="$2"; shift 2 ;;
        --packzip) PACKZIP="$2"; shift 2 ;;
        --keep) KEEP=1; shift ;;
        *) echo "unknown arg: $1"; exit 1 ;;
    esac
done
W="${SIZE%x*}"
H="${SIZE#*x}"
GAME="$CLIENT/game"
PACKNAME="nameshader-test.zip"
LOG="$OUT/run.log"
CTL="$OUT/bot.ctl"
JAR_DEST="$SERVER/plugins/nameshader-1.0.0.jar"
DATA_DIR="$SERVER/plugins/nameshader"
mkdir -p "$OUT" "$GAME/resourcepacks"
: > "$LOG"
say() { echo "[realclient] $*" | tee -a "$LOG"; }

XVFB_PID=""
CLIENT_PID=""
BOT_PID=""
STARTED_SERVER=0
WORLD_CHANGED=0
JAR_CREATED=0
DATA_CREATED=0
[ -e "$JAR_DEST" ] || JAR_CREATED=1
[ -e "$DATA_DIR" ] || DATA_CREATED=1

con() { echo "$*" > "$SERVER/console.in"; }
running() { pgrep -f 'mc-skilltest-marker' >/dev/null; }

unbuild() {
    con "fill $((X0 + -2)) -60 $((Z0 + -2)) $((X0 + 2)) -57 $((Z0 + -2)) air replace white_wool"
    con "fill $((X0 + 2)) -60 $((Z0 + -1)) $((X0 + 2)) -60 $((Z0 + -1)) air replace oak_sign"
    con "kill @e[tag=nameshader_test]"
}

cleanup() {
    if [ "$KEEP" = 1 ]; then
        say "keep: client pid=$CLIENT_PID bot pid=$BOT_PID xvfb pid=$XVFB_PID display=$DISPLAY_NUM"
        return
    fi
    echo quit > "$CTL"
    sleep 1
    if [ -n "$BOT_PID" ] && kill -0 "$BOT_PID" 2>/dev/null; then kill "$BOT_PID" 2>/dev/null; fi
    if [ -n "$CLIENT_PID" ] && kill -0 "$CLIENT_PID" 2>/dev/null; then
        kill "$CLIENT_PID" 2>/dev/null
        for _ in $(seq 20); do kill -0 "$CLIENT_PID" 2>/dev/null || break; sleep 0.5; done
        kill -9 "$CLIENT_PID" 2>/dev/null
    fi
    if [ "$WORLD_CHANGED" = 1 ] && running; then
        unbuild
        con "time set 18000"
        sleep 2
    fi
    if [ "$STARTED_SERVER" = 1 ]; then
        "$SERVER/stop.sh" >> "$LOG" 2>&1
    fi
    if ! running; then
        if [ "$JAR_CREATED" = 1 ] && [ -n "$JAR_DEST" ] && [ -f "$JAR_DEST" ]; then rm -f -- "$JAR_DEST"; say "removed $JAR_DEST"; fi
        if [ "$DATA_CREATED" = 1 ] && [ -n "$DATA_DIR" ] && [ "$DATA_DIR" = "$SERVER/plugins/nameshader" ] && [ -d "$DATA_DIR" ]; then
            rm -f -- "$DATA_DIR/config.yml"
            rmdir -- "$DATA_DIR" 2>/dev/null && say "removed $DATA_DIR"
        fi
    fi
    if [ -n "$XVFB_PID" ] && kill -0 "$XVFB_PID" 2>/dev/null; then
        kill "$XVFB_PID" 2>/dev/null
    fi
    rm -f -- "$CTL"
    say "stopped"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

mkdir -p "$CLIENT"
for f in 1.21.11.json manifest.json client.jar libraries assets; do
    [ -e "$CLIENT/$f" ] || { [ -e "$BASE_CLIENT/$f" ] && ln -s "$BASE_CLIENT/$f" "$CLIENT/$f"; }
done
python3 -I "$HERE/realclient/setup_client.py" "$CLIENT" "$CLIENT_JAR_SRC" | tee -a "$LOG" || exit 1
if [ -n "$PACKZIP" ]; then
    cp -- "$PACKZIP" "$GAME/resourcepacks/$PACKNAME" || exit 1
    say "pack from zip $PACKZIP"
    PACKS="zip"
    PACKARG="$PACKNAME"
elif [ "$PACKS" != "none" ]; then
    python3 -I "$HERE/realclient/build_pack.py" "$REPO" "$GAME/resourcepacks/$PACKNAME" "$PACKS" | tee -a "$LOG"
    [ "${PIPESTATUS[0]}" = 0 ] || { say "pack build failed"; exit 1; }
    PACKARG="$PACKNAME"
else
    PACKARG="none"
fi
CHAT_ONLY="true"
[ "$BG" = "everywhere" ] && CHAT_ONLY="false"
python3 -I "$HERE/realclient/options.py" "$GAME/options.txt" "$GUI" "$PACKARG" "en_us" "$OPACITY" "$CHAT_ONLY"

say "build nameshader jar"
(cd "$PROJ" && gradle --offline -q jar >> "$LOG" 2>&1) || { say "gradle jar failed"; exit 1; }
if running; then
    say "server already running: stop it first (the plugin jar must be loaded at start)"
    exit 1
fi
cp "$PROJ/build/libs/nameshader-1.0.0.jar" "$JAR_DEST"

say "start server"
T0=$(date +%s)
"$SERVER/start.sh" >> "$LOG" 2>&1
STARTED_SERVER=1
ok=0
for _ in $(seq 150); do
    sleep 1
    [ "$(stat -c %Y "$SERVER/server.log")" -ge "$T0" ] || continue
    grep -q 'Done (' "$SERVER/server.log" && { ok=1; break; }
done
[ "$ok" = 1 ] || { say "server did not start"; exit 1; }
grep -E 'nameshader' "$SERVER/server.log" | head -5 | tee -a "$LOG"
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
say "start client ${W}x${H} gui=$GUI pack=$PACKARG packs=$PACKS bg=$BG opacity=$OPACITY"
(cd "$GAME" && exec java -Xmx3G -Xss2M -Djava.library.path="$NATIVES" -Dorg.lwjgl.system.SharedLibraryExtractPath="$NATIVES" \
    -Djna.tmpdir="$NATIVES" -Dio.netty.native.workdir="$NATIVES" -Dminecraft.launcher.brand=realclient -Dminecraft.launcher.version=1 \
    -cp "$CP" net.minecraft.client.main.Main --username "$CAM" --version 1.21.11 --gameDir "$GAME" --assetsDir "$CLIENT/assets" \
    --assetIndex "$AIDX" --accessToken 0 --versionType release --width "$W" --height "$H" \
    --quickPlayMultiplayer "localhost:$PORT" > "$OUT/client.log" 2>&1) &
CLIENT_PID=$!

joined=0
for _ in $(seq 240); do
    if tail -n +"$SLOG_START" "$SERVER/server.log" | grep -q "$CAM joined the game"; then joined=1; break; fi
    kill -0 "$CLIENT_PID" 2>/dev/null || break
    sleep 1
done
[ "$joined" = 1 ] || { say "client did not join"; tail -30 "$OUT/client.log" | tee -a "$LOG"; exit 1; }
say "joined"

echo stand > "$CTL"
RC_PORT="$PORT" node "$HERE/realclient/bot.js" "$CTL" NameBot > "$OUT/bot.log" 2>&1 &
BOT_PID=$!
for _ in $(seq 30); do grep -q SPAWNED "$OUT/bot.log" && break; sleep 1; done
grep -q SPAWNED "$OUT/bot.log" || { say "bot did not spawn"; exit 1; }

WIN="$(xdotool search --name 'Minecraft' 2>/dev/null | head -1)"
[ -n "$WIN" ] && xdotool windowfocus "$WIN" 2>/dev/null

WORLD_CHANGED=1
con "time set 6000"
con "weather clear"
con "gamemode creative $CAM"
con "gamemode survival NameBot"
con "clear $CAM"
con "give $CAM diamond_sword[custom_name='Меч проверки']"
con "give $CAM stone 64"
con "effect clear $CAM"
con "tp NameBot $((X0 + 0)) -60 $((Z0 + -4)) 0 0"
con "tp $CAM $((X0 + 0)) -60 $((Z0 + 0)) 180 -12"
sleep 15
SLOG_CHECK=$(wc -l < "$SERVER/server.log")
con "execute if blocks $((X0 - 4)) -60 $((Z0 - 8)) $((X0 + 8)) -55 $((Z0 + 4)) $((X0 - 4)) 200 $((Z0 - 8)) all run seed"
sleep 1
tail -n +"$SLOG_CHECK" "$SERVER/server.log" | grep -q 'Seed: \[' || { say "scene area at $X0,$Z0 is not empty air, pick RC_X/RC_Z"; exit 1; }

shot() {
    if [ "${NIGHT:-0}" != 1 ]; then con "time set 6000"; sleep 1; fi
    import -window root "$OUT/$1.png" 2>> "$LOG"
    convert "$OUT/$1.png" -crop "$((W / 2))x$((H / 4))+$((W / 4))+$((H * 3 / 8))" +repage -filter point -resize 200% "$OUT/$1_zoom.png" 2>> "$LOG"
    say "shot $1"
}
key() { xdotool key --delay 80 "$@"; }

shot 01_front
con "tp $CAM $((X0 + 3)) -60 $((Z0 + -1)) 135 -12"
sleep 3
shot 02_side
con "tp $CAM $((X0 + 0)) -60 $((Z0 + 0)) 180 -12"
con "fill $((X0 + -2)) -60 $((Z0 + -2)) $((X0 + 2)) -57 $((Z0 + -2)) white_wool replace air"
sleep 3
shot 03_wall
con "fill $((X0 + -2)) -60 $((Z0 + -2)) $((X0 + 2)) -57 $((Z0 + -2)) air replace white_wool"
echo sneak > "$CTL"
sleep 3
shot 04_sneak
con "fill $((X0 + -2)) -60 $((Z0 + -2)) $((X0 + 2)) -57 $((Z0 + -2)) white_wool replace air"
sleep 3
shot 05_sneak_wall
con "fill $((X0 + -2)) -60 $((Z0 + -2)) $((X0 + 2)) -57 $((Z0 + -2)) air replace white_wool"
echo stand > "$CTL"
con "tp $CAM $((X0 + 0)) -60 $((Z0 + 3)) 180 -8"
sleep 3
shot 06_far
con "time set 18000"
con "tp $CAM $((X0 + 0)) -60 $((Z0 + 0)) 180 -5"
sleep 3
NIGHT=1 shot 06b_night
if [ "$SHOTS" = "all" ]; then
    con "tp NameBot $((X0 + 6)) -60 $((Z0 + -6)) 0 0"
    con "tp $CAM $((X0 + 0)) -60 $((Z0 + 0)) 180 -5"
    sleep 2
    con "nsh test $CAM"
    sleep 4
    shot 07_test
    con "kill @e[tag=nameshader_test]"
    con "setblock $((X0 + 2)) -60 $((Z0 + -1)) oak_sign[rotation=0]{front_text:{messages:['\"Табличка\"','\"проверка\"','\"\"','\"\"']}}"
    con "tp $CAM $((X0 + 0)) -59 $((Z0 + 1)) -135 20"
    sleep 3
    key 2
    sleep 0.3
    key 1
    sleep 0.6
    shot 08_sign_item
    con "tp $CAM $((X0 + 0)) -60 $((Z0 + 0)) 180 -5"
    key t
    sleep 1
    xdotool type --delay 60 "hello chat"
    sleep 1
    shot 09_chat
    key Escape
    sleep 1
    xdotool keydown Tab
    sleep 2
    shot 10_tab
    xdotool keyup Tab
    sleep 1
    CFG="$DATA_DIR/config.yml"
    cp "$CFG" "$OUT/config_backup.yml"
    python3 -I "$HERE/realclient/set_config.py" "$CFG" "nametags.style-text-displays=true" >> "$LOG" 2>&1
    con "nsh reload"
    sleep 1
    con "nsh test $CAM"
    sleep 4
    shot 11_displays_styled
    con "nsh status"
    sleep 1
    cp "$OUT/config_backup.yml" "$CFG"
    con "nsh reload"
    sleep 2
    shot 12_displays_back
fi
grep -E 'Shader|shader|GLSL|Couldn.t|Failed|ERROR|Exception' "$OUT/client.log" | head -40 > "$OUT/client_errors.txt"
tail -n +"$SLOG_START" "$SERVER/server.log" | grep -i -E 'nameshader|nsh|Текстовые|Тестовые|Пак Nexo|Плагины|Exception' | head -40 > "$OUT/server_lines.txt"
say "done: $OUT"
