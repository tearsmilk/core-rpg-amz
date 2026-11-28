// Модуль gravity: вакуум/невесомость на выбранных картах. Настоящая физика (инерция, притяжение тел 1/r²) —
// библиотека spacephys, её классы вшиваются прямо в gravity.jar (класслоадеры модулей изолированы).
evaluationDependsOn(":spacephys")
dependencies { "implementation"(project(":spacephys")) }
tasks.jar {
    archiveFileName.set("gravity.jar")
    from(project(":spacephys").the<SourceSetContainer>()["main"].output)
}
