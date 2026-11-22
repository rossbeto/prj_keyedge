// JMH microbenchmarks in src/jmh/java (D-67). Run with `./gradlew :<module>:jmh`.
plugins {
    id("keyedge.java")
    id("me.champeau.jmh")
}

jmh {
    jmhVersion = catalog.v("jmh")
    fork = 2
    warmupIterations = 3
    iterations = 5
    resultFormat = "JSON"
    profilers = listOf("gc")
    jvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
    // make jmh M=libs:ratelimit B=gcra  -> only benchmarks matching the regex
    providers.gradleProperty("jmh.includes").orNull?.let { includes = listOf(it) }
    providers.gradleProperty("jmh.quick").orNull?.let { fork = 1; warmupIterations = 2; iterations = 3 }
}
