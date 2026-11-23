package dev.keyedge.infra.constructs;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.kms.Key;
import software.constructs.Construct;

/** The KMS key SOPS encrypts secrets files to (second recipient besides age, D-44). */
public final class SopsKey extends Construct {
  private final Key key;

  public SopsKey(Construct scope, String id, String alias, boolean retain) {
    super(scope, id);
    key =
        Key.Builder.create(this, "Key")
            .alias(alias)
            .description("keyedge SOPS data key encryption")
            .enableKeyRotation(true)
            .removalPolicy(retain ? RemovalPolicy.RETAIN : RemovalPolicy.DESTROY)
            .pendingWindow(software.amazon.awscdk.Duration.days(7))
            .build();
  }

  public Key key() {
    return key;
  }
}
