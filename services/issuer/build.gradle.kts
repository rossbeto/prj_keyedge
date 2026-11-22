// A tiny local OAuth-style issuer for partner JWTs (tenant + scope claims). Local demos and tests only (D-24).
plugins { id("keyedge.micronaut-service") }
application { mainClass = "dev.keyedge.issuer.Application" }
keyedgeCoverage { excludes = listOf("**/wiring/**", "**/Application*") }
dependencies {
    runtimeOnly(project(":libs:ops"))
    implementation(project(":libs:keyformat"))
    implementation(project(":libs:problem-micronaut"))
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-management")
    implementation("com.nimbusds:nimbus-jose-jwt")
    testImplementation(project(":libs:testkit"))
    testImplementation("io.micronaut:micronaut-http-client")
}
