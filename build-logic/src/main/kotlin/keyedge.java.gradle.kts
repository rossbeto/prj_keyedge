// Base convention for every Java module: Java 25 toolchain, JUnit Platform with tag levels, JaCoCo (D-01, D-60).
plugins {
    `java-library`
    jacoco
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(catalog.v("java").toInt()) }
}

dependencies {
    compileOnly(catalog.lib("jspecify"))
    testCompileOnly(catalog.lib("jspecify"))
    testImplementation(platform(catalog.lib("junit-bom")))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(catalog.lib("assertj"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = catalog.v("java").toInt()
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial,-try", "-parameters"))
}

jacoco { toolVersion = catalog.v("jacoco") }

// Test levels (masterplan §11): the default `test` task runs untagged tests only.
val levels = listOf("aws", "it", "e2e", "experiment", "slow")
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags(*levels.toTypedArray()) }
    finalizedBy(tasks.named("jacocoTestReport"))
}
levels.forEach { level ->
    tasks.register<Test>("test${level.replaceFirstChar(Char::uppercase)}") {
        description = "Runs tests tagged '$level'."
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform { includeTags(level) }
        shouldRunAfter(tasks.named("test"))
    }
}
tasks.withType<Test>().configureEach {
    systemProperty("junit.jupiter.execution.timeout.default", "60s")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
tasks.named<JacocoReport>("jacocoTestReport") { reports { xml.required = true; html.required = true } }
