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
        filter.isFailOnNoMatchingTests = false // `make test-aws TARGET=…` filters by class across all modules
        shouldRunAfter(tasks.named("test"))
    }
}
tasks.withType<Test>().configureEach {
    systemProperty("junit.jupiter.execution.timeout.default", "60s")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
tasks.named<JacocoReport>("jacocoTestReport") { reports { xml.required = true; html.required = true } }

// Coverage gate (DoD 2): 85 % lines by default; libraries and core packages raise it with
// `keyedgeCoverage { minimum = 0.95 }`. Wired into `check`.
abstract class KeyedgeCoverageExtension {
    abstract val minimum: Property<Double>
    /** Ant-style class patterns left out of the gate (demos, pitfalls, code exercised by other modules). */
    abstract val excludes: ListProperty<String>
}
val keyedgeCoverage = extensions.create<KeyedgeCoverageExtension>("keyedgeCoverage").apply {
    minimum.convention(0.85)
    excludes.convention(emptyList())
}
val coverageGate = tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    classDirectories.setFrom(
        sourceSets["main"].output.classesDirs.asFileTree.matching { exclude(keyedgeCoverage.excludes.get()) },
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = keyedgeCoverage.minimum.map { it.toBigDecimal() }.get()
            }
        }
    }
}
tasks.named("check") { dependsOn(coverageGate) }
