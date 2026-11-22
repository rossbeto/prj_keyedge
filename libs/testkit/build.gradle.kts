// Shared test support: ArchUnit rules, fakes, Testcontainers helpers (masterplan §3.3.5).
plugins { id("keyedge.quality") }
dependencies {
    api(libs.archunit.junit5)
    testImplementation(libs.archunit.junit5)
}
dependencies { testImplementation("org.junit.jupiter:junit-jupiter-params") }
