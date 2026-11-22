package dev.keyedge.keys.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;

/** A throwaway RSA issuer for tests: its public JWKS is written to a temp file Micronaut reads. */
final class TestTokens {
  private final RSAKey key;
  final Path jwks;

  TestTokens() {
    try {
      key = new RSAKeyGenerator(2048).keyID("test-1").generate();
      jwks = Files.createTempFile("keyedge-jwks-", ".json");
      Files.writeString(jwks, new JWKSet(key.toPublicJWK()).toString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (com.nimbusds.jose.JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  String token(String tenant, String scope) {
    return token("keyedge-issuer", tenant, scope);
  }

  String token(String issuer, String tenant, String scope) {
    try {
      var claims =
          new JWTClaimsSet.Builder()
              .issuer(issuer)
              .subject("tester")
              .claim("tenant", tenant)
              .claim("scope", scope)
              .issueTime(new Date())
              .expirationTime(new Date(System.currentTimeMillis() + 600_000))
              .build();
      var jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (com.nimbusds.jose.JOSEException e) {
      throw new IllegalStateException(e);
    }
  }
}
