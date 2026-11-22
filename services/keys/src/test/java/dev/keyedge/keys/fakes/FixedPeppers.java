package dev.keyedge.keys.fakes;

import dev.keyedge.keyformat.Pepper;
import dev.keyedge.keyformat.PepperVersion;
import dev.keyedge.keys.ports.PepperProvider;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Peppers in memory; {@link #rotate()} adds a new current version. */
public final class FixedPeppers implements PepperProvider {
  private final Map<Integer, Pepper> peppers = new TreeMap<>();
  private int current;

  public FixedPeppers() {
    rotate();
  }

  public void rotate() {
    current++;
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) current);
    peppers.put(current, new Pepper(new PepperVersion(current), key));
  }

  public void retire(int version) {
    peppers.remove(version);
  }

  @Override
  public Pepper current() {
    return peppers.get(current);
  }

  @Override
  public Optional<Pepper> byVersion(PepperVersion version) {
    return Optional.ofNullable(peppers.get(version.value()));
  }
}
