// Система Amazake core-python: системы на Python (GraalPy) внутри сервера.
//   ./gradlew test azs        -> build/libs/core-python.azs  +  build/python-lib/*.jar (библиотеки GraalPy)
//   ./gradlew azs -PamazakeApiJar=путь
//
// Устройство .azs (подробно — README.md, раздел «Как это устроено»):
//   * main   — маленький загрузчик (класс системы). Он не знает про GraalPy: находит/скачивает jar-ы GraalPy в
//              systems/core-python/lib/, создаёт для них URLClassLoader и уже в нём запускает «хост».
//   * host   — весь код хоста (Python-контексты, API amazake, команды). Компилируется против polyglot API и кладётся
//              в .azs ресурсами host/**.class.bin: загрузчик системы их сам не определит, их определяет HostLoader,
//              у которого родитель — загрузчик библиотек GraalPy.
//   * python — модуль amazake (amazake.py) — ресурс host/python/.
//   * libs.txt — список jar-ов GraalPy (имя, sha256, путь в Maven Central): по нему lib/ проверяется и докачивается.
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    java
}

val systemName = rootProject.name
group = "core.amazake.systems"
version = "1.0.0"

/** Версия GraalPy / Truffle / polyglot (одна на все артефакты). Ветка 25.0.x — для JDK 25. */
val graalVersion = "25.0.5"

val apiUrl = "https://github.com/tearsmilk/amazake/releases/latest/download/amazake-systems-api.jar"
val apiJar: File = (findProperty("amazakeApiJar") as String?)?.let { file(it) }
    ?: layout.projectDirectory.file("libs/amazake-systems-api.jar").asFile.also { jar ->
        if (!jar.exists()) {
            jar.parentFile.mkdirs()
            logger.lifecycle("Скачиваю API систем Amazake: $apiUrl")
            val tmp = File(jar.parentFile, jar.name + ".part")
            uri(apiUrl).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
if (!apiJar.isFile) {
    throw GradleException("Нет jar API систем Amazake: $apiJar. Укажите -PamazakeApiJar=путь или проверьте интернет ($apiUrl)")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

val host: SourceSet = sourceSets.create("host")

/** Библиотеки GraalPy, которые лежат на сервере в systems/core-python/lib/ (в .azs не входят). */
val graalpy: Configuration = configurations.create("graalpy") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(files(apiJar))

    "hostCompileOnly"("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    "hostCompileOnly"(files(apiJar))
    "hostCompileOnly"("org.graalvm.polyglot:polyglot:$graalVersion")

    // python-community — сам GraalPy + стандартная библиотека Python; truffle-runtime — оптимизирующий рантайм
    // Truffle: без JVMCI (обычный JDK без флагов) он сам откатывается в режим интерпретатора.
    graalpy("org.graalvm.polyglot:python-community:$graalVersion")
    graalpy("org.graalvm.truffle:truffle-runtime:$graalVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(files(apiJar))
    testImplementation(host.output)
    testImplementation("org.graalvm.polyglot:polyglot:$graalVersion")
    testRuntimeOnly("org.graalvm.polyglot:python-community:$graalVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial,-options", "-parameters"))
}

tasks.processResources {
    val props = mapOf("name" to systemName, "version" to project.version.toString())
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("system.yml") {
        expand(props)
    }
}

/** build/python-lib/: jar-ы GraalPy + libs.txt (имя, размер, sha256, путь в Maven Central). */
val pythonLibs = tasks.register("pythonLibs") {
    group = "build"
    description = "Собирает jar-ы GraalPy в build/python-lib и пишет libs.txt"
    val out = layout.buildDirectory.dir("python-lib")
    inputs.files(graalpy)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        val lines = mutableListOf("# jar-ы GraalPy $graalVersion для systems/core-python/lib/: имя размер sha256 путь-в-maven-central")
        graalpy.resolvedConfiguration.resolvedArtifacts.filter { it.extension == "jar" }.sortedBy { it.file.name }.forEach { a ->
            val id = a.moduleVersion.id
            val f = a.file
            f.copyTo(File(dir, f.name), overwrite = true)
            val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            val classifier = a.classifier?.let { "-$it" } ?: ""
            val path = "${id.group.replace('.', '/')}/${id.name}/${id.version}/${id.name}-${id.version}$classifier.${a.extension}"
            lines += "${f.name} ${f.length()} $sha $path"
        }
        File(dir, "libs.txt").writeText(lines.joinToString("\n") + "\n")
    }
}

tasks.jar {
    archiveFileName.set("$systemName.azs")
    dependsOn(pythonLibs)
    // Классы хоста — ресурсами .bin: их определяет HostLoader (родитель — загрузчик библиотек GraalPy).
    from(host.output.classesDirs) {
        into("host")
        rename { "$it.bin" }
    }
    from(tasks.named("processHostResources")) {
        into("host")
    }
    from(pythonLibs.map { File(it.outputs.files.singleFile, "libs.txt") })
    // Python-системы по умолчанию: распаковываются в systems/core-python/scripts/ при первом включении.
    from("scripts") {
        into("default-scripts")
        exclude("**/__pycache__/**")
    }
}

tasks.register("azs") {
    group = "build"
    description = "Собирает build/libs/<Имя>.azs и build/python-lib/"
    dependsOn(tasks.jar)
}

tasks.register<Delete>("refreshSystemsApi") {
    group = "build setup"
    description = "Удаляет скачанный jar API систем: при следующей сборке скачается свежий"
    delete("libs/amazake-systems-api.jar")
}

tasks.test {
    useJUnitPlatform()
    // Тесты поднимают настоящий GraalPy (режим интерпретатора); кэш ресурсов — в build/.
    systemProperty("polyglot.engine.WarnInterpreterOnly", "false")
    systemProperty("polyglot.engine.userResourceCache", layout.buildDirectory.dir("graal-cache").get().asFile.absolutePath)
    systemProperty("corepython.scripts", file("scripts").absolutePath)
    maxHeapSize = "1g"
}
