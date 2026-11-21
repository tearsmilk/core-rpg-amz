// Общие настройки модулей. Сборка: gradle build → respack/build/libs/respack.jar, coreshaders/build/libs/coreshaders.jar.
// Локально с ядром: gradle runServer (папка run/, ядро — server.jar из клона HokaOdyssey, модули — run/module/).
plugins { java }

subprojects {
    apply(plugin = "java")
    group = "ink.hoka.odyssey"
    version = "0.1.0"
    repositories { mavenCentral() }
    extensions.configure<JavaPluginExtension> { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
    tasks.withType<JavaCompile> {
        options.release.set(25)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial,-this-escape", "-parameters"))
    }
    dependencies {
        // API ядра и Minestom уже есть в server.jar — в jar модуля не кладём (из included build ядра)
        "compileOnly"("ink.hoka.odyssey:api:0.1.0")
        "testImplementation"("ink.hoka.odyssey:api:0.1.0")
        "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test> { useJUnitPlatform() }
}

val coreDir = gradle.extensions.extraProperties["hokaCoreDir"] as File
val core = gradle.includedBuild("hoka-core")

// jar модулей → run/module/
val deployModules by tasks.registering(Copy::class) {
    group = "odyssey"
    description = "Собрать модули и положить их в run/module/"
    subprojects.forEach { m ->
        dependsOn("${m.path}:jar")
        from(m.layout.buildDirectory.dir("libs")) { include("*.jar") }
    }
    into(layout.projectDirectory.dir("run/module"))
}

// ядро (server.jar из клона HokaOdyssey) в run/
val deployServer by tasks.registering(Copy::class) {
    group = "odyssey"
    description = "Собрать ядро HokaOdyssey и положить server.jar в run/"
    dependsOn(core.task(":server:shadowJar"))
    from(File(coreDir, "server/build/libs/server.jar"))
    into(layout.projectDirectory.dir("run"))
}

// локальный сервер: порт 25565, ресурспак — http://localhost:<respack http.port>
tasks.register<JavaExec>("runServer") {
    group = "odyssey"
    description = "Запустить ядро локально из папки run/ с модулями этого репозитория"
    dependsOn(deployServer, deployModules)
    workingDir = layout.projectDirectory.dir("run").asFile
    classpath = files(layout.projectDirectory.file("run/server.jar"))
    environment("SERVER_PORT", "25565")
    standardInput = System.`in`
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
