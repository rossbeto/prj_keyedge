// The control plane: tenants' API keys (issue, rotate, revoke, list, usage) and the sidecars' verify (Phase 005).
plugins {
    id("keyedge.native")
    id("keyedge.pit")
}
application { mainClass = "dev.keyedge.keys.Application" }
// Incremental annotation processing drops Javadoc descriptions from the generated OpenAPI document,
// which made the contract snapshot depend on how the last build ran: full processing only.
micronaut { processing { incremental(false) } }
tasks.named<JavaCompile>("compileJava") { options.isIncremental = false }
// ./gradlew :services:keys:test -PupdateSnapshots=true rewrites contracts/openapi/keys.yml (review the diff).
tasks.withType<Test>().configureEach {
    systemProperty("keyedge.updateSnapshots", providers.gradleProperty("updateSnapshots").getOrElse("false"))
}
keyedgeCoverage {
    minimum = 0.85
    // Adapters are covered by the contract suites on both emulators (make test-aws), wiring by the API tests.
    excludes = listOf("**/adapters/**", "**/wiring/**", "**/Application*")
}
dependencies {
    implementation(project(":libs:ops"))
    implementation(project(":libs:keyformat"))
    implementation(project(":libs:problem-micronaut"))
    implementation(project(":libs:awstarget"))
    implementation(platform(libs.awssdk.bom))
    implementation("software.amazon.awssdk:dynamodb")
    implementation("software.amazon.awssdk:secretsmanager")
    implementation(libs.caffeine)
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut.security:micronaut-security-jwt")
    compileOnly("io.micronaut.openapi:micronaut-openapi-annotations")
    annotationProcessor("io.micronaut.openapi:micronaut-openapi")
    annotationProcessor("io.micronaut.security:micronaut-security-annotations")
    testImplementation(project(":libs:testkit"))
    testImplementation("io.micronaut:micronaut-http-client")
    testImplementation(libs.jqwik)
    testImplementation("io.micronaut:micronaut-json-core")
}
pitest {
    targetClasses = listOf("dev.keyedge.keys.domain.*", "dev.keyedge.keys.app.*")
    mutationThreshold = 85 // Phase 005 baseline 89.3 %
}
