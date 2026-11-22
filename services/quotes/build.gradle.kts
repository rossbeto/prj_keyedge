// The demo app behind the sidecar. Phase 007: trust boundary (signed principal). Phase 009: the quote use case.
plugins { id("keyedge.micronaut-service") }
application { mainClass = "dev.keyedge.quotes.Application" }
keyedgeCoverage { excludes = listOf("**/wiring/**", "**/Application*") }
dependencies {
    runtimeOnly(project(":libs:ops"))
    implementation(project(":libs:keyformat"))
    implementation(project(":libs:problem-micronaut"))
    implementation(project(":libs:awstarget"))
    implementation(platform(libs.awssdk.bom))
    implementation("software.amazon.awssdk:secretsmanager")
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-management")
    testImplementation(project(":libs:testkit"))
    testImplementation("io.micronaut:micronaut-http-client")
}
