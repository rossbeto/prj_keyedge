package dev.keyedge.keys.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyFormat;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.ParseResult;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.keys.domain.KeyStatus;
import dev.keyedge.keys.domain.KeysError;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KeysUseCasesTest {
  private final KeysScenario s = new KeysScenario();
  private final IssueKey.Command cmd =
      new IssueKey.Command("ci", Set.of(new Scope("quotes:read")), Optional.empty());

  private IssueKey.Result.Issued issue(String idem) {
    return (IssueKey.Result.Issued) s.issue.run(s.ctx("acme"), cmd, idem);
  }

  private KeyCheck verify(String fullKey) {
    var parsed = (ParseResult.Parsed) KeyFormat.parse(fullKey);
    return s.verify.verify(parsed.keyId(), parsed.secret());
  }

  @Test
  void issuedKeyVerifiesAndStoresOnlyTheHash() {
    var issued = issue("i-1");
    assertThat(verify(issued.fullKey()))
        .isInstanceOfSatisfying(
            KeyCheck.Valid.class,
            v -> {
              assertThat(v.tenant().value()).isEqualTo("acme");
              assertThat(v.scopes()).containsExactly(new Scope("quotes:read"));
              assertThat(v.ttlHint()).isEqualTo(Duration.ofSeconds(60));
            });
    var stored = s.store.find(issued.key().keyId()).orElseThrow();
    assertThat(stored.toString()).doesNotContain(issued.fullKey().substring(35, 78));
    assertThat(stored.hint()).isEqualTo("ke_test_…" + issued.fullKey().substring(74, 78));
  }

  @Test
  void sameIdempotencyKeyReplaysWithoutTheSecret() {
    var first = issue("i-2");
    var again = s.issue.run(s.ctx("acme"), cmd, "i-2");
    assertThat(again)
        .isInstanceOfSatisfying(
            IssueKey.Result.Replayed.class,
            r -> assertThat(r.response().keyId()).isEqualTo(first.key().keyId().toString()));
    assertThat(s.store.size()).isEqualTo(1);
  }

  @Test
  void differentBodySameKeyIsAMismatchAndOtherTenantsDoNotCollide() {
    issue("i-3");
    var other = new IssueKey.Command("other", Set.of(), Optional.empty());
    assertThat(s.issue.run(s.ctx("acme"), other, "i-3"))
        .isInstanceOf(IssueKey.Result.Mismatch.class);
    assertThat(s.issue.run(s.ctx("globex"), cmd, "i-3")).isInstanceOf(IssueKey.Result.Issued.class);
  }

  @Test
  void inFlightUntilTheClaimTimesOutThenTakenOver() {
    var request =
        new dev.keyedge.keys.ports.Idempotency.Request(
            "acme", "i-4", IssueKey.hash("acme\n" + cmd.canonical()));
    s.store.begin(request, s.clock.instant()); // another request owns it
    assertThat(s.issue.run(s.ctx("acme"), cmd, "i-4")).isInstanceOf(IssueKey.Result.InFlight.class);
    s.clock.advance(Duration.ofSeconds(31));
    assertThat(s.issue.run(s.ctx("acme"), cmd, "i-4")).isInstanceOf(IssueKey.Result.Issued.class);
  }

  @Test
  void idCollisionsAreRetriedThenGivenUp() {
    s.store.forcedCollisions = 2;
    assertThat(issue("i-5").key()).isNotNull();
    s.store.forcedCollisions = 3;
    assertThat(s.issue.run(s.ctx("acme"), cmd, "i-6"))
        .isEqualTo(new IssueKey.Result.Failed(new KeysError.Conflict()));
    // the claim was released: a retry may succeed
    assertThat(s.issue.run(s.ctx("acme"), cmd, "i-6")).isInstanceOf(IssueKey.Result.Issued.class);
  }

  @Test
  void pastExpiryIsRejected() {
    var past = new IssueKey.Command("x", Set.of(), Optional.of(s.clock.instant()));
    assertThat(s.issue.run(s.ctx("acme"), past, "i-7")).isInstanceOf(IssueKey.Result.Failed.class);
  }

  @Test
  void rotationKeepsTheOldSecretForTheGracePeriodOnly() {
    var issued = issue("r-1");
    var rotated =
        (ManageKeys.Change.Changed)
            s.manage.rotate(s.ctx("acme"), issued.key().keyId(), Duration.ofHours(1));
    String newKey = rotated.fullKey().orElseThrow();
    assertThat(verify(newKey)).isInstanceOf(KeyCheck.Valid.class);
    assertThat(verify(issued.fullKey()))
        .isInstanceOfSatisfying(
            KeyCheck.Valid.class, v -> assertThat(v.ttlHint()).isEqualTo(Duration.ofSeconds(60)));
    s.clock.advance(Duration.ofMinutes(59).plusSeconds(30));
    assertThat(verify(issued.fullKey()))
        .isInstanceOfSatisfying(
            KeyCheck.Valid.class,
            v ->
                assertThat(v.ttlHint()).isEqualTo(Duration.ofSeconds(30))); // capped at retireAfter
    s.clock.advance(Duration.ofMinutes(1));
    assertThat(verify(issued.fullKey())).isInstanceOf(KeyCheck.RotatedOut.class);
    assertThat(verify(newKey)).isInstanceOf(KeyCheck.Valid.class);
  }

  @Test
  void revokeIsIdempotentAndFinal() {
    var issued = issue("v-1");
    var first = (ManageKeys.Change.Changed) s.manage.revoke(s.ctx("acme"), issued.key().keyId());
    s.clock.advance(Duration.ofMinutes(5));
    var second = (ManageKeys.Change.Changed) s.manage.revoke(s.ctx("acme"), issued.key().keyId());
    assertThat(second.key()).isEqualTo(first.key());
    assertThat(verify(issued.fullKey())).isInstanceOf(KeyCheck.Revoked.class);
    assertThat(s.manage.rotate(s.ctx("acme"), issued.key().keyId(), Duration.ZERO))
        .isInstanceOf(ManageKeys.Change.Failed.class);
  }

  @Test
  void otherTenantsKeysAreNotFound() {
    var issued = issue("t-1");
    assertThat(s.manage.get(s.ctx("globex"), issued.key().keyId())).isEmpty();
    assertThat(s.manage.revoke(s.ctx("globex"), issued.key().keyId()))
        .isEqualTo(new ManageKeys.Change.Failed(new KeysError.NotFound()));
  }

  @Test
  void graceIsBounded() {
    var issued = issue("g-1");
    assertThat(s.manage.rotate(s.ctx("acme"), issued.key().keyId(), Duration.ofDays(8)))
        .isInstanceOf(ManageKeys.Change.Failed.class);
    assertThat(s.manage.rotate(s.ctx("acme"), issued.key().keyId(), Duration.ofSeconds(-1)))
        .isInstanceOf(ManageKeys.Change.Failed.class);
  }

  @Test
  void unknownKeysAndRetiredPeppers() {
    assertThat(
            s.verify.verify(new KeyId(1, 2), dev.keyedge.keyformat.KeySecret.ofBytes(new byte[32])))
        .isInstanceOf(KeyCheck.Unknown.class);
    var old = issue("p-1");
    s.peppers.rotate();
    var fresh = issue("p-2");
    assertThat(verify(old.fullKey()))
        .isInstanceOf(KeyCheck.Valid.class); // old pepper still readable
    assertThat(s.store.find(fresh.key().keyId()).orElseThrow().hash().version().value())
        .isEqualTo(2);
    s.peppers.retire(1);
    assertThat(verify(old.fullKey())).isInstanceOf(KeyCheck.Unknown.class);
  }

  @Test
  void listingPagesNewestFirstAndIsTenantScoped() {
    for (int i = 0; i < 5; i++) {
      issue("l-" + i);
      s.clock.advance(Duration.ofMillis(5));
    }
    s.issue.run(s.ctx("globex"), cmd, "other");
    var page1 = s.manage.list(s.ctx("acme"), Optional.empty(), 2);
    var page2 = s.manage.list(s.ctx("acme"), page1.next(), 2);
    var page3 = s.manage.list(s.ctx("acme"), page2.next(), 2);
    assertThat(page1.keys()).hasSize(2);
    assertThat(page3.keys()).hasSize(1);
    assertThat(page3.next()).isEmpty();
    assertThat(page1.keys().getFirst().keyId()).isGreaterThan(page1.keys().getLast().keyId());
  }

  @Test
  void expiredKeysAreExpired() {
    var issued =
        (IssueKey.Result.Issued)
            s.issue.run(
                s.ctx("acme"),
                new IssueKey.Command("e", Set.of(), Optional.of(s.clock.instant().plusSeconds(30))),
                "e-1");
    assertThat(verify(issued.fullKey()))
        .isInstanceOfSatisfying(
            KeyCheck.Valid.class, v -> assertThat(v.ttlHint()).isEqualTo(Duration.ofSeconds(30)));
    s.clock.advance(Duration.ofSeconds(30));
    assertThat(verify(issued.fullKey())).isInstanceOf(KeyCheck.Expired.class);
    assertThat(s.store.find(issued.key().keyId()).orElseThrow().status())
        .isInstanceOf(KeyStatus.Active.class);
    assertThat(Instant.EPOCH).isBefore(s.clock.instant());
  }
}
