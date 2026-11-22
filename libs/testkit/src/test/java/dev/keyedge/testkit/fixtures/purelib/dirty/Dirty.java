package dev.keyedge.testkit.fixtures.purelib.dirty;

import java.security.SecureRandom;
import java.time.Instant;

/** Breaks pureLibrary (reactor) and noHiddenRandomnessOrTime (new SecureRandom, Instant.now). */
public final class Dirty {
  public String run() {
    new SecureRandom().nextInt();
    return reactor.fixture.Mono.just(Instant.now().toString());
  }
}
