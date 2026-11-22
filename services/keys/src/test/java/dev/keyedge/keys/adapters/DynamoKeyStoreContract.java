package dev.keyedge.keys.adapters;

import dev.keyedge.awstarget.AwsTargetConfigurer;
import dev.keyedge.keys.adapters.dynamodb.DynamoKeys;
import dev.keyedge.keys.adapters.dynamodb.KeysTable;
import dev.keyedge.keys.ports.Idempotency;
import dev.keyedge.keys.ports.KeyRepository;
import dev.keyedge.testkit.aws.Emulators;
import dev.keyedge.testkit.aws.OnTarget;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** The storage contract on a real emulator: one fresh table per class. */
@Tag("aws")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DynamoKeyStoreContract extends KeyStoreContract implements OnTarget {
  private DynamoKeys store;

  @BeforeAll
  void table() {
    var dynamo =
        AwsTargetConfigurer.configure(DynamoDbClient.builder(), Emulators.start(kind())).build();
    String name = "keyedge-keys-" + UUID.randomUUID().toString().substring(0, 8);
    KeysTable.create(dynamo, name);
    store = new DynamoKeys(dynamo, name, Duration.ofSeconds(30), Duration.ofHours(24));
  }

  @Override
  protected KeyRepository keys() {
    return store;
  }

  @Override
  protected Idempotency idempotency() {
    return store;
  }
}
