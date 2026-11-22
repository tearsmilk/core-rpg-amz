// Клиентский модуль «skyspike» — проверка (спайк) пути неба без патча ядра: полноэкранный проход своим шейдером
// в WorldRenderEvents.START_MAIN, до отрисовки террейна. Игрокам не раздаётся.
dependencies {
    compileOnly(project(":core"))
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("skyspike.jar")
}
