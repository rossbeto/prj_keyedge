// Convention for Micronaut 5 services (D-02): Netty runtime, JUnit 5, compile-time processing.
plugins {
    id("keyedge.quality")
    id("io.micronaut.application")
    id("org.cyclonedx.bom") // SBOM of the runtime classpath: ./gradlew :services:<svc>:cyclonedxDirectBom (Phase 010)
}

micronaut {
    version(catalog.v("micronaut"))
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("dev.keyedge.*")
    }
}

dependencies {
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut.validation:micronaut-validation")
    // Phase 011: traces (OTLP, off unless OTEL_TRACES_EXPORTER=otlp), metrics (Prometheus at /prometheus)
    implementation(project(":libs:observe")) // JSON log lines with trace/request fields, redaction
    implementation("io.micronaut.tracing:micronaut-tracing-opentelemetry-http")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.micronaut.micrometer:micronaut-micrometer-core")
    implementation("io.micronaut.micrometer:micronaut-micrometer-registry-prometheus")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")
}

// Micronaut's generated serde/bean classes use raw types; javac cannot exclude generated files from -Xlint.
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.addAll(listOf("-Xlint:-rawtypes", "-Xlint:-unchecked", "-Xlint:-cast", "-Xlint:-classfile")) }

// Native builds use a GraalVM 25 toolchain provisioned by foojay (D-50); nothing installed on the host.
graalvmNative {
    toolchainDetection = false
    binaries.configureEach {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(catalog.v("java").toInt())
            vendor = JvmVendorSpec.GRAAL_VM
            nativeImageCapable = true
        }
    }
}
