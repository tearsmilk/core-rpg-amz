// Клиентский модуль «guns» — огнестрел (пара — серверный модуль guns): ЛКМ — выстрел (onClick, очередь при зажатой),
// ПКМ — прицел (ванильное «использование предмета»; модуль сводит оружие к глазу и сам приближает обзор), R —
// перезарядка, V — режим огня. Своя модель в руке (.bbmodel из TACZ, рисуется в onWorld классами модели ядра
// BbModel/Pose) с руками игрока, поза тела от третьего лица (крючок onModelParts ядра 0.56+, если есть), отдача
// камеры, трассеры, вспышка, HUD патронов. Нужно ядро 0.55+ (onClick, onCamera, hideHand) —
// файл requires-camera-api рядом; сборка: -Phoka.core.jar=...

// Тесты чистой математики (RecoilSpline, AdsBlend) — против JOML игры; без них jar не собирается.
dependencies {
    "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
tasks.named<Test>("test") { useJUnitPlatform() }

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("guns.jar")
    dependsOn(tasks.named("test"))
}
