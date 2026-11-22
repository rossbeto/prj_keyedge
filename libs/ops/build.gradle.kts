// Tiny operational helpers shipped in every image (JDK only): the container health probe.
plugins { id("keyedge.quality") }
keyedgeCoverage { minimum = 0.80 }
dependencies { testImplementation(project(":libs:testkit")) }
