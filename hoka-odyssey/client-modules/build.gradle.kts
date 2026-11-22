// Клиентские мини-модули Hoka Odyssey (см. settings.gradle.kts). Сборка: ./gradlew build --max-workers=1
//   build/clientmods/<id>.jar — модули (класть на сервер в module/clientmods/jars/, затем /hoclient reload);
//   build/core/hoka-odyssey-<версия>.jar — ядро-мод, собранное из той же папки ядра (для стенда tools/devclient).
// Версии — как в апстриме (client/build.gradle.kts): MC 1.21.11, Fabric Loader 0.19.5, Fabric API 0.141.6.
plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.19.1" apply false
}

val mc = "1.21.11"

subprojects {
    apply(plugin = "net.fabricmc.fabric-loom-remap")
    repositories { mavenCentral() }

    dependencies {
        "minecraft"("com.mojang:minecraft:$mc")
        "mappings"(project.extensions.getByType<net.fabricmc.loom.api.LoomGradleExtensionAPI>().officialMojangMappings())
        "modImplementation"("net.fabricmc:fabric-loader:0.19.5")
        "modImplementation"("net.fabricmc.fabric-api:fabric-api:0.141.6+1.21.11")
    }

    // shade: библиотеки, которые нужно вшить в jar модуля (например общая физика spacephys). Загрузчики классов модулей
    // изолированы и вложенные jar (Fabric include) не читают — классы библиотеки кладутся прямо в jar модуля.
    // В модуле: dependencies { "shade"(project(":spacephys")) } или "shade"("группа:имя:версия").
    val shade = configurations.create("shade") { isTransitive = true }
    configurations.named("implementation") { extendsFrom(shade) }
    tasks.named<Jar>("jar") {
        dependsOn(shade)
        from({ shade.map { if (it.isDirectory) it else zipTree(it) } }) {
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF", "module-info.class")
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))       // javac 25, байткод Java 21 (на нём 1.21.11)
    }
    tasks.withType<JavaCompile> {
        options.release.set(21)
        options.encoding = "UTF-8"
    }
}

val clientModules = subprojects.filter { it.path.startsWith(":modules:") || it.path == ":core-hello" }

// API ядра для наших модулей (modules/<id>): исходники ядра (compileOnly(project(":core"))) или готовый jar мода
// (-Phoka.core.jar=..., modCompileOnly(files(...)) — Loom ремапит его из intermediary). В jar модуля ядро НЕ попадает.
val coreJar = gradle.extensions.extraProperties["hokaCoreJar"] as File?
subprojects.filter { it.path.startsWith(":modules:") }.forEach { p ->
    p.dependencies {
        if (coreJar != null) "modCompileOnly"(files(coreJar)) else "compileOnly"(project(":core"))
    }
}

// все клиентские модули — в build/clientmods (оттуда их берёт tools/devclient/run-server.sh)
val collectClientModules by tasks.registering(Sync::class) {
    group = "hoka"
    description = "Собрать клиентские модули в build/clientmods"
    clientModules.forEach { p ->
        dependsOn(p.tasks.named("remapJar"))
        from(p.tasks.named("remapJar"))
    }
    into(layout.buildDirectory.dir("clientmods"))
}

// ядро-мод из той же папки ядра — для dev-клиента (с dev-ключом его собирает tools/devclient/build-devcore.sh).
// При сборке против готового jar (-Phoka.core.jar) ядро не собирается.
val collectCore = if (coreJar == null) tasks.register<Sync>("collectCore") {
    group = "hoka"
    description = "Положить jar ядра-мода в build/core"
    dependsOn(":core:remapJar")
    from(project(":core").tasks.named("remapJar"))
    into(layout.buildDirectory.dir("core"))
} else null

tasks.register("build") {
    group = "build"
    dependsOn(collectClientModules)
    if (collectCore != null) dependsOn(collectCore)
}

tasks.register<Delete>("clean") {
    group = "build"
    delete(layout.buildDirectory)
}
