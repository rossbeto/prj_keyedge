package dev.keyedge.testkit.aws;

import dev.keyedge.awstarget.AwsTarget;
import dev.keyedge.awstarget.AwsTargets;
import io.floci.testcontainers.FlociContainer;
import java.net.URI;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.regions.Region;

/** Floci for tests: the same pinned image as compose, in-memory storage, no Docker socket. */
public final class FlociSupport {
  /** Same image (and digest) as deploy/compose.yaml. */
  public static final String IMAGE =
      "floci/floci:2.5.0@sha256:a0fbf944d7f338cc0856f1ef83831ea749ff2be71956eb7a7fce08fc21458e19";

  private FlociSupport() {}

  /** A new (not yet started) container. */
  public static FlociContainer container() {
    return new FlociContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("floci/floci"))
        .withEnv("FLOCI_SERVICES_CLOUDFORMATION_ALLOW_STUB_UNSUPPORTED_RESOURCE_TYPES", "false");
  }

  /** The target for a started container. */
  public static AwsTarget target(FlociContainer container) {
    return AwsTargets.floci(URI.create(container.getEndpoint()), Region.of(container.getRegion()));
  }
}
