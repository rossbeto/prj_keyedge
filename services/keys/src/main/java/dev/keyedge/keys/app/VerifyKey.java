package dev.keyedge.keys.app;

import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;
import dev.keyedge.keys.domain.VerifyPolicy;
import dev.keyedge.keys.ports.KeyRepository;
import dev.keyedge.keys.ports.PepperProvider;
import java.time.InstantSource;

/** The sidecar's question: is this secret valid for this key id, and what may it do? One read. */
public final class VerifyKey {
  private final KeyRepository keys;
  private final PepperProvider peppers;
  private final VerifyPolicy policy;
  private final InstantSource clock;

  public VerifyKey(
      KeyRepository keys, PepperProvider peppers, VerifyPolicy policy, InstantSource clock) {
    this.keys = keys;
    this.peppers = peppers;
    this.policy = policy;
    this.clock = clock;
  }

  public KeyCheck verify(KeyId keyId, KeySecret secret) {
    return keys.find(keyId)
        .map(key -> policy.check(key, secret, peppers::byVersion, clock.instant()))
        .orElseGet(() -> new KeyCheck.Unknown(keyId));
  }
}
