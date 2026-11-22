// Клиентский модуль «devprobe» — зонд стенда tools/devclient: строка в логе при загрузке, строка на HUD
// и «скриптовый» режим (нажатия клавиш, поворот, команды, снимки) без xdotool. Игрокам не раздаётся.
dependencies {
    compileOnly(project(":core"))      // API ядра (ink.hoka.odyssey.client.api) — в jar модуля НЕ попадает
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("devprobe.jar")
}
