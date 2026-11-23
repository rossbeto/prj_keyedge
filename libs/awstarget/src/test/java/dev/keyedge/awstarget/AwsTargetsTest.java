package dev.keyedge.awstarget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;

class AwsTargetsTest {
  // A throwaway self-signed CA (not a secret: it signs nothing anyone trusts).
  static final String CA =
      """
      -----BEGIN CERTIFICATE-----
      MIIBiTCCAS+gAwIBAgIUfLQU7KVhJdxSLVV375UC5M6cXIAwCgYIKoZIzj0EAwIw
      GjEYMBYGA1UEAwwPa2V5ZWRnZS10ZXN0LWNhMB4XDTI2MTEyMjIxNTI1N1oXDTM2
      MTExOTIxNTI1N1owGjEYMBYGA1UEAwwPa2V5ZWRnZS10ZXN0LWNhMFkwEwYHKoZI
      zj0CAQYIKoZIzj0DAQcDQgAEElTrKlUVqAPh4FZR/RrPDQvlP+bXAd6OxsMlpgi9
      pSO6WLkKYukwpogkaiIkFSs2qn15PRQBd7VLAMgvKdv/ZKNTMFEwHQYDVR0OBBYE
      FELLqlpCLRHhghsF7ES/b90ZaqSDMB8GA1UdIwQYMBaAFELLqlpCLRHhghsF7ES/
      b90ZaqSDMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIgVrDjhO3f
      VVjw7PTYEMd0SV+5qdJfhwS6fVxOFDhqnMECIQCRcnwvee13DZbaT+vl3ouBVKbQ
      UAJC8Ej7lRd+7QL9GQ==
      -----END CERTIFICATE-----
      """;

  @Test
  void flociIsTheDefault() {
    var t = AwsTargets.resolve(Map.of(), Path.of("/nonexistent"));
    assertThat(t.kind()).isEqualTo(TargetKind.FLOCI);
    assertThat(t.endpoint()).contains(AwsTargets.FLOCI_ENDPOINT);
    assertThat(t.region()).isEqualTo(Region.US_EAST_1);
    assertThat(t.local()).isTrue();
    assertThat(t.trustStore()).isEmpty();
    AwsCredentials c = t.credentials().resolveCredentials();
    assertThat(c.accessKeyId()).isEqualTo("test");
    assertThat(t.toString()).isEqualTo("AwsTarget[floci http://127.0.0.1:15566]");
  }

  @Test
  void endpointRegionAndConnectHostAreOverridable() {
    var t =
        AwsTargets.resolve(
            Map.of(
                "KEYEDGE_AWS_TARGET", "FLOCI",
                "KEYEDGE_AWS_ENDPOINT", "http://floci:4566",
                "AWS_REGION", "eu-west-1"),
            Path.of("/x"));
    assertThat(t.endpoint()).contains(URI.create("http://floci:4566"));
    assertThat(t.region()).isEqualTo(Region.EU_WEST_1);
  }

  @Test
  void awsEmulatorReadsItsPerRunFiles(@TempDir Path home) throws Exception {
    Path dir = Files.createDirectories(home.resolve(".aws/emulator/15297"));
    Files.writeString(dir.resolve("ca.pem"), CA);
    Files.writeString(
        dir.resolve("emulator_credentials"),
        "[emulator]\naws_access_key_id = AKEMULATOR\naws_secret_access_key = s3cr3t\n");
    var t =
        AwsTargets.resolve(
            Map.of("KEYEDGE_AWS_TARGET", "aws-emulator", "KEYEDGE_AWS_CONNECT_HOST", "emu"), home);
    assertThat(t.kind()).isEqualTo(TargetKind.AWS_EMULATOR);
    assertThat(t.endpoint()).contains(AwsTargets.AWS_EMULATOR_ENDPOINT);
    assertThat(t.credentials().resolveCredentials().accessKeyId()).isEqualTo("AKEMULATOR");
    assertThat(t.trustStore()).isPresent();
    assertThat(t.trustStore().orElseThrow().size()).isEqualTo(1);
    assertThat(t.connectHost()).contains("emu");
  }

  @Test
  void awsEmulatorWithoutItsFilesFailsWithAHint(@TempDir Path home) {
    assertThatThrownBy(() -> AwsTargets.resolve(Map.of("KEYEDGE_AWS_TARGET", "aws-emulator"), home))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("make emu-up TARGET=aws-emulator");
  }

  @Test
  void realAwsIsRefusedUnlessExplicitlyAllowed() {
    assertThatThrownBy(() -> AwsTargets.resolve(Map.of("KEYEDGE_AWS_TARGET", "aws"), Path.of("/x")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("KEYEDGE_ALLOW_REAL_AWS");
    var t =
        AwsTargets.resolve(
            Map.of("KEYEDGE_AWS_TARGET", "aws", "KEYEDGE_ALLOW_REAL_AWS", "yes"), Path.of("/x"));
    assertThat(t.local()).isFalse();
    assertThat(t.endpoint()).isEmpty();
    assertThat(t.toString()).isEqualTo("AwsTarget[aws default]");
  }

  @Test
  void unknownTargetsAreRejected() {
    assertThatThrownBy(() -> TargetKind.of("localstack"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(TargetKind.of(" Aws-Emulator ")).isEqualTo(TargetKind.AWS_EMULATOR);
  }

  @Test
  void trustStoreRejectsAFileWithoutCertificates(@TempDir Path dir) throws Exception {
    Path empty = Files.writeString(dir.resolve("empty.pem"), "nothing here\n");
    assertThatThrownBy(() -> TrustStores.fromPem(empty)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> TrustStores.fromPem(dir.resolve("missing.pem")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void configurerAppliesEndpointRegionCredentialsAndHttpClient() {
    var t = AwsTargets.floci(URI.create("http://127.0.0.1:1"), Region.AP_SOUTHEAST_1);
    try (StsClient sts = AwsTargetConfigurer.configure(StsClient.builder(), t).build()) {
      assertThat(sts.serviceClientConfiguration().endpointOverride())
          .contains(URI.create("http://127.0.0.1:1"));
      assertThat(sts.serviceClientConfiguration().region()).isEqualTo(Region.AP_SOUTHEAST_1);
    }
  }

  @Test
  void fixedHostResolverSendsEveryNameToOneHost() throws Exception {
    var resolver = new AwsTargetConfigurer.FixedHostResolver("127.0.0.1");
    assertThat(resolver.resolve("localhost")[0].getHostAddress()).isEqualTo("127.0.0.1");
    assertThat(resolver.resolve("anything.example")[0].getHostAddress()).isEqualTo("127.0.0.1");
    assertThat(resolver.resolveCanonicalHostname("localhost")).isEqualTo("localhost");
    var t =
        new AwsTarget(
            TargetKind.FLOCI,
            Optional.empty(),
            Region.US_EAST_1,
            AwsTargets.floci(URI.create("http://x"), Region.US_EAST_1).credentials(),
            Optional.empty(),
            Optional.of("127.0.0.1"));
    try (var http = AwsTargetConfigurer.httpClient(t)) {
      assertThat(http).isNotNull();
    }
  }

  @org.junit.jupiter.api.Test
  void flociHonoursTheStandardEndpointVariableAfterOurs() {
    var injected = Map.of("AWS_ENDPOINT_URL", "http://localhost.floci.io:4566");
    assertThat(AwsTargets.resolve(injected, Path.of("/nonexistent")).endpoint())
        .contains(java.net.URI.create("http://localhost.floci.io:4566"));
    var both = Map.of("AWS_ENDPOINT_URL", "http://a:1", "KEYEDGE_AWS_ENDPOINT", "http://b:2");
    assertThat(AwsTargets.resolve(both, Path.of("/nonexistent")).endpoint())
        .contains(java.net.URI.create("http://b:2"));
  }
}
