// spacephys — общая физика космоса (чистая Java, без Minecraft и Minestom).
// Один источник истины для сервера (gravity, Java 25) и клиентского мини-модуля (Fabric, Java 21):
// поэтому компилируем с --release 21 и без зависимостей. В модули библиотека вшивается (shade), см. README.md.
plugins { `java-library` }

tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
tasks.jar { archiveFileName.set("spacephys.jar") }
