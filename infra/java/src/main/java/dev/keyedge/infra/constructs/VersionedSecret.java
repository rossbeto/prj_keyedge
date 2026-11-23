package dev.keyedge.infra.constructs;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.secretsmanager.SecretStringGenerator;
import software.constructs.Construct;

/**
 * A Secrets Manager secret holding a JSON document. CloudFormation generates one random field in a
 * template ({@code {"current":1}} + key {@code "1"} for the peppers): the value never appears in
 * the template or in git. In prodlike the generated value is a placeholder that the SOPS sync
 * replaces.
 */
public final class VersionedSecret extends Construct {
  private final Secret secret;

  /** Name and the generated field. */
  public record Props(
      String secretName, String template, String generatedKey, int length, boolean retain) {}

  public VersionedSecret(Construct scope, String id, Props p) {
    super(scope, id);
    secret =
        Secret.Builder.create(this, "Secret")
            .secretName(p.secretName())
            .description("keyedge " + p.secretName() + " (JSON document)")
            .generateSecretString(
                SecretStringGenerator.builder()
                    .secretStringTemplate(p.template())
                    .generateStringKey(p.generatedKey())
                    .passwordLength(p.length())
                    .excludePunctuation(true) // base64 alphabet: the services decode it
                    .includeSpace(false)
                    .build())
            .removalPolicy(p.retain() ? RemovalPolicy.RETAIN : RemovalPolicy.DESTROY)
            .build();
  }

  public Secret secret() {
    return secret;
  }
}
