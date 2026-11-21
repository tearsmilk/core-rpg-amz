// Модуль coreshaders: панорамы-шейдеры на ItemDisplay. Ресурспак (шейдер, модели, панорамы) — src/main/resources/pack/,
// в общий пак его кладёт модуль respack (depends: [respack]).
dependencies {
    compileOnly(project(":respack-api"))      // ResourcePackApi — класс берётся из respack.jar (Takuyamo) на сервере
    testImplementation(project(":respack-api"))
}

tasks.jar { archiveFileName.set("coreshaders.jar") }
