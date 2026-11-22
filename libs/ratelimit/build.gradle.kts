// Lock-free rate limiters and adaptive concurrency limits, proven with jcstress, measured with JMH (Phase 003, D-22).
plugins {
    id("keyedge.quality")
    id("keyedge.pit")
    id("keyedge.jmh")
    id("keyedge.jcstress")
}
keyedgeCoverage {
    minimum = 0.95
    excludes = listOf("**/lab/pitfalls/**")
}
dependencies {
    api(project(":libs:keyformat"))
    implementation(libs.caffeine)
    testImplementation(project(":libs:testkit"))
    testImplementation(libs.jqwik)
    jmh(libs.bucket4j)
}
pitest { mutationThreshold = 80 } // Phase 003 baseline 84.7 % (survivors: removed onSpinWait/unlock calls = equivalent mutants)
