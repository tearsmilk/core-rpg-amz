# Стенд проверки клиентских модулей (tools/devclient)

Локальная связка «сервер Minestom + Fabric-клиент с модом Hoka Odyssey» без видеокарты и без лицензии:
клиент работает под Xvfb на программном GL (Mesa llvmpipe), сервер пускает без проверки аккаунтов.
Боевой сервер и panel.hoka.ink стенд не трогает: всё на localhost.

## Зачем dev-ключ

Мод загружает клиентские модули, только если их список подписан ключом сервера (Ed25519, открытый ключ вшит
в мод). Закрытого ключа владельцев у нас нет и не должно быть. Поэтому стенд:

1. делает свою пару ключей (`gen-devkey.sh`) в рабочей папке стенда `$HOKA_DEV_DIR/devkey`;
2. собирает **dev-ядро** — копию ядра с нашим открытым ключом (`build-devcore.sh`);
3. кладёт закрытый ключ серверу стенда в `module/clientmods/signing.key`.

Dev-ядро годится только для стенда: с боевым сервером оно модули не загрузит (подпись другая).
В репозиторий, в патч ядра (docs/core-client-hooks) и в jar модулей ключ не попадает.

## Быстрый старт

```bash
export HOKA_DEV_DIR=/tmp/hoka-devclient          # рабочая папка стенда (не в репозитории)
export HOKA_CORE_DIR=/путь/к/HokaOdyssey         # клон ядра (только читается; патченое ядро — тоже сюда)
tools/devclient/fetch-client.sh                   # ванильный клиент 1.21.11 (один раз)
tools/devclient/smoke.sh --keep                   # сборка, сервер, клиент, вход, модули, снимки
```

`smoke.sh` завершится с кодом 0, только если всё дошло: клиент зашёл и загрузил модули (строка
`Hoka Odyssey: модуль hello … загружен` в `$HOKA_DEV_DIR/client.log`), `skyspike` нарисовал кадр (если он раздан),
`hello` показал текст по `/hoclient hello` (строка `показал` в логе сервера), `devprobe` выполнил скрипт и сделал
снимок. Иначе — код 1 и строка `ПРОВАЛ: …`. Снимки — в `$HOKA_DEV_DIR/shots` и `$HOKA_DEV_DIR/game/screenshots`.

## Скрипты

| Скрипт | Что делает |
|---|---|
| `env.sh` | общие настройки (подключается остальными); всё переопределяется переменными окружения |
| `fetch-client.sh` | ванильный клиент: `client.jar`, библиотеки, `classpath.txt`, ассеты (`HOKA_ASSETS=empty\|full`) |
| `gen-devkey.sh` | пара Ed25519 стенда (если её ещё нет) |
| `build-devcore.sh` | копия ядра с dev-ключом и `Auth.Offline` → dev-ядро-мод, клиентские модули (client-modules), server.jar, clientmods, respack, наши серверные модули |
| `run-server.sh` | сервер стенда в фоне на `localhost:$HOKA_PORT`; `--sync-only` — обновить клиентские модули и `/hoclient reload` |
| `run-client.sh` | Xvfb + Fabric-клиент (Knot) с Fabric API и dev-ядром, сразу заходит на сервер; `--no-join` — меню |
| `cmd.sh` | команда в консоль сервера: `cmd.sh hoclient hello Привет` |
| `input.sh` | ввод через xdotool: `focus`, `key`, `hold`, `chat`, `mouse`; `script` — без xdotool, через модуль devprobe |
| `screenshot.sh` | снимок дисплея (`import`/`xwd`) или внутриигровой (`--f2`) |
| `stop.sh` | остановить `client`, `server`, `xvfb` или всё |
| `smoke.sh` | вся проверка разом |

Переменные (по умолчанию): `HOKA_PORT=25601`, `HOKA_RESPACK_PORT=25602`, `HOKA_DISPLAY=:42`, `HOKA_PLAYER=DevBot`,
`HOKA_OUR_MODULES="gravity gravzones oxygen items spacesky coreshaders"`, `MC_CLIENT_DIR=$HOKA_DEV_DIR/mcclient`,
`JAVA21`, `JAVA25_HOME`.

## Цикл разработки клиентского модуля

```bash
cd client-modules && ./gradlew build --max-workers=1 -Phoka.core.dir=$HOKA_DEV_DIR/core-src
cp build/clientmods/*.jar $HOKA_DEV_DIR/clientmods/
tools/devclient/run-server.sh --sync-only         # клиент перезагрузит модули без перезахода
```

## Модули стенда (client-modules/modules)

- `devprobe` — строка в логе при загрузке, строка на HUD, скриптовый ввод (файл
  `$HOKA_DEV_DIR/game/hoka_odyssey/data/devprobe/script.txt`, команды — в javadoc `DevProbeModule`).
- `skyspike` — спайк неба без патча ядра: полноэкранный проход своим шейдером в `WorldRenderEvents.START_MAIN`.
  Кэш шейдеров игры — по id шейдера, поэтому id содержит хэш GLSL: правка шейдера доходит и через `--sync-only`,
  без F3+T.
- `hooks-demo` — проверка хуков патча ядра (onSky, onFog, onAvatarRotations); собирается только против патченого ядра.

Игрокам эти модули не раздаются: их jar кладёт только `run-server.sh` стенда.

## Ограничения

- llvmpipe медленный (единицы–десятки FPS) и не показывает реальное время GPU: бюджет кадра меряйте на своей машине.
- Первый запуск Fabric перекодирует Minecraft в intermediary (кэш в `$HOKA_DEV_DIR/game/.fabric`) — минута-две.
- Maven Central иногда отвечает 429: скрипты собирают с `--max-workers=1` и повторяют до трёх раз.
