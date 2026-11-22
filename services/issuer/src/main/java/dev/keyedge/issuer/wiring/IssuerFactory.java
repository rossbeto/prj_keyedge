package dev.keyedge.issuer.wiring;

import dev.keyedge.issuer.adapters.nimbus.NimbusSigner;
import dev.keyedge.issuer.app.IssueToken;
import dev.keyedge.issuer.ports.TokenSigner;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.InstantSource;

/** Composition root of the issuer. */
@Factory
public class IssuerFactory {
  @Singleton
  TokenSigner signer() {
    return new NimbusSigner();
  }

  @Singleton
  IssueToken issueToken(TokenSigner signer) {
    return new IssueToken(signer, InstantSource.system(), Duration.ofHours(24));
  }
}
