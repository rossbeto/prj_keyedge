// Convention for small Micronaut adapter libraries (e.g. libs/problem-micronaut): compile-time
// processing, no application plugin, Micronaut test support.
plugins {
    id("keyedge.quality")
    id("io.micronaut.library")
}

micronaut {
    version(catalog.v("micronaut"))
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("dev.keyedge.*")
    }
}

dependencies {
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    testRuntimeOnly("ch.qos.logback:logback-classic")
    testRuntimeOnly("org.yaml:snakeyaml")
}

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.addAll(listOf("-Xlint:-rawtypes", "-Xlint:-unchecked")) }
