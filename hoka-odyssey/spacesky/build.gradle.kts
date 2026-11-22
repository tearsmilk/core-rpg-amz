// Модуль spacesky: всё небо обычного мира — космос (шейдер неба в ресурспаке, без текстур и сущностей).
dependencies {
    compileOnly(project(":respack-api"))      // ResourcePackApi — класс берётся из respack.jar (Takuyamo) на сервере
    testImplementation(project(":respack-api"))
    compileOnly(project(":gravity"))          // BlackHoles (чёрные дыры в небе) — из gravity.jar на сервере, softdepends
    testImplementation(project(":gravity"))
}

tasks.jar { archiveFileName.set("spacesky.jar") }
