// Клиентский модуль «spaceflight» — живая невесомость в вакууме (мод 0.17+): инерция без сопротивления, тяготение
// тел, присланных сервером (модуль gravity), тяга WASD/пробел/shift по осям взгляда и свободная камера с креном 360°.
// Нужны хуки камеры мода 0.17 (onMouse/onCamera) — файл requires-camera-api рядом; сборка: -Phoka.core.jar=...
// API ядра подключает корневой build.gradle.kts. Физика — общая библиотека spacephys: её исходники компилируются
// прямо в этот jar (загрузчики классов модулей изолированы, вложенные jar не читаются).
sourceSets.named("main") { java.srcDir("../../../spacephys/src/main/java") }

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("spaceflight.jar")
}
