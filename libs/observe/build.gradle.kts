// Logs that explain themselves (Phase 011): one JSON line per event with trace and request fields read at
// log time (no MDC put/remove pairs), and API-key secrets redacted before anything is written.
plugins { id("keyedge.micronaut-library") }
keyedgeCoverage { minimum = 0.90 }
dependencies {
    compileOnly("ch.qos.logback:logback-classic")
    implementation("io.opentelemetry:opentelemetry-api")
    testImplementation("ch.qos.logback:logback-classic")
}
