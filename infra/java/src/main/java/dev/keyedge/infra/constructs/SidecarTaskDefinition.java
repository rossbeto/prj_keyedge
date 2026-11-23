package dev.keyedge.infra.constructs;

import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.ecs.ContainerDefinition;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerDependency;
import software.amazon.awscdk.services.ecs.ContainerDependencyCondition;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.HealthCheck;
import software.amazon.awscdk.services.ecs.LogDrivers;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.constructs.Construct;

/**
 * One Fargate task = the app + its edge sidecar in the same network namespace (D-26, D-29). Only
 * the edge's port is mapped; the app starts after the edge is healthy and stops before it (edge
 * {@code stopTimeout} ≥ app), so the edge can drain. Read-only root filesystems, non-root user.
 * Secrets are read by the services through the SSM contract and the task role, not injected as env.
 */
public final class SidecarTaskDefinition extends Construct {
  private final FargateTaskDefinition task;
  private final ContainerDefinition edge;
  private final ContainerDefinition app;

  /** Sizes, images, ports and timeouts from the spec. */
  public record Props(
      int cpu,
      int memoryMiB,
      String edgeImage,
      String appImage,
      String appName,
      int edgePort,
      int appPort,
      int edgeStopTimeoutSeconds,
      int appStopTimeoutSeconds,
      String user,
      Map<String, String> edgeEnvironment,
      Map<String, String> appEnvironment) {}

  public SidecarTaskDefinition(Construct scope, String id, Props p) {
    super(scope, id);
    task =
        FargateTaskDefinition.Builder.create(this, "Task")
            .cpu(p.cpu())
            .memoryLimitMiB(p.memoryMiB())
            .build();
    edge =
        task.addContainer(
            "edge",
            container(p.edgeImage(), p, p.edgeStopTimeoutSeconds(), p.edgeEnvironment())
                .portMappings(List.of(PortMapping.builder().containerPort(p.edgePort()).build()))
                .healthCheck(
                    HealthCheck.builder()
                        .command(health(p.edgePort(), "/health/readiness"))
                        .interval(Duration.seconds(10))
                        .timeout(Duration.seconds(5))
                        .retries(3)
                        .startPeriod(Duration.seconds(10))
                        .build())
                .build());
    app =
        task.addContainer(
            p.appName(),
            container(p.appImage(), p, p.appStopTimeoutSeconds(), p.appEnvironment())
                .healthCheck(
                    HealthCheck.builder()
                        .command(health(p.appPort(), "/health"))
                        .interval(Duration.seconds(10))
                        .timeout(Duration.seconds(5))
                        .retries(3)
                        .build())
                .build());
    app.addContainerDependencies(
        ContainerDependency.builder()
            .container(edge)
            .condition(ContainerDependencyCondition.HEALTHY)
            .build());
  }

  private static ContainerDefinitionOptions.Builder container(
      String image, Props p, int stopSeconds, Map<String, String> environment) {
    return ContainerDefinitionOptions.builder()
        .image(ContainerImage.fromRegistry(image))
        .readonlyRootFilesystem(true)
        .user(p.user())
        .stopTimeout(Duration.seconds(stopSeconds))
        .environment(environment)
        .logging(
            LogDrivers.awsLogs(
                software.amazon.awscdk.services.ecs.AwsLogDriverProps.builder()
                    .streamPrefix(image.substring(image.lastIndexOf('/') + 1).replace(':', '-'))
                    .build()));
  }

  /**
   * The native binary probes itself (no shell or curl in distroless): Phase 010's --health-check.
   */
  static List<String> health(int port, String path) {
    return List.of("CMD", "/app/application", "--health-check", "http://127.0.0.1:" + port + path);
  }

  public FargateTaskDefinition task() {
    return task;
  }

  public ContainerDefinition edge() {
    return edge;
  }

  public ContainerDefinition app() {
    return app;
  }
}
