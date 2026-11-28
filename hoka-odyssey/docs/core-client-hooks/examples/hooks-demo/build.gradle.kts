// Клиентский модуль «hooks-demo» — проверка крючков ядра 0.10 (небо, туман, тела игроков, поза, ввод).
dependencies {
    compileOnly(project(":core"))      // API ядра (ink.hoka.odyssey.client.api) — в jar модуля НЕ попадает
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("hooks-demo.jar")
}
