plugins { `kotlin-dsl` }

dependencies {
    implementation(libs.plugin.spotless)
    implementation(libs.plugin.errorprone)
    implementation(libs.plugin.pitest)
    implementation(libs.plugin.jmh)
    implementation(libs.plugin.micronaut)
}
