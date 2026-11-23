// The fan-out port and its non-preview adapter (Phase 009, D-12): run three calls under one deadline,
// one result per call, a failed required call cancels the others. The structured adapter lives in
// :libs:fanout-structured, the only module compiled with --enable-preview.
plugins {
    id("keyedge.quality")
    id("keyedge.pit")
    `java-test-fixtures`
}
keyedgeCoverage { minimum = 0.90 }
dependencies {
    testImplementation(project(":libs:testkit"))
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation("org.junit.jupiter:junit-jupiter")
    testFixturesImplementation(libs.assertj)
    testFixturesCompileOnly(libs.jspecify)
}
pitest { mutationThreshold = 70 }
