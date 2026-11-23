// Experiment `pinning` (Phase 011): the same program on Java 21 and Java 25 (JEP 491). A plain module (no
// conventions) compiled for release 21 so both JVMs can run it; foojay provisions JDK 21 on first use.
//   ./gradlew :lab:pinning:pinning   (make lab E=pinning)
plugins { java }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
tasks.withType<JavaCompile>().configureEach { options.release = 21 }

val out = layout.buildDirectory.dir("pinning")
listOf(21, 25).forEach { v ->
    tasks.register<JavaExec>("pinningOn$v") {
        group = "lab"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass = "dev.keyedge.lab.pinning.PinningExperiment"
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(v) }
        jvmArgs("-Djdk.virtualThreadScheduler.parallelism=4", "-Djdk.virtualThreadScheduler.maxPoolSize=4")
        val file = out.map { it.file("java$v.json") }
        args(file.get().asFile.absolutePath)
        outputs.upToDateWhen { false }
    }
}
tasks.register("pinning") {
    group = "lab"
    dependsOn("pinningOn21", "pinningOn25")
}
