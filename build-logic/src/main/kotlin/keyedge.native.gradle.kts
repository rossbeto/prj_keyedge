// Native images (Phase 010, D-50/D-51): built INSIDE the pinned GraalVM builder container by Micronaut's
// dockerBuildNative (a foojay GraalVM toolchain extracts native-image as an empty file, B-04), so the host
// needs only Docker. Runtime: distroless cc (glibc + libgcc), non-root. One image at a time (memory).
//   ./gradlew --no-configuration-cache :services:<svc>:dockerBuildNative   (make native S=<svc>)
import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
import io.micronaut.gradle.docker.NativeImageDockerfile

plugins { id("keyedge.micronaut-service") }

val builder = "ghcr.io/graalvm/native-image-community:25-ol9@sha256:7d17320ffc98d5aaffdf8c1aae929e659dc440f7a7722eb5ad2cade1ac834af7"
val runtime = "gcr.io/distroless/cc-debian13:nonroot@sha256:532c7a94f9e96ba9db75eb9388f4227751131c7cb0a411d1a8521af4096135de"

graalvmNative {
    binaries.named("main") {
        buildArgs.addAll("--no-fallback", "-march=compatibility", "-H:+ReportExceptionStackTraces", "-J-Xmx5g")
        if (project.findProperty("keyedge.native.preview") == "true") {
            buildArgs.add("--enable-preview")
        }
    }
    metadataRepository { enabled = true }
    // ./gradlew :services:<svc>:test -Pagent then :services:<svc>:metadataCopy: reachability metadata from
    // real test traffic, reviewed and committed under META-INF/native-image/dev.keyedge/<svc>.
    agent {
        defaultMode = "standard"
        metadataCopy {
            inputTaskNames.addAll("test", "testAws")
            outputDirectories.add("src/main/resources/META-INF/native-image/dev.keyedge/${project.name}")
            mergeWithExisting = true
        }
    }
}

tasks.named<NativeImageDockerfile>("dockerfileNative") {
    graalImage = builder
    baseImage(runtime)
    jdkVersion = catalog.v("java")
}

tasks.named<DockerBuildImage>("dockerBuildNative") {
    images = listOf("keyedge/${project.name}:native")
}

// With -Pagent the tests run on the GraalVM JDK (the only one with libnative-image-agent.so).
if (providers.gradleProperty("agent").isPresent) {
    tasks.withType<Test>().configureEach {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(catalog.v("java").toInt())
            vendor = JvmVendorSpec.GRAAL_VM
        }
    }
}
