// jcstress concurrency tests in src/jcstress/java (D-63). No maintained Gradle plugin exists for
// recent Gradle, so the harness runs as a JavaExec on the jcstress-core runner.
plugins { id("keyedge.java") }

val jcstress: SourceSet = sourceSets.create("jcstress") {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}

dependencies {
    "jcstressImplementation"(catalog.lib("jcstress-core"))
    "jcstressAnnotationProcessor"(catalog.lib("jcstress-core"))
}

tasks.named<JavaCompile>("compileJcstressJava") { options.compilerArgs.removeAll { it.startsWith("-Xlint") } }

tasks.register<JavaExec>("jcstress") {
    description = "Runs jcstress tests. -Pjcstress.mode=quick|default|tough, -Pjcstress.tests=<regex>."
    group = "verification"
    classpath = jcstress.runtimeClasspath
    mainClass = "org.openjdk.jcstress.Main"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(catalog.v("java").toInt()) }
    val mode = providers.gradleProperty("jcstress.mode").orElse("quick")
    val filter = providers.gradleProperty("jcstress.tests").orElse(".*")
    val out = layout.buildDirectory.dir("reports/jcstress")
    args = listOf("-m", mode.get(), "-t", filter.get(), "-r", out.get().asFile.absolutePath)
    workingDir = layout.buildDirectory.get().asFile
}
