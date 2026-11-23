// The bench CLI (Phase 015, D-67): predictions before runs, k6 open-model load in a container, results schema v1,
// lab notebook and charts. Talks HTTP, Docker and files only: never depends on services/* (independence rule).
//   make bench S=<scenario> | bench-smoke | report | docs-check
plugins {
    id("keyedge.quality")
    application
}
application { mainClass = "dev.keyedge.bench.cli.BenchCli" }
// adapters (Docker, k6, files) and the CLI are exercised by bench-smoke, not by unit tests
keyedgeCoverage {
    minimum = 0.85
    excludes = listOf("**/bench/adapters/**", "**/bench/cli/**")
}
dependencies {
    implementation(libs.picocli)
    implementation(libs.jackson3.databind)
    implementation(libs.jackson3.yaml)
    testImplementation(project(":libs:testkit"))
    testImplementation(libs.jqwik)
}
tasks.named<JavaExec>("run") { workingDir = rootDir }
// The start script needs Java 25, not whatever `java` is on PATH: record the toolchain's home for `make bench`.
val javaHomeFile = layout.buildDirectory.file("java-home")
val launcher = javaToolchains.launcherFor { languageVersion = java.toolchain.languageVersion }
val writeJavaHome by tasks.registering {
    val out = javaHomeFile
    val home = launcher.map { it.metadata.installationPath.asFile.absolutePath }
    outputs.file(out)
    doLast { out.get().asFile.writeText(home.get()) }
}
tasks.named("installDist") { finalizedBy(writeJavaHome) }
tasks.withType<Test>().configureEach {
    workingDir = rootDir
    systemProperty("updateGolden", providers.gradleProperty("updateGolden").getOrElse("false"))
}
