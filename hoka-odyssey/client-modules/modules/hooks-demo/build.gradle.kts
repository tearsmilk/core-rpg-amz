// Клиентский модуль «hooks-demo» — проверка хуков патча ядра (WP0): сплошное небо (onSky), туман того же цвета
// (onFog), повёрнутые на бок чужие аватары (onAvatarRotations), смена мира (onWorld). Игрокам не раздаётся.
// Собирается только против патченого ядра (файл requires-core-hooks рядом).
dependencies {
    compileOnly(project(":core"))
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("hooks-demo.jar")
}
