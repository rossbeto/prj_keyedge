package dev.keyedge.infra.constructs;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationTargetGroup;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.elasticloadbalancingv2.TargetType;
import software.constructs.Construct;

/**
 * Target group for the edge sidecar: IP targets on the edge port, readiness health check, and a
 * deregistration delay shorter than the edge's drain window (D-29: readiness DOWN → drain → stop).
 */
public final class DrainingTargetGroup extends Construct {
  private final ApplicationTargetGroup group;

  /** Port, health path, drain delay. */
  public record Props(IVpc vpc, int port, String healthCheckPath, int deregistrationDelaySeconds) {}

  public DrainingTargetGroup(Construct scope, String id, Props p) {
    super(scope, id);
    group =
        ApplicationTargetGroup.Builder.create(this, "Group")
            .vpc(p.vpc())
            .port(p.port())
            .protocol(ApplicationProtocol.HTTP)
            .targetType(TargetType.IP)
            .deregistrationDelay(Duration.seconds(p.deregistrationDelaySeconds()))
            .healthCheck(
                HealthCheck.builder()
                    .path(p.healthCheckPath())
                    .healthyHttpCodes("200")
                    .interval(Duration.seconds(10))
                    .healthyThresholdCount(2)
                    .unhealthyThresholdCount(2)
                    .build())
            .build();
  }

  public ApplicationTargetGroup group() {
    return group;
  }
}
