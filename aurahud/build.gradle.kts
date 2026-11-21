plugins {
    java
}

group = "dev.tearsmilk"
version = "1.0.0"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
    from("pack/assets") {
        into("shaderpack/assets")
    }
    from("pack/stubs") {
        into("shaderpack/stubs")
    }
    from("pack/sprites") {
        into("aurasprites")
    }
}

tasks.jar {
    archiveFileName.set("aurahud-${project.version}.jar")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("aurahud.root", projectDir.absolutePath)
    systemProperty("aurahud.golden", System.getProperty("aurahud.golden", ""))
}
