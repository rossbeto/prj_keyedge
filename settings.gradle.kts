pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories { mavenCentral() }
}

rootProject.name = "keyedge"

include("libs:testkit")
include("libs:keyformat")
include("libs:problem")
include("libs:problem-micronaut")
include("libs:ratelimit")
include("libs:awstarget")
include("libs:ops")
include("services:keys")
include("services:issuer")
include("services:upstream-sim")
include("services:edge")
include("services:quotes")
