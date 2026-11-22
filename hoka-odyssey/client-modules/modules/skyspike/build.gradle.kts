// Клиентский модуль «skyspike» — проверка (спайк) пути неба без патча ядра: полноэкранный проход своим шейдером
// в WorldRenderEvents.START_MAIN, до отрисовки террейна. Игрокам не раздаётся.
// API ядра (ink.hoka.odyssey.client.api) подключает корневой build.gradle.kts — в jar модуля оно НЕ попадает

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("skyspike.jar")
}
