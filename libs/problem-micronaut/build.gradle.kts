// The one HTTP mapping of RFC 9457 problems for every Micronaut service (masterplan §3.3.3 rule 9).
plugins { id("keyedge.micronaut-library") }
dependencies {
    api(project(":libs:problem"))
    implementation("io.micronaut:micronaut-http-server")
    testImplementation("io.micronaut:micronaut-http-server-netty")
    testImplementation("io.micronaut:micronaut-http-client")
}
