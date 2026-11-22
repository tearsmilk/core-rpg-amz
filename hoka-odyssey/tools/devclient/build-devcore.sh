#!/bin/bash
# Сборка всего для стенда из клона ядра $HOKA_CORE_DIR (сам клон не меняется — работаем с копией):
#   1) копия ядра → $HOKA_DEV_DIR/core-src, в ней:
#        - PUBLIC_KEY клиентского ядра заменён открытым dev-ключом (gen-devkey.sh),
#        - сервер пускает без проверки аккаунтов Mojang (Auth.Offline) — ник $HOKA_PLAYER без лицензии;
#      Обе правки — только в этой копии: ни в патч ядра, ни в репозиторий они не попадают.
#   2) client-modules (Loom) против этой копии → dev-ядро-мод в $HOKA_DEV_DIR/mods, модули в $HOKA_DEV_DIR/clientmods;
#   3) сервер ядра (server.jar) и модули clientmods + respack из копии → $HOKA_DEV_DIR/server-dist;
#   4) наши серверные модули ($HOKA_OUR_MODULES) против этой копии → $HOKA_DEV_DIR/server-dist/module.
# Флаги: --skip-server (только 1–2), --skip-ours (без 4).
set -euo pipefail
. "$(dirname "$0")/env.sh"

SKIP_SERVER=0; SKIP_OURS=0
for a in "$@"; do
    case "$a" in
        --skip-server) SKIP_SERVER=1 ;;
        --skip-ours) SKIP_OURS=1 ;;
        *) echo "неизвестный флаг $a" >&2; exit 2 ;;
    esac
done

[[ -f "$HOKA_CORE_DIR/settings.gradle.kts" && -d "$HOKA_CORE_DIR/client/core" ]] || {
    echo "в HOKA_CORE_DIR=$HOKA_CORE_DIR нет клона ядра HokaOdyssey с client/core" >&2; exit 1; }

"$DEVCLIENT_DIR/gen-devkey.sh"
PUB="$(cat "$HOKA_DEV_DIR/devkey/public.b64")"

SRC="$HOKA_DEV_DIR/core-src"
echo "== копия ядра: $HOKA_CORE_DIR → $SRC"
rm -rf "$SRC"
mkdir -p "$SRC"
(cd "$HOKA_CORE_DIR" && tar --exclude=./build --exclude='*/build' --exclude=.gradle --exclude=.git --exclude=./run -cf - .) \
    | tar -xf - -C "$SRC"

MM="$SRC/client/core/src/main/java/ink/hoka/odyssey/client/ModuleManager.java"
sed -i -E "s#(PUBLIC_KEY = \")[^\"]+#\1$PUB#" "$MM"
grep -q "PUBLIC_KEY = \"$PUB\"" "$MM" || { echo "PUBLIC_KEY в $MM не заменён — ядро изменилось?" >&2; exit 1; }

OS="$SRC/server/src/main/java/ink/hoka/odyssey/server/OdysseyServer.java"
sed -i 's/new Auth\.Online()/new Auth.Offline()/' "$OS"
grep -q 'new Auth.Offline()' "$OS" || { echo "Auth.Online в $OS не найден — ядро изменилось?" >&2; exit 1; }

echo "== client-modules против dev-копии ядра"
gradle_retry "$CLIENT_MODULES" build -Phoka.core.dir="$SRC"
rm -rf "$HOKA_DEV_DIR/mods" "$HOKA_DEV_DIR/clientmods"
mkdir -p "$HOKA_DEV_DIR/mods" "$HOKA_DEV_DIR/clientmods"
cp "$CLIENT_MODULES"/build/core/*.jar "$HOKA_DEV_DIR/mods/"
cp "$CLIENT_MODULES"/build/clientmods/*.jar "$HOKA_DEV_DIR/clientmods/"
echo "dev-ядро: $(ls "$HOKA_DEV_DIR/mods")"
echo "клиентские модули: $(ls "$HOKA_DEV_DIR/clientmods" | tr '\n' ' ')"

[[ $SKIP_SERVER == 1 ]] && exit 0

DIST="$HOKA_DEV_DIR/server-dist"
rm -rf "$DIST"
mkdir -p "$DIST/module"
echo "== сервер ядра + clientmods + respack"
gradle_retry "$SRC" :server:shadowJar :modules:clientmods:jar :modules:respack:jar
cp "$SRC/server/build/libs/server.jar" "$DIST/"
cp "$SRC"/modules/clientmods/build/libs/clientmods.jar "$DIST/module/"
cp "$SRC"/modules/respack/build/libs/*.jar "$DIST/module/"

if [[ $SKIP_OURS == 0 && -n "$HOKA_OUR_MODULES" ]]; then
    echo "== наши серверные модули: $HOKA_OUR_MODULES"
    tasks=()
    for m in $HOKA_OUR_MODULES; do tasks+=(":$m:jar"); done
    gradle_retry "$HOKA_REPO" "${tasks[@]}" -Phoka.core.dir="$SRC"
    for m in $HOKA_OUR_MODULES; do cp "$HOKA_REPO/$m"/build/libs/*.jar "$DIST/module/"; done
fi
echo "сервер стенда: $(ls "$DIST/module" | tr '\n' ' ')"
