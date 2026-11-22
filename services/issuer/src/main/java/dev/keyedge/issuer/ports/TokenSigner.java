package dev.keyedge.issuer.ports;

import java.time.Instant;
import java.util.Map;

/** Signs claims into a compact JWT and publishes the public key set. */
public interface TokenSigner {
  String sign(Map<String, Object> claims, Instant issuedAt, Instant expiresAt);

  /** Public JWKS as JSON. */
  String publicJwks();
}
