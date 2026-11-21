#!/bin/bash
# Живая проверка модулей ботом: собирает ядро и модули, поднимает тестовый сервер в tools/e2e/srv/ на порту 25599
# (HTTP пака — 25574) и гоняет test.js. Ядро — server.jar из клона HokaOdyssey, но вход офлайн: для теста
# компилируется копия OdysseyServer с Auth.Offline и кладётся в classpath перед server.jar (в сборку не попадает).
# Нужно: JDK 25, Node 22, python3 с Pillow (тестовая картинка для /hocs import) и модуль respack (Takuyamo) —
# его jar не в этом репозитории: RESPACK_JAR=/путь/к/respack.jar tools/e2e/run.sh (например, взять с сервера).
set -e
[ -f "$RESPACK_JAR" ] || { echo "укажите RESPACK_JAR=/путь/к/respack.jar (модуль respack с сервера)"; exit 2; }
E=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$E/../.." && pwd); S=$E/srv; H=$E/.harness
JAVA=${JAVA25_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}/bin   # JDK 25 (не JAVA_HOME: там может быть другой)
(cd "$ROOT" && ./gradlew -q deployServer deployModules)
mkdir -p "$H"
sed 's/new Auth.Online()/new Auth.Offline()/' "$ROOT/.hoka-core/server/src/main/java/ink/hoka/odyssey/server/OdysseyServer.java" > "$H/OdysseyServer.java"
"$JAVA/javac" -nowarn -d "$H/classes" -cp "$ROOT/run/server.jar" "$H/OdysseyServer.java"
rm -rf "$S"; mkdir -p "$S/module/coreshaders/images"
cp "$ROOT/run/server.jar" "$S/"; cp "$ROOT/run/module/coreshaders.jar" "$S/module/"; cp "$RESPACK_JAR" "$S/module/respack.jar"
mkdir -p "$S/module/respack"
printf 'http:\n  port: 25574\n  public-url: "http://127.0.0.1:25574"\npack:\n  send-on-join: true\n  required: false\n' > "$S/module/respack/config.yml"
echo '[{"uuid":"","name":"csbot"}]' > "$S/ops.json"
python3 -c "
from PIL import Image
im=Image.new('RGB',(800,400)); px=im.load()
for x in range(800):
  for y in range(400): px[x,y]=(x*255//800,y*255//400,128)
im.save('$S/module/coreshaders/images/testpic.png')"
cd "$S"; mkfifo console.fifo
(tail -f console.fifo | SERVER_PORT=25599 "$JAVA/java" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$H/classes:server.jar" ink.hoka.odyssey.server.OdysseyServer > server.log 2>&1 &)
for i in $(seq 1 60); do grep -q "Done (" server.log 2>/dev/null && break; sleep 0.5; done
cd "$E"; [ -d node_modules ] || npm install --silent
set +e; SRV="$S" node test.js; code=$?
echo stop > "$S/console.fifo"; sleep 3; pkill -f "tail -f console.fifo"
grep -a " WARN \| ERROR \|Exception" "$S/server.log" | grep -v "WARNING:" && echo "^ в логе сервера есть предупреждения/ошибки"
exit $code
