package dev.keyedge.issuer.adapters.nimbus;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.keyedge.issuer.ports.TokenSigner;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/** RS256 with a key generated at start-up (local issuer only: tokens die with the process). */
public final class NimbusSigner implements TokenSigner {
  private final RSAKey key;

  public NimbusSigner() {
    try {
      key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
    } catch (JOSEException e) {
      throw new IllegalStateException("cannot generate an RSA key", e);
    }
  }

  @Override
  public String sign(Map<String, Object> claims, Instant issuedAt, Instant expiresAt) {
    var builder =
        new JWTClaimsSet.Builder()
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiresAt))
            .jwtID(UUID.randomUUID().toString());
    claims.forEach(builder::claim);
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            builder.build());
    try {
      jwt.sign(new RSASSASigner(key));
    } catch (JOSEException e) {
      throw new IllegalStateException("signing failed", e);
    }
    return jwt.serialize();
  }

  @Override
  public String publicJwks() {
    return new JWKSet(key.toPublicJWK()).toString();
  }
}
