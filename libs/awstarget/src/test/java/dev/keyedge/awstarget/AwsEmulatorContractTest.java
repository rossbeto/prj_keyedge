package dev.keyedge.awstarget;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * The AWS contract suite on AWS Emulator, plus the TLS-with-redirected-connection case (spike S4).
 */
class AwsEmulatorContractTest extends AwsContractSuite {
  @Override
  public TargetKind kind() {
    return TargetKind.AWS_EMULATOR;
  }

  @Test
  void connectHostOverrideKeepsTlsVerificationOnTheEndpointName() {
    // Endpoint names "localhost" (in the certificate); connections go to 127.0.0.1 via the
    // resolver.
    var redirected =
        new AwsTarget(
            target.kind(),
            Optional.of(
                URI.create(
                    "https://localhost:" + dev.keyedge.testkit.aws.AwsEmulatorContainer.PORT)),
            target.region(),
            target.credentials(),
            target.trustStore(),
            Optional.of("127.0.0.1"));
    try (var sts = AwsTargetConfigurer.configure(StsClient.builder(), redirected).build()) {
      assertThat(sts.getCallerIdentity().account()).isEqualTo("000000000000");
    }
  }
}
