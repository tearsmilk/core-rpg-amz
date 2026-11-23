// Клиентский модуль «starmap» — карта-радар на клавишу M: блоки вокруг (что прогружено у клиента), ты, корабли и
// игроки (данные — серверный модуль starmap), свои точки. Нужны только обычные крючки ядра (onTick, onHud,
// onMessage) — работает и на ядре из исходников, и на jar мода 0.17. API ядра подключает корневой build.gradle.kts.
tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("starmap.jar")
}
