// Mutation testing on core packages (D-65). Thresholds are per module: `pitest { mutationThreshold = N }`.
plugins {
    id("keyedge.java")
    id("info.solidsoft.pitest")
}

pitest {
    pitestVersion = catalog.v("pitest")
    junit5PluginVersion = catalog.v("pitestJunit5")
    targetClasses = listOf("dev.keyedge.*")
    excludedTestClasses = listOf("*ArchitectureTest")
    outputFormats = listOf("HTML", "XML")
    timestampedReports = false
    threads = 4
    jvmArgs = listOf("--enable-preview")
}
