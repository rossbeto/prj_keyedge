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
    implementation(platform(libs.resilience4j.bom))
    implementation("io.github.resilience4j:resilience4j-circuitbreaker")
    implementation("io.github.resilience4j:resilience4j-bulkhead")
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut.reactor:micronaut-reactor")
    testImplementation(project(":libs:testkit"))
    testImplementation(libs.jqwik)
}
pitest {
    targetClasses = listOf("dev.keyedge.edge.domain.*", "dev.keyedge.edge.app.*", "dev.keyedge.edge.adapters.cache.*")
    // targetTests defaults to the targetClasses globs, which missed dev.keyedge.edge.adapters.*Test (B-13).
    targetTests = listOf("dev.keyedge.edge.domain.*", "dev.keyedge.edge.app.*", "dev.keyedge.edge.adapters.*")
    mutationThreshold = 75 // 84 % on 2026-11-23
}
