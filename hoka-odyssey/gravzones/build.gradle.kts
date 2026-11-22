// Модуль gravzones: зоны гравитации, выделяемые топориком (depends: [gravity] — вакуум берётся у его VacuumService).
dependencies {
    compileOnly(project(":gravity"))          // VacuumService — класс берётся из gravity.jar на сервере
    testImplementation(project(":gravity"))
}

tasks.jar { archiveFileName.set("gravzones.jar") }
