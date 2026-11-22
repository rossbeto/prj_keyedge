package dev.keyedge.keys.adapters;

import dev.keyedge.keys.fakes.InMemoryKeys;
import dev.keyedge.keys.ports.Idempotency;
import dev.keyedge.keys.ports.KeyRepository;
import java.time.Duration;

/** The fake obeys the same contract as the real store, so use-case tests with it mean something. */
class InMemoryKeyStoreTest extends KeyStoreContract {
  private final InMemoryKeys store = new InMemoryKeys(Duration.ofSeconds(30));

  @Override
  protected KeyRepository keys() {
    return store;
  }

  @Override
  protected Idempotency idempotency() {
    return store;
  }
}
