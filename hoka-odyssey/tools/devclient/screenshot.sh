#!/bin/bash
# Снимок экрана стенда: screenshot.sh [имя] [--f2]
#   по умолчанию — снимок дисплея Xvfb (ImageMagick import, иначе xwd + convert) в $SHOTS_DIR/<имя>.png;
#   --f2 — внутриигровой снимок (клавиша F2): файл появится в $GAME_DIR/screenshots/.
# Печатает путь к файлу.
set -euo pipefail
. "$(dirname "$0")/env.sh"

name="shot-$(date +%H%M%S)"; f2=0
for a in "$@"; do
    case "$a" in
        --f2) f2=1 ;;
        *) name="$a" ;;
    esac
done

if [[ $f2 == 1 ]]; then
    before="$(ls -t "$GAME_DIR/screenshots" 2>/dev/null | head -1 || true)"
    DISPLAY="$HOKA_DISPLAY" xdotool key F2
    for _ in $(seq 1 40); do
        last="$(ls -t "$GAME_DIR/screenshots" 2>/dev/null | head -1 || true)"
        [[ -n "$last" && "$last" != "$before" ]] && { echo "$GAME_DIR/screenshots/$last"; exit 0; }
        sleep 0.25
    done
    echo "F2: снимок не появился в $GAME_DIR/screenshots" >&2
    exit 1
fi

mkdir -p "$SHOTS_DIR"
out="$SHOTS_DIR/$name.png"
if command -v import >/dev/null; then
    import -display "$HOKA_DISPLAY" -window root "$out"
else
    xwd -display "$HOKA_DISPLAY" -root -silent | convert xwd:- "$out"
fi
echo "$out"
