// Quality gates on every compile (D-64): Error Prone + NullAway (JSpecify @NullMarked), Spotless, Checkstyle (size/naming only).
import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("keyedge.java")
    id("net.ltgt.errorprone")
    id("com.diffplug.spotless")
    checkstyle
}

dependencies {
    errorprone(catalog.lib("errorprone-core"))
    errorprone(catalog.lib("nullaway"))
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        disableWarningsInGeneratedCode = true
        excludedPaths = ".*/build/generated/.*"
        check("NullAway", net.ltgt.gradle.errorprone.CheckSeverity.ERROR)
        option("NullAway:OnlyNullMarked", "true")
        option("NullAway:JSpecifyMode", "true")
        // Tests use fakes and AssertJ chains freely; keep Error Prone on but NullAway off there.
        if (name.contains("Test", ignoreCase = true) || name.contains("Jmh") || name.contains("Jcstress")) {
            check("NullAway", net.ltgt.gradle.errorprone.CheckSeverity.OFF)
        }
    }
}

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat(catalog.v("googleJavaFormat"))
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    toolVersion = catalog.v("checkstyle")
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}
tasks.withType<Checkstyle>().configureEach { exclude("**/generated/**") }
