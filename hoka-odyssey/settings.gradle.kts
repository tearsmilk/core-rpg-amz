// Модули Hoka Odyssey из этого репозитория. Ядро и API живут в отдельном репозитории SakuraMesd/HokaOdyssey —
// здесь мы их только читаем: сборка подключает ядро как included build (API берётся из его проекта :api).
//
// Где взять ядро (по порядку):
//   1) -Phoka.core.dir=/путь/к/HokaOdyssey  или переменная окружения HOKA_CORE_DIR — готовый клон;
//   2) иначе — свой клон в .hoka-core/ (делается сам при первой сборке, git clone --depth 1).
// Обновить клон до свежего main: gradle build -Phoka.core.update=true  (или удалить папку .hoka-core/).
// Ветка/тег ядра: -Phoka.core.ref=main

rootProject.name = "hoka-odyssey-modules"

val coreRepo = "https://github.com/SakuraMesd/HokaOdyssey"
val coreRef = providers.gradleProperty("hoka.core.ref").getOrElse("main")
val coreDir: File = (providers.gradleProperty("hoka.core.dir").orNull ?: System.getenv("HOKA_CORE_DIR"))
    ?.let { File(it) } ?: File(settingsDir, ".hoka-core")

fun git(dir: File, vararg args: String) {
    val p = ProcessBuilder(listOf("git") + args).directory(dir).inheritIO().start()
    if (p.waitFor() != 0) throw GradleException("git ${args.joinToString(" ")} — ошибка (код ${p.exitValue()})")
}

val ownClone = coreDir == File(settingsDir, ".hoka-core")
if (!File(coreDir, "settings.gradle.kts").isFile) {
    if (!ownClone) throw GradleException("в $coreDir нет ядра Hoka Odyssey (settings.gradle.kts)")
    coreDir.deleteRecursively()
    logger.lifecycle("Ядро Hoka Odyssey: клонирую $coreRepo ($coreRef) в $coreDir")
    git(settingsDir, "clone", "--depth", "1", "--branch", coreRef, coreRepo, coreDir.path)
} else if (ownClone && providers.gradleProperty("hoka.core.update").orNull == "true") {
    logger.lifecycle("Ядро Hoka Odyssey: обновляю $coreDir до $coreRef")
    git(coreDir, "fetch", "--depth", "1", "origin", coreRef)
    git(coreDir, "checkout", "--force", "FETCH_HEAD")
}

gradle.extensions.extraProperties["hokaCoreDir"] = coreDir   // для runServer

// ink.hoka.odyssey:api в модулях заменяется проектом :api ядра
includeBuild(coreDir) { name = "hoka-core" }

include("respack-api")   // интерфейс ResourcePackApi модуля respack (Takuyamo) — только для компиляции
include("coreshaders")   // «окна в другой мир»: панорамы-шейдеры на ItemDisplay (depends: respack)
include("spacesky")      // космическое небо: шейдер неба в ресурспаке (depends: respack)
include("gravity")       // невесомость/вакуум на выбранных картах (/hograv)
