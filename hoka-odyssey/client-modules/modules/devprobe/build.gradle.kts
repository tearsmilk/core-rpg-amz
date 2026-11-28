// Клиентский модуль «devprobe» — зонд стенда tools/devclient: строка в логе при загрузке, строка на HUD
// и «скриптовый» режим (нажатия клавиш, поворот, команды, снимки) без xdotool. Игрокам не раздаётся.
// API ядра (ink.hoka.odyssey.client.api) подключает корневой build.gradle.kts — в jar модуля оно НЕ попадает

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("devprobe.jar")
}
