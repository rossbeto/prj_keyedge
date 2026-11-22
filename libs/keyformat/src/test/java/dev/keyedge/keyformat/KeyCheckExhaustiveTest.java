package dev.keyedge.keyformat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.keyedge.problem.ProblemType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The exhaustive switch below has no default branch: adding a {@link KeyCheck} case breaks this
 * file at compile time, which is the point of a sealed verdict.
 */
class KeyCheckExhaustiveTest {
  static ProblemType problemFor(KeyCheck check) {
    return switch (check) {
      case KeyCheck.Valid v -> throw new IllegalArgumentException("valid keys have no problem");
      case KeyCheck.Malformed m -> ProblemType.MALFORMED_KEY;
      case KeyCheck.Unknown u -> ProblemType.UNKNOWN_KEY;
      case KeyCheck.Revoked r -> ProblemType.REVOKED_KEY;
      case KeyCheck.Expired e -> ProblemType.EXPIRED_KEY;
      case KeyCheck.RotatedOut r -> ProblemType.UNKNOWN_KEY; // don't reveal that the key existed
    };
  }

  @Test
  void everyRejectionMapsToA401Problem() {
    var id = new KeyId(1, 1);
    var at = Instant.EPOCH;
    List<KeyCheck> rejections =
        List.of(
            new KeyCheck.Malformed(MalformedReason.CHECKSUM),
            new KeyCheck.Unknown(id),
            new KeyCheck.Revoked(id, at),
            new KeyCheck.Expired(id, at),
            new KeyCheck.RotatedOut(id, at));
    assertThat(rejections).allSatisfy(c -> assertThat(problemFor(c).status()).isEqualTo(401));
  }

  @Test
  void validCarriesAnImmutableCopyOfScopes() {
    var scopes = new java.util.HashSet<>(Set.of(new Scope("quotes:read")));
    var valid =
        new KeyCheck.Valid(
            new TenantId("acme"),
            new KeyId(1, 1),
            scopes,
            new RatePlan(10, 20),
            Duration.ofSeconds(60));
    scopes.add(new Scope("quotes:write"));
    assertThat(valid.scopes()).containsExactly(new Scope("quotes:read"));
    assertThatThrownBy(() -> problemFor(valid)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void valueTypesValidate() {
    assertThatThrownBy(() -> new TenantId("A")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Scope("quotes")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RatePlan(0, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RatePlan(1, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThat(new RatePlan(1, 1).burst()).isEqualTo(1); // boundary: 1 is the smallest valid value
    assertThat(KeyId.parse("7" + "Z".repeat(25))).isPresent(); // largest first character
    assertThat(new TenantId("acme-1").toString()).isEqualTo("acme-1");
    assertThat(new Scope("quotes:read").toString()).isEqualTo("quotes:read");
  }
}
