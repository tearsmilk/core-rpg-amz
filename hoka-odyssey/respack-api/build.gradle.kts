// Только интерфейс ResourcePackApi модуля respack (автор — Takuyamo), чтобы coreshaders компилировался.
// В jar модулей не попадает: на сервере класс берётся из настоящего module/respack.jar.
dependencies { compileOnly("ink.hoka.odyssey:api:0.1.0") }
tasks.jar { archiveFileName.set("respack-api.jar") }   // не выкладывается: deployModules его пропускает
