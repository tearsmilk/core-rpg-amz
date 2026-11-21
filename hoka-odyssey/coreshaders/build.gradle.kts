// Модуль coreshaders: панорамы-шейдеры на ItemDisplay. Ресурспак (шейдер, модели, панорамы) — src/main/resources/pack/,
// в общий пак его кладёт модуль respack (depends: [respack]).
dependencies {
    compileOnly(project(":respack"))      // ResourcePackApi — класс берётся из respack.jar на сервере
    testImplementation(project(":respack"))
}

tasks.jar { archiveFileName.set("coreshaders.jar") }
