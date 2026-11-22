#!/bin/bash
# Dev-ключ подписи клиентских модулей (Ed25519) — только для стенда.
#   $HOKA_DEV_DIR/devkey/signing.key  — закрытый (PKCS#8 DER, base64): уходит в module/clientmods/signing.key сервера стенда
#   $HOKA_DEV_DIR/devkey/public.b64   — открытый (X.509 DER, base64): подставляется в PUBLIC_KEY dev-сборки ядра
# Ключ живёт только в рабочей папке стенда, в репозиторий и в патч ядра не попадает. Повторный запуск ключ не меняет
# (новый: удалить папку devkey).
set -euo pipefail
. "$(dirname "$0")/env.sh"

K="$HOKA_DEV_DIR/devkey"
if [[ -s "$K/signing.key" && -s "$K/public.b64" ]]; then
    echo "dev-ключ уже есть: $K"
    exit 0
fi
mkdir -p "$K"
chmod 700 "$K"
umask 077
openssl genpkey -algorithm ed25519 -outform DER -out "$K/private.der"
base64 -w0 "$K/private.der" > "$K/signing.key"
openssl pkey -inform DER -in "$K/private.der" -pubout -outform DER | base64 -w0 > "$K/public.b64"
rm -f "$K/private.der"
echo "dev-ключ создан: $K (открытый: $(cat "$K/public.b64"))"
