// API-key format, ids, checksum, hashing: pure Java, no framework (Phase 002, D-14, D-62).
plugins {
    id("keyedge.quality")
    id("keyedge.pit")
    application
}
application { mainClass = "dev.keyedge.keyformat.demo.KeyformatDemo" }
keyedgeCoverage {
    minimum = 0.95
    excludes = listOf("**/demo/**")
}
dependencies {
    testImplementation(project(":libs:testkit"))
    testImplementation(project(":libs:problem"))
    testImplementation(libs.jqwik)
}
pitest { mutationThreshold = 85 } // Phase 002 baseline 89.6 %, rounded down to 5
