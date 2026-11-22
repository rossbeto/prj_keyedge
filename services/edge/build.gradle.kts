// The sidecar: authenticate, scope-check and rate-limit every request, then stream it to the app (Phase 007).
plugins {
    id("keyedge.micronaut-service")
    id("keyedge.pit")
}
application { mainClass = "dev.keyedge.edge.Application" }
keyedgeCoverage {
    minimum = 0.85
    excludes = listOf("**/wiring/**", "**/Application*", "**/adapters/secrets/**")
}
dependencies {
    runtimeOnly(project(":libs:ops"))
    implementation(project(":libs:keyformat"))
    implementation(project(":libs:ratelimit"))
    implementation(project(":libs:problem-micronaut"))
    implementation(project(":libs:awstarget"))
    implementation(platform(libs.awssdk.bom))
    implementation("software.amazon.awssdk:secretsmanager")
    implementation(libs.caffeine)
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut.reactor:micronaut-reactor")
    testImplementation(project(":libs:testkit"))
}
pitest {
    targetClasses = listOf("dev.keyedge.edge.domain.*", "dev.keyedge.edge.app.*", "dev.keyedge.edge.adapters.cache.*")
    // Baseline 60.4 %: PIT reports the verdict-cache tests as not covering it (timing-sensitive concurrent
    // tests excluded under instrumentation; BACKLOG B-13).
    mutationThreshold = 55
}
