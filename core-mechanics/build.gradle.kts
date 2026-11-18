// Система Amazake core-mechanics: рэгдоллы на Jolt + куклы-статуи.
//   ./gradlew test azs                      -> build/libs/core-mechanics.azs  +  build/jolt-lib/ (jar-ы jolt-jni + libs.txt)
//   ./gradlew azs -PbundleJolt=false        -> .azs без jar-ов Jolt внутри (тогда система скачает их из Maven Central)
//   ./gradlew azs -PamazakeApiJar=путь      -> собрать против своего jar API систем
//
// Устройство .azs (подробно — README.md, «Как загружается Jolt»):
//   * main   — код системы (Bukkit): команды, события, дисплеи, статуи, скины, мешер секций, поток физики.
//              Про классы Jolt он не знает — говорит с физикой через интерфейсы physics.PhysicsHost/PhysicsWorld/RagdollBody.
//   * host   — код, который трогает Jolt (com.github.stephengold.joltjni). Компилируется против jolt-jni и кладётся в
//              .azs ресурсами host/**.class.bin: их определяет HostLoader (новый на каждое включение), который классы
//              Jolt берёт из ОБЩЕГО на JVM загрузчика (System.getProperties()), а всё остальное — из загрузчика .azs.
//   * libs.txt — jar-ы jolt-jni (имя, размер, sha256, путь в Maven Central); bundled-lib/ — сами jar-ы (если bundleJolt).
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    java
}

val systemName = rootProject.name
group = "core.amazake.systems"
version = "1.0.0"

/** Версия jolt-jni (Java-API + нативка linux x86-64, одинарная точность, Release). */
val joltVersion = "6.1.1"
val bundleJolt = (findProperty("bundleJolt") as String?)?.toBoolean() ?: true

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
    // Paper первым: снапшот-метаданные paper-api Maven Central не отдаёт и часто отвечает 429.
    maven("https://repo.papermc.io/repository/maven-public/")
    // Зеркало Maven Central от Google — только для jolt-jni (repo1 часто отвечает 429 на большие jar-ы).
    maven("https://maven-central.storage-download.googleapis.com/maven2/") {
        content { includeGroup("com.github.stephengold") }
    }
    mavenCentral()
}

val host: SourceSet = sourceSets.create("host")

/** jar-ы jolt-jni, которые лежат на сервере в systems/core-mechanics/lib/. */
val jolt: Configuration = configurations.create("jolt") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    // И Paper API, и API систем есть на сервере: в .azs они не кладутся (compileOnly).
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(files(apiJar))

    // Хост видит классы main (интерфейсы physics.*), Paper API (joml) и jolt-jni.
    "hostCompileOnly"(sourceSets.main.get().output)
    "hostCompileOnly"("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    "hostCompileOnly"("com.github.stephengold:jolt-jni-Linux64:$joltVersion")

    jolt("com.github.stephengold:jolt-jni-Linux64:$joltVersion")
    jolt("com.github.stephengold:jolt-jni-Linux64:$joltVersion:ReleaseSp")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Тесты чистой логики (без сервера): Paper API нужен и им.
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(files(apiJar))
    // Тест хоста гоняет настоящий Jolt (нативка linux x86-64) — только на Linux x86-64.
    testImplementation(host.output)
    testImplementation("com.github.stephengold:jolt-jni-Linux64:$joltVersion")
    testRuntimeOnly("com.github.stephengold:jolt-jni-Linux64:$joltVersion:ReleaseSp")
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

/** build/jolt-lib/: jar-ы jolt-jni + libs.txt (имя, размер, sha256, путь в Maven Central). */
val joltLibs = tasks.register("joltLibs") {
    group = "build"
    description = "Собирает jar-ы jolt-jni в build/jolt-lib и пишет libs.txt"
    val out = layout.buildDirectory.dir("jolt-lib")
    inputs.files(jolt)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        val lines = mutableListOf("# jar-ы jolt-jni $joltVersion для systems/core-mechanics/lib/: имя размер sha256 путь-в-maven-central")
        jolt.resolvedConfiguration.resolvedArtifacts.filter { it.extension == "jar" }.sortedBy { it.file.name }.forEach { a ->
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
    dependsOn(joltLibs)
    // Классы хоста — ресурсами .bin: их определяет HostLoader (классы Jolt он берёт из общего загрузчика).
    from(host.output.classesDirs) {
        into("host")
        rename { "$it.bin" }
    }
    from(joltLibs.map { File(it.outputs.files.singleFile, "libs.txt") })
    if (bundleJolt) {
        // jar-ы Jolt внутри .azs (~2,7 МБ): при первом включении распаковываются в lib/ (сверка sha256).
        from(joltLibs.map { it.outputs.files.singleFile }) {
            into("bundled-lib")
            include("*.jar")
        }
    }
}

tasks.register("azs") {
    group = "build"
    description = "Собирает build/libs/<Имя>.azs и build/jolt-lib/"
    dependsOn(tasks.jar)
}

tasks.register<Delete>("refreshSystemsApi") {
    group = "build setup"
    description = "Удаляет скачанный jar API систем: при следующей сборке скачается свежий (запускайте отдельно: ./gradlew refreshSystemsApi)"
    delete("libs/amazake-systems-api.jar")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
