// Клиентский модуль «spaceflight» — живая невесомость в вакууме (мод 0.48+): инерция без сопротивления, тяготение
// тел, присланных сервером (модуль gravity), тяга WASD/пробел/shift по осям взгляда, свободная камера с креном 360°
// (CameraState.rotation) и повёрнутые тела игроков (onEntityPose). Нужны хуки камеры мода — файл requires-camera-api
// рядом; сборка: -Phoka.core.jar=... API ядра подключает корневой build.gradle.kts. Физика — общая библиотека
// spacephys: её исходники компилируются прямо в этот jar (загрузчики классов модулей изолированы, вложенные jar
// не читаются).
sourceSets.named("main") { java.srcDir("../../../spacephys/src/main/java") }

// Тесты чистой математики (BodyFrame, FreeLook) — против JOML игры; без них jar не собирается.
dependencies {
    "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
tasks.named<Test>("test") { useJUnitPlatform() }

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("spaceflight.jar")
    dependsOn(tasks.named("test"))
}
