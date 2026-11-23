package dev.keyedge.awstarget;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;

/**
 * Resolves the target from environment-style settings:
 *
 * <ul>
 *   <li>{@code KEYEDGE_AWS_TARGET}: floci (default) | aws-emulator | aws
 *   <li>{@code KEYEDGE_AWS_ENDPOINT}: endpoint override (defaults: Floci {@code
 *       http://127.0.0.1:15566}, AWS Emulator {@code https://127.0.0.1:15297}); for Floci the SDK's
 *       standard {@code AWS_ENDPOINT_URL} is honoured next — Floci injects it into the ECS tasks it
 *       runs (Phase 014)
 *   <li>{@code KEYEDGE_AWS_EMULATOR_DIR}: AWS Emulator's per-run folder (default {@code
 *       ~/.aws/emulator/15297}) holding {@code emulator_credentials} and {@code ca.pem}
 *   <li>{@code KEYEDGE_AWS_CONNECT_HOST}: open connections to this host (see {@link AwsTarget})
 *   <li>{@code AWS_REGION}: default us-east-1
 * </ul>
 */
public final class AwsTargets {
  /** Floci's default host endpoint. */
  public static final URI FLOCI_ENDPOINT = URI.create("http://127.0.0.1:15566");

  /** AWS Emulator's default host endpoint. */
  public static final URI AWS_EMULATOR_ENDPOINT = URI.create("https://127.0.0.1:15297");

  private AwsTargets() {}

  /** Resolves from the process environment and the user's home directory. */
  public static AwsTarget fromEnvironment() {
    return resolve(System.getenv(), Path.of(System.getProperty("user.home")));
  }

  /** Resolves from explicit settings (tests pass a map). */
  public static AwsTarget resolve(Map<String, String> env, Path home) {
    TargetKind kind = TargetKind.of(env.getOrDefault("KEYEDGE_AWS_TARGET", "floci"));
    Region region = Region.of(env.getOrDefault("AWS_REGION", "us-east-1"));
    Optional<String> connectHost = Optional.ofNullable(env.get("KEYEDGE_AWS_CONNECT_HOST"));
    Optional<URI> override = Optional.ofNullable(env.get("KEYEDGE_AWS_ENDPOINT")).map(URI::create);
    return switch (kind) {
      case FLOCI ->
          floci(
              override
                  .or(() -> Optional.ofNullable(env.get("AWS_ENDPOINT_URL")).map(URI::create))
                  .orElse(FLOCI_ENDPOINT),
              region);
      case AWS_EMULATOR -> {
        Path dir =
            Optional.ofNullable(env.get("KEYEDGE_AWS_EMULATOR_DIR"))
                .map(Path::of)
                .orElse(home.resolve(".aws/emulator/15297"));
        yield awsEmulator(override.orElse(AWS_EMULATOR_ENDPOINT), region, dir, connectHost);
      }
      case AWS -> {
        RealAwsGuard.check(env);
        yield new AwsTarget(
            TargetKind.AWS,
            Optional.empty(),
            region,
            DefaultCredentialsProvider.builder().build(),
            Optional.empty(),
            Optional.empty());
      }
    };
  }

  /** Floci: any non-empty static credentials, plain HTTP. */
  public static AwsTarget floci(URI endpoint, Region region) {
    return new AwsTarget(
        TargetKind.FLOCI,
        Optional.of(endpoint),
        region,
        StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")),
        Optional.empty(),
        Optional.empty());
  }

  /** AWS Emulator: this run's SigV4 keys and CA, read from its per-run folder. */
  public static AwsTarget awsEmulator(
      URI endpoint, Region region, Path runDir, Optional<String> connectHost) {
    Path credentials = runDir.resolve("emulator_credentials");
    Path ca = runDir.resolve("ca.pem");
    if (!Files.isReadable(credentials) || !Files.isReadable(ca)) {
      throw new IllegalStateException(
          "AWS Emulator files not found in "
              + runDir
              + " (is it running? make emu-up TARGET=aws-emulator)");
    }
    ProfileFile profile =
        ProfileFile.builder().content(credentials).type(ProfileFile.Type.CREDENTIALS).build();
    String profileName = profile.profiles().keySet().stream().findFirst().orElse("default");
    return new AwsTarget(
        TargetKind.AWS_EMULATOR,
        Optional.of(endpoint),
        region,
        ProfileCredentialsProvider.builder().profileFile(profile).profileName(profileName).build(),
        Optional.of(TrustStores.fromPem(ca)),
        connectHost);
  }
}
