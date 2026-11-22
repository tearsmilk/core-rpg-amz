// Клиентские мини-модули Hoka Odyssey из этого репозитория (jar с client-module.json; раздаёт их модуль clientmods).
// Сборка на Loom — как client/ в апстриме SakuraMesd/HokaOdyssey. Ядро-мод в jar модулей НЕ попадает:
// модули компилируются compileOnly против исходников ядра (проект :core берётся прямо из клона ядра).
//
// Где взять ядро (по порядку):
//   1) -Phoka.core.dir=/путь/к/HokaOdyssey  или переменная окружения HOKA_CORE_DIR — корень клона
//      (в нём client/core) или сразу его папка client/ (в ней core/). Так подключается и патченое ядро
//      (docs/core-client-hooks), и обычное ядро из апстрима;
//   2) иначе — общий клон ../.hoka-core (его делает сборка серверных модулей; если в нём нет client/ —
//      обновить: в hoka-odyssey/ запустить gradle build -Phoka.core.update=true).
// В папку ядра сборка ничего не пишет: build/ проектов ядра уезжает в client-modules/build/ext/<проект>.
//
// Модули: каждая папка modules/<id> с build.gradle.kts подключается сама. Модуль, которому нужны хуки патча
// ядра (onSky, onFog, …), кладёт рядом файл requires-core-hooks — без патченого ядра он пропускается.
// Шаблонный модуль ядра hello (client/modules/hello апстрима) тоже собирается — нужен для проверки стенда;
// выключить: -Phoka.core.hello=false.

pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "hoka-odyssey-client-modules"

val coreRoot: File = (providers.gradleProperty("hoka.core.dir").orNull ?: System.getenv("HOKA_CORE_DIR"))
    ?.let { File(it).absoluteFile } ?: File(settingsDir, "../.hoka-core").canonicalFile

// корень клона (client/core) или сама папка client (core/)
val coreClient: File = listOf(File(coreRoot, "client"), coreRoot)
    .firstOrNull { File(it, "core/build.gradle.kts").isFile }
    ?: throw GradleException(
        "в $coreRoot нет клиентского ядра Hoka Odyssey (client/core/build.gradle.kts). " +
            "Укажите клон: -Phoka.core.dir=/путь/к/HokaOdyssey (или HOKA_CORE_DIR); общий клон .hoka-core обновляется " +
            "сборкой серверных модулей: в hoka-odyssey/ — ./gradlew build -Phoka.core.update=true")

// Патч хуков ядра (WP0) наложен? Признак — onSky в API ClientContext.
val contextJava = File(coreClient, "core/src/main/java/ink/hoka/odyssey/client/api/ClientContext.java")
val coreHooks = contextJava.isFile && contextJava.readText().contains("onSky(")
logger.lifecycle("Ядро Hoka Odyssey (клиент): $coreClient" + if (coreHooks) " — с хуками патча" else " — без патча хуков")

gradle.extensions.extraProperties["hokaCoreClient"] = coreClient
gradle.extensions.extraProperties["hokaCoreHooks"] = coreHooks

// ядро-мод: модули компилируются против него (compileOnly(project(":core")) — как в апстриме)
include("core")
project(":core").projectDir = File(coreClient, "core")

// шаблонный модуль ядра hello — для проверки стенда (tools/devclient)
val helloDir = File(coreClient, "modules/hello")
if (providers.gradleProperty("hoka.core.hello").getOrElse("true") != "false" && File(helloDir, "build.gradle.kts").isFile) {
    include("core-hello")
    project(":core-hello").projectDir = helloDir
}

// наши модули: modules/<id>
File(settingsDir, "modules").listFiles()?.sortedBy { it.name }?.forEach { dir ->
    if (!File(dir, "build.gradle.kts").isFile) return@forEach
    if (File(dir, "requires-core-hooks").isFile && !coreHooks) {
        logger.lifecycle("модуль ${dir.name} пропущен: нужен патч хуков ядра (docs/core-client-hooks)")
        return@forEach
    }
    include("modules:${dir.name}")
}

// build/ проектов из папки ядра — к нам, а не в клон ядра (он может быть только для чтения)
gradle.beforeProject {
    if (!projectDir.canonicalPath.startsWith(settingsDir.canonicalPath + File.separator) && projectDir != settingsDir) {
        layout.buildDirectory.set(File(settingsDir, "build/ext/" + name))
    }
}
