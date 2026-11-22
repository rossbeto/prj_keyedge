package dev.keyedge.keys.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.awstarget.AwsTargetConfigurer;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.PepperVersion;
import dev.keyedge.keyformat.TenantId;
import dev.keyedge.keys.adapters.dynamodb.DynamoUsage;
import dev.keyedge.keys.adapters.dynamodb.KeysTable;
import dev.keyedge.keys.adapters.secrets.SecretsManagerCallers;
import dev.keyedge.keys.adapters.secrets.SecretsManagerPeppers;
import dev.keyedge.keys.domain.UsageWindow;
import dev.keyedge.testkit.aws.Emulators;
import dev.keyedge.testkit.aws.OnTarget;
import io.micronaut.json.JsonMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/** Secrets and usage adapters against a real emulator. */
@Tag("aws")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SecretsAndUsageContract implements OnTarget {
  private SecretsManagerClient secrets;
  private DynamoDbClient dynamo;
  private String table;
  private final JsonMapper json = JsonMapper.createDefault();

  @BeforeAll
  void clients() {
    var target = Emulators.start(kind());
    secrets = AwsTargetConfigurer.configure(SecretsManagerClient.builder(), target).build();
    dynamo = AwsTargetConfigurer.configure(DynamoDbClient.builder(), target).build();
    table = "keyedge-usage-" + UUID.randomUUID().toString().substring(0, 8);
    KeysTable.create(dynamo, table);
  }

  private static String b64(int fill) {
    byte[] b = new byte[32];
    java.util.Arrays.fill(b, (byte) fill);
    return Base64.getEncoder().encodeToString(b);
  }

  @Test
  void peppersByVersionAndCurrent() {
    String id = "keyedge/peppers-" + UUID.randomUUID();
    secrets.createSecret(b ->
        b.name(id)
            .secretString(
                "{\"current\":2,\"peppers\":{\"1\":\"" + b64(1) + "\",\"2\":\"" + b64(2) + "\"}}"));
    var peppers = new SecretsManagerPeppers(secrets, json, id, Duration.ofMinutes(5));
    assertThat(peppers.current().version()).isEqualTo(new PepperVersion(2));
    assertThat(peppers.byVersion(new PepperVersion(1))).isPresent();
    assertThat(peppers.byVersion(new PepperVersion(3))).isEmpty();
  }

  @Test
  void callerCredentials() {
    String id = "keyedge/callers-" + UUID.randomUUID();
    secrets.createSecret(b -> b.name(id).secretString("{\"edge-quotes\":\"" + b64(7) + "\"}"));
    var callers = new SecretsManagerCallers(secrets, json, id, Duration.ofMinutes(5));
    assertThat(callers.credential("edge-quotes"))
        .hasValueSatisfying(c -> assertThat(c).hasSize(32));
    assertThat(callers.credential("nobody")).isEmpty();
  }

  @Test
  void usageMergesPerMinuteAndQueriesARange() {
    var usage = new DynamoUsage(dynamo, table, Duration.ofDays(30));
    var tenant = new TenantId("acme");
    var key = new KeyId(1, UUID.randomUUID().getLeastSignificantBits());
    Instant m = Instant.parse("2026-11-23T10:00:00Z");
    usage.add(
        List.of(
            new UsageWindow(tenant, key, m.plusSeconds(5), 3, 1),
            new UsageWindow(tenant, key, m.plusSeconds(30), 2, 0),
            new UsageWindow(tenant, key, m.plusSeconds(60), 10, 4)));
    var rows = usage.query(tenant, key, m, m.plusSeconds(120));
    assertThat(rows).extracting(UsageWindow::allowed).containsExactly(5L, 10L);
    assertThat(rows).extracting(UsageWindow::rejected).containsExactly(1L, 4L);
    assertThat(usage.query(tenant, key, m, m.plusSeconds(60))).hasSize(1);
  }
}
