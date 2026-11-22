package dev.keyedge.issuer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import dev.keyedge.testkit.KeyedgeArchitectureRules;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@MicronautTest
class IssuerTest {
  @Inject
  @Client("/")
  HttpClient client;

  @Test
  void tokensVerifyAgainstThePublishedJwks() throws Exception {
    var response =
        client
            .toBlocking()
            .retrieve(
                HttpRequest.POST(
                    "/token",
                    Map.of("tenant", "acme", "scopes", List.of("keys:write", "keys:read"))),
                Argument.mapOf(String.class, Object.class));
    var jwt = SignedJWT.parse((String) response.get("accessToken"));
    var jwks = JWKSet.parse(client.toBlocking().retrieve("/.well-known/jwks.json"));
    RSAKey key = (RSAKey) jwks.getKeyByKeyId(jwt.getHeader().getKeyID());
    assertThat(jwt.verify(new RSASSAVerifier(key))).isTrue();
    assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("keyedge-issuer");
    assertThat(jwt.getJWTClaimsSet().getStringClaim("tenant")).isEqualTo("acme");
    assertThat(jwt.getJWTClaimsSet().getStringClaim("scope")).isEqualTo("keys:read keys:write");
    assertThat(response).containsEntry("tokenType", "Bearer").containsEntry("expiresIn", 3600);
    assertThat(jwks.getKeys()).allSatisfy(k -> assertThat(k.isPrivate()).isFalse());
  }

  @Test
  void invalidInputIsAValidationProblem() {
    for (var body :
        List.of(
            Map.of("tenant", "A"),
            Map.of("tenant", "acme", "scopes", List.of("bad")),
            Map.<String, Object>of("tenant", "acme", "ttlSeconds", 999_999))) {
      var e =
          assertThrows(
              HttpClientResponseException.class,
              () -> client.toBlocking().exchange(HttpRequest.POST("/token", body)));
      assertThat(e.getStatus().getCode()).isEqualTo(400);
    }
  }

  @Test
  void followsTheLayeringRules() {
    KeyedgeArchitectureRules.checkAll("dev.keyedge.issuer");
  }
}
