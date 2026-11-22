package dev.keyedge.awstarget;

import java.net.URI;
import java.security.KeyStore;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * Everything an AWS SDK client needs to talk to one target.
 *
 * @param endpoint endpoint override; empty only for real AWS
 * @param trustStore extra trust (AWS Emulator's per-run CA); in memory, never JVM-wide
 * @param connectHost host to open TCP connections to while TLS still verifies the endpoint's name.
 *     Used from other containers: AWS Emulator's certificate only names {@code localhost}/{@code
 *     127.0.0.1}, so clients keep {@code https://localhost:15297} and resolve {@code localhost} to
 *     the emulator container (Phase 001 spike S4).
 */
public record AwsTarget(
    TargetKind kind,
    Optional<URI> endpoint,
    Region region,
    AwsCredentialsProvider credentials,
    Optional<KeyStore> trustStore,
    Optional<String> connectHost) {

  /** True for the two local emulators. */
  public boolean local() {
    return kind != TargetKind.AWS;
  }

  @Override
  public String toString() {
    return "AwsTarget[" + kind.id() + " " + endpoint.map(URI::toString).orElse("default") + "]";
  }
}
