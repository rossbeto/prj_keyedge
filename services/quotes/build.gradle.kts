// The demo app behind the sidecar: the quote use case three ways (Phase 009, D-11/D-12): structured
// concurrency, futures on virtual threads, and a Reactor twin; per-upstream resilience; a
// single-flight token provider. Compiled without preview; RUNS with --enable-preview because it loads
// :libs:fanout-structured (runtime-only, found through ServiceLoader).
plugins {
    id("keyedge.native")
    id("keyedge.pit")
    id("keyedge.jcstress")
}
application {
    mainClass = "dev.keyedge.quotes.Application"
    applicationDefaultJvmArgs = listOf("--enable-preview")
}
keyedgeCoverage { excludes = listOf("**/wiring/**", "**/Application*") }
dependencies {
    implementation(project(":libs:ops"))
    implementation(project(":libs:keyformat"))
    implementation(project(":libs:problem-micronaut"))
    implementation(project(":libs:awstarget"))
    implementation(project(":libs:fanout"))
    runtimeOnly(project(":libs:fanout-structured")) // via ServiceLoader: no --enable-preview at compile time
    implementation(platform(libs.awssdk.bom))
    implementation("software.amazon.awssdk:secretsmanager")
    implementation(platform(libs.resilience4j.bom))
    implementation("io.github.resilience4j:resilience4j-circuitbreaker")
    implementation("io.github.resilience4j:resilience4j-bulkhead")
    implementation("io.github.resilience4j:resilience4j-reactor")
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut.reactor:micronaut-reactor")
    testImplementation(project(":libs:testkit"))
    testImplementation(testFixtures(project(":libs:fanout")))
    testImplementation(libs.jqwik)
    testImplementation("io.micronaut:micronaut-http-client")
}
tasks.withType<Test>().configureEach { jvmArgs("--enable-preview") }
pitest {
    // HttpTokenEndpoint is exercised by QuotesApiTest (an HTTP test outside targetTests): not a PIT target
    targetClasses = listOf("dev.keyedge.quotes.domain.*", "dev.keyedge.quotes.app.*", "dev.keyedge.quotes.adapters.resilience.*", "dev.keyedge.quotes.adapters.auth.SingleFlightTokenProvider*")
    targetTests = listOf("dev.keyedge.quotes.domain.*", "dev.keyedge.quotes.app.*", "dev.keyedge.quotes.adapters.*")
    jvmArgs = listOf("--enable-preview")
    mutationThreshold = 75 // 79 % on 2026-11-23
}
