package dev.keyedge.keys.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.keyformat.KeyEnvironment;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.PepperVersion;
import dev.keyedge.keyformat.RatePlan;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.keyformat.SecretHash;
import dev.keyedge.keyformat.TenantId;
import dev.keyedge.keys.domain.KeyLifecycle;
import dev.keyedge.keys.domain.KeyStatus;
import dev.keyedge.keys.domain.StoredKey;
import dev.keyedge.keys.ports.Idempotency;
import dev.keyedge.keys.ports.KeyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The same storage contract for the in-memory fake and the DynamoDB adapter (on both emulators).
 */
public abstract class KeyStoreContract {
  protected static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

  protected abstract KeyRepository keys();

  protected abstract Idempotency idempotency();

  static SecretHash hash(int b) {
    byte[] d = new byte[32];
    d[0] = (byte) b;
    return new SecretHash(new PepperVersion(1), d);
  }

  static StoredKey key(String tenant, KeyId id) {
    return new StoredKey(
        new TenantId(tenant),
        id,
        KeyEnvironment.TEST,
        "k",
        Set.of(new Scope("quotes:read")),
        new RatePlan(10, 20),
        hash(1),
        "ke_test_…abcd",
        new KeyStatus.Active(),
        NOW,
        Optional.of(NOW.plusSeconds(3600)),
        1);
  }

  static KeyId id() {
    var u = UUID.randomUUID();
    return new KeyId(u.getMostSignificantBits() >>> 1, u.getLeastSignificantBits());
  }

  private Idempotency.Request request(String tenant) {
    return new Idempotency.Request(tenant, "idem-" + UUID.randomUUID(), "hash-a");
  }

  private StoredKey create(String tenant) {
    var req = request(tenant);
    var started = ((Idempotency.Begin.Started) idempotency().begin(req, NOW)).startedAt();
    var k = key(tenant, id());
    assertThat(
            idempotency()
                .createAndComplete(
                    req,
                    started,
                    k,
                    new Idempotency.StoredResponse(201, k.keyId().toString(), "/x")))
        .isInstanceOf(Idempotency.Create.Created.class);
    return k;
  }

  @Test
  void createdKeysRoundTripEveryField() {
    var k = create("acme");
    assertThat(keys().find(k.keyId())).contains(k);
    var rotated = KeyLifecycle.rotate(k, hash(2), "ke_test_…wxyz", Duration.ofHours(1), NOW);
    assertThat(keys().update(rotated)).isTrue();
    assertThat(keys().find(k.keyId())).contains(rotated);
    var revoked = KeyLifecycle.revoke(rotated, NOW.plusSeconds(5));
    assertThat(keys().update(revoked)).isTrue();
    assertThat(keys().find(k.keyId())).contains(revoked);
  }

  @Test
  void staleVersionsAreRejected() {
    var k = create("acme");
    var v2 = KeyLifecycle.revoke(k, NOW);
    assertThat(keys().update(v2)).isTrue();
    assertThat(keys().update(v2)).as("same version again").isFalse();
    assertThat(keys().update(KeyLifecycle.revoke(key("acme", id()), NOW)))
        .as("unknown key")
        .isFalse();
  }

  @Test
  void idempotencyLifecycle() {
    var req = request("acme");
    var started = ((Idempotency.Begin.Started) idempotency().begin(req, NOW)).startedAt();
    assertThat(idempotency().begin(req, NOW.plusSeconds(1)))
        .isInstanceOf(Idempotency.Begin.InFlight.class);
    assertThat(
            idempotency()
                .begin(new Idempotency.Request("acme", req.idempotencyKey(), "hash-b"), NOW))
        .isInstanceOf(Idempotency.Begin.Mismatch.class);
    var k = key("acme", id());
    var response =
        new Idempotency.StoredResponse(201, k.keyId().toString(), "/v1/keys/" + k.keyId());
    assertThat(idempotency().createAndComplete(req, started, k, response))
        .isInstanceOf(Idempotency.Create.Created.class);
    assertThat(idempotency().begin(req, NOW.plusSeconds(2)))
        .isEqualTo(new Idempotency.Begin.Replay(response));
  }

  @Test
  void collisionAndLostClaimAreToldApart() {
    var existing = create("acme");
    var req = request("acme");
    var started = ((Idempotency.Begin.Started) idempotency().begin(req, NOW)).startedAt();
    var resp = new Idempotency.StoredResponse(201, "x", "/x");
    assertThat(idempotency().createAndComplete(req, started, key("acme", existing.keyId()), resp))
        .isInstanceOf(Idempotency.Create.IdCollision.class);
    assertThat(
            idempotency().createAndComplete(req, started.minusSeconds(1), key("acme", id()), resp))
        .isInstanceOf(Idempotency.Create.LostClaim.class);
  }

  @Test
  void abandonedAndStaleClaimsCanBeRetaken() {
    var req = request("acme");
    var started = ((Idempotency.Begin.Started) idempotency().begin(req, NOW)).startedAt();
    idempotency().abandon(req, started);
    assertThat(idempotency().begin(req, NOW.plusSeconds(1)))
        .isInstanceOf(Idempotency.Begin.Started.class);
    assertThat(idempotency().begin(req, NOW.plusSeconds(10)))
        .isInstanceOf(Idempotency.Begin.InFlight.class);
    assertThat(idempotency().begin(req, NOW.plusSeconds(60)))
        .isInstanceOf(Idempotency.Begin.Started.class);
  }

  @Test
  void listIsTenantScopedNewestFirstAndPaged() {
    String tenant = "t" + UUID.randomUUID().toString().substring(0, 8);
    for (int i = 0; i < 5; i++) {
      create(tenant);
    }
    create("other-tenant");
    var all = new java.util.ArrayList<StoredKey>();
    Optional<String> cursor = Optional.empty();
    do {
      var page = keys().list(new TenantId(tenant), cursor, 2);
      all.addAll(page.keys());
      cursor = page.next();
    } while (cursor.isPresent());
    assertThat(all).hasSize(5).allMatch(k -> k.tenant().value().equals(tenant));
    assertThat(all).isSortedAccordingTo((a, b) -> b.keyId().compareTo(a.keyId()));
  }
}
