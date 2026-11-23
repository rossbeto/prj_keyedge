// The AWS footprint as a CDK v2 app in Java (Phase 012, D-40..D-47). Names, sizes and nag reasons come from
// infra/spec/infra-spec.yaml (shared with infra/python). Synth starts a Node process (jsii): kept out of
// `make test`; `make cdk-test` runs these tests.
//   make cdk-synth [MODE=local|prodlike] -> infra/out/java/<mode>
plugins {
    id("keyedge.quality")
    application
}
application { mainClass = "dev.keyedge.infra.InfraApp" }
keyedgeCoverage { minimum = 0.80 }
dependencies {
    implementation(libs.cdk.lib)
    implementation(libs.cdk.nag)
    implementation(libs.constructs)
    implementation(libs.snakeyaml)
    testImplementation(project(":libs:testkit"))
    testImplementation(project(":libs:awstarget")) // ServiceContract key constants
}
val mode = providers.gradleProperty("mode").orElse("local")
// The CDK CLI (cdk.json) and scripts/cdk-synth.sh set CDK_OUTDIR/CDK_CONTEXT_JSON; -Pmode is the fallback.
val outdir = providers.environmentVariable("CDK_OUTDIR")
    .orElse(mode.map { rootDir.resolve("infra/out/java/$it").absolutePath })
val context = providers.environmentVariable("CDK_CONTEXT_JSON")
    .orElse(mode.map { """{"mode":"$it","env":"$it"}""" })
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    environment("CDK_OUTDIR", outdir.get())
    environment("CDK_CONTEXT_JSON", context.get())
}
// excluded from `make test` (jsii starts Node): run by `make cdk-test` (-Pcdk) or by naming the task
val runCdkTests = providers.gradleProperty("cdk").isPresent ||
    gradle.startParameter.taskNames.any { it.contains(":infra:java") }
tasks.withType<Test>().configureEach {
    workingDir = rootDir // the spec path is relative to the repository root
    enabled = runCdkTests
    systemProperty("updateSnapshots", providers.gradleProperty("updateSnapshots").getOrElse("false"))
    maxHeapSize = "1g"
}
