// Seeded, scriptable flaky upstream (pricing, stock, fx, oauth) with independent counters (Phase 006).
// Independence: depends on no other service and not on libs/awstarget (masterplan §3.3.1).
plugins { id("keyedge.micronaut-service") }
application { mainClass = "dev.keyedge.sim.Application" }
keyedgeCoverage { excludes = listOf("**/wiring/**", "**/Application*") }
dependencies {
    runtimeOnly(project(":libs:ops"))
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("org.yaml:snakeyaml")
    testImplementation(project(":libs:testkit"))
    testImplementation("io.micronaut:micronaut-http-client")
}
