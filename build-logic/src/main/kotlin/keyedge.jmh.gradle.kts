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
}
