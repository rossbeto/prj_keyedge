// One switch for every AWS client: floci | aws-emulator | aws (guarded). Masterplan §7.2, D-32, D-33.
plugins {
    id("keyedge.quality")
    id("keyedge.pit")
}
keyedgeCoverage { minimum = 0.90 }
dependencies {
    api(platform(libs.awssdk.bom))
    api("software.amazon.awssdk:aws-core")
    api("software.amazon.awssdk:regions")
    api("software.amazon.awssdk:auth")
    api("software.amazon.awssdk:apache5-client")
    api("software.amazon.awssdk:ssm") // ServiceContract (Phase 012)
    testImplementation(project(":libs:testkit"))
    testImplementation("software.amazon.awssdk:sts")
    testImplementation("software.amazon.awssdk:dynamodb")
    testImplementation("software.amazon.awssdk:secretsmanager")
    testImplementation("software.amazon.awssdk:ssm")
    testImplementation("software.amazon.awssdk:kms")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
pitest { mutationThreshold = 0 }
