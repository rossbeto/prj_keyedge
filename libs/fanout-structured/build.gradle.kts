// The structured-concurrency fan-out (StructuredTaskScope is a preview API in Java 25, JEP 505).
// The ONLY module compiled with --enable-preview; anything that loads it must run with it too.
plugins {
    id("keyedge.quality")
}
keyedgeCoverage { minimum = 0.90 }
dependencies {
    api(project(":libs:fanout"))
    testImplementation(testFixtures(project(":libs:fanout")))
    testImplementation(project(":libs:testkit"))
}
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.addAll(listOf("--enable-preview", "-Xlint:-preview")) }
tasks.withType<Test>().configureEach { jvmArgs("--enable-preview") }
