package dev.keyedge.keys.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.keyformat.TenantId;
import dev.keyedge.keys.domain.UsageWindow;
import dev.keyedge.keys.fakes.InMemoryUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UsageAndBoundariesTest {
  private final KeysScenario s = new KeysScenario();
  private final InMemoryUsage usageStore = new InMemoryUsage();
  private final RecordUsage usage = new RecordUsage(usageStore, s.store);

  private KeyId issue(String idem, String name) {
    var r =
        (IssueKey.Result.Issued)
            s.issue.run(
                s.ctx("acme"),
                new IssueKey.Command(name, Set.of(new Scope("quotes:read")), Optional.empty()),
                idem);
    return r.key().keyId();
  }

  @Test
  void usageIsRecordedMergedAndOnlyVisibleToTheOwner() {
    KeyId id = issue("u-1", "u");
    Instant m = Instant.parse("2026-11-23T10:00:00Z");
    usage.record(
        List.of(
            new UsageWindow(new TenantId("acme"), id, m.plusSeconds(10), 2, 1),
            new UsageWindow(new TenantId("acme"), id, m.plusSeconds(20), 3, 0)));
    usage.record(List.of());
    var rows = usage.query(s.ctx("acme"), id, m, m.plusSeconds(60)).orElseThrow();
    assertThat(rows)
        .singleElement()
        .satisfies(w -> {
          assertThat(w.allowed()).isEqualTo(5);
          assertThat(w.rejected()).isEqualTo(1);
        });
    assertThat(usage.query(s.ctx("globex"), id, m, m.plusSeconds(60))).isEmpty();
    assertThatThrownBy(() -> new UsageWindow(new TenantId("acme"), id, m, -1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new UsageWindow(new TenantId("acme"), id, m, 0, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new UsageWindow(new TenantId("acme"), id, m, 0, 0).allowed()).isZero();
  }

  @Test
  void nameAndGraceBoundaries() {
    assertThat(issue("n-1", "x".repeat(80))).isNotNull();
    assertThat(
            s.issue.run(
                s.ctx("acme"),
                new IssueKey.Command("x".repeat(81), Set.of(), Optional.empty()),
                "n-2"))
        .isNotInstanceOf(IssueKey.Result.Issued.class);
    KeyId id = issue("n-3", "g");
    assertThat(s.manage.rotate(s.ctx("acme"), id, Duration.ofDays(7)))
        .isInstanceOf(ManageKeys.Change.Changed.class);
    assertThat(s.manage.rotate(s.ctx("acme"), id, Duration.ZERO))
        .isInstanceOf(ManageKeys.Change.Changed.class);
  }

  @Test
  void listLimitIsClampedTo1to100() {
    for (int i = 0; i < 3; i++) {
      issue("c-" + i, "c");
    }
    assertThat(s.manage.list(s.ctx("acme"), Optional.empty(), 0).keys()).hasSize(1);
    assertThat(s.manage.list(s.ctx("acme"), Optional.empty(), 1000).keys()).hasSize(3);
  }

  @Test
  void ttlHintEqualToTheRemainingLifetimeIsKept() {
    var r =
        (IssueKey.Result.Issued)
            s.issue.run(
                s.ctx("acme"),
                new IssueKey.Command("t", Set.of(), Optional.of(s.clock.instant().plusSeconds(60))),
                "t-1");
    var p =
        (dev.keyedge.keyformat.ParseResult.Parsed)
            dev.keyedge.keyformat.KeyFormat.parse(r.fullKey());
    var v = (dev.keyedge.keyformat.KeyCheck.Valid) s.verify.verify(p.keyId(), p.secret());
    assertThat(v.ttlHint()).isEqualTo(Duration.ofSeconds(60));
    assertThat(r.key().toString()).doesNotContain(p.secret().text());
  }
}
