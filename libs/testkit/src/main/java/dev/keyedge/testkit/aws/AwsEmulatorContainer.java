package dev.keyedge.testkit.aws;

import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.sun.security.auth.module.UnixSystem;
import dev.keyedge.awstarget.AwsTarget;
import dev.keyedge.awstarget.AwsTargets;
import dev.keyedge.awstarget.TrustStores;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.regions.Region;

/**
 * AWS Emulator in a container for tests, built from the latest package ({@code make emu-image}).
 * Port {@value #PORT} on both sides, on 127.0.0.1 only (the emulator writes its port into its
 * per-run files), so tests can run while the dev emulator holds 15297. Hardened like the compose
 * service: the user's UID, read-only root filesystem, no capabilities, telemetry off. Readiness is
 * probed over HTTPS trusting this run's CA: TLS verification is never turned off.
 */
public final class AwsEmulatorContainer extends GenericContainer<AwsEmulatorContainer> {
  /** Host and container port of the test emulator. */
  public static final int PORT = 15298;

  private final Path runDir;

  public AwsEmulatorContainer() {
    super(
        DockerImageName.parse(
            System.getProperty("keyedge.awsEmulator.image", "keyedge/aws-emulator:latest")));
    try {
      Path home = Files.createTempDirectory("keyedge-aws-emulator-");
      this.runDir = Files.createDirectories(home.resolve(String.valueOf(PORT)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var unix = new UnixSystem();
    String user = unix.getUid() + ":" + unix.getGid();
    withCommand("-p", String.valueOf(PORT));
    withEnv("EMULATOR_TELEMETRY", "off");
    withFileSystemBind(
        runDir.toString(), "/home/emulator/.aws/emulator/" + PORT, BindMode.READ_WRITE);
    withCreateContainerCmdModifier(cmd -> {
      cmd.withUser(user).withExposedPorts(ExposedPort.tcp(PORT));
      var host = cmd.getHostConfig();
      if (host != null) {
        host.withPortBindings(
                new PortBinding(
                    Ports.Binding.bindIpAndPort("127.0.0.1", PORT), ExposedPort.tcp(PORT)))
            .withReadonlyRootfs(true)
            .withCapDrop(Capability.ALL)
            .withSecurityOpts(List.of("no-new-privileges"));
      }
    });
    waitingFor(new HealthOverTls(runDir));
  }

  /** The target for this container's run. */
  public AwsTarget target() {
    return AwsTargets.awsEmulator(
        URI.create("https://127.0.0.1:" + PORT), Region.US_EAST_1, runDir, Optional.empty());
  }

  /** Waits for the per-run CA, then for {@code GET /__admin/health} over verified TLS. */
  private static final class HealthOverTls extends AbstractWaitStrategy {
    private final Path runDir;

    HealthOverTls(Path runDir) {
      this.runDir = runDir;
      withStartupTimeout(Duration.ofSeconds(60));
    }

    @Override
    protected void waitUntilReady() {
      Instant deadline = Instant.now().plus(startupTimeout);
      Path ca = runDir.resolve("ca.pem");
      while (Instant.now().isBefore(deadline)) {
        if (Files.isReadable(ca) && healthy(ca)) {
          return;
        }
        try {
          Thread.sleep(200);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while waiting for AWS Emulator", e);
        }
      }
      throw new IllegalStateException("AWS Emulator not healthy after " + startupTimeout);
    }

    private static boolean healthy(Path ca) {
      try {
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, TrustStores.trustManagers(TrustStores.fromPem(ca)), null);
        try (HttpClient client =
            HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(2)).build()) {
          var request =
              HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + PORT + "/__admin/health"))
                  .build();
          return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        }
      } catch (IOException | RuntimeException | GeneralSecurityException e) {
        return false;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }
}
