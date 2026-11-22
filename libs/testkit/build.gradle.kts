// Shared test support: ArchUnit rules, AWS emulator containers, the parity annotation (masterplan §3.3.5).
plugins { id("keyedge.quality") }
dependencies {
    api(libs.archunit.junit5)
    api(project(":libs:awstarget"))
    api(platform(libs.testcontainers.bom))
    api(libs.testcontainers)
    api(libs.floci.testcontainers)
    api(platform(libs.junit.bom))
    api("org.junit.jupiter:junit-jupiter-api")
    implementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation(platform(libs.awssdk.bom))
    testImplementation("software.amazon.awssdk:sts")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
// Containers are exercised by every module's testAws run, not by testkit's fast tests.
keyedgeCoverage { excludes = listOf("**/testkit/aws/**") }
