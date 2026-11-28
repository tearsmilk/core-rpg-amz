// Модуль guns: огнестрел (АКМ из TACZ) — стрельба, прицел, магазин и перезарядка, отдача в вакууме.
// Классов clientmods/hoitems/hosounds при сборке нет — только рефлексия (softdepends); VacuumService — из gravity.jar
// на сервере (compileOnly, как у gravzones). Ассеты TACZ (модели, текстуры, звуки — CC BY-NC-ND 4.0, см.
// assets-tacz/NOTICE.md) кладутся в jar в папку tacz/: модуль раздаёт их моду сам и ставит в hoitems/hosounds.
dependencies {
    compileOnly(project(":gravity"))
    testImplementation(project(":gravity"))
}

tasks.processResources {
    from("assets-tacz") { into("tacz") }
    from("assets-hoka") { into("hoka") }   // свои ассеты (плазменная винтовка ПВ-7, tools/plasma) — не TACZ
}

tasks.jar { archiveFileName.set("guns.jar") }
