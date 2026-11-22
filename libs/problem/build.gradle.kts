// RFC 9457 Problem Details types: the only error format of every keyedge API (Phase 002, D-14).
plugins {
    id("keyedge.quality")
    id("keyedge.pit")
}
keyedgeCoverage { minimum = 0.95 }
dependencies {
    testImplementation(project(":libs:testkit"))
    testImplementation(libs.jqwik)
}
pitest { mutationThreshold = 90 } // Phase 002 baseline 91.7 %, rounded down to 5
