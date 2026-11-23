package dev.keyedge.infra;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.awstarget.ServiceContract;
import dev.keyedge.infra.config.Mode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

/** Fine-grained assertions on the synthesised templates (both modes). */
class StacksTest {
  @SuppressWarnings("unchecked")
  private static Map<String, Object> props(Map<String, Map<String, Object>> resources) {
    assertThat(resources).hasSize(1);
    return (Map<String, Object>) resources.values().iterator().next().get("Properties");
  }

  @ParameterizedTest
  @EnumSource(Mode.class)
  void keysTable(Mode mode) {
    Template t = Synth.of(mode, "KeyedgeData");
    t.hasResourceProperties(
        "AWS::DynamoDB::Table",
        Map.of(
            "TableName", "keyedge-keys",
            "BillingMode", "PAY_PER_REQUEST",
            "KeySchema",
                List.of(
                    Map.of("AttributeName", "PK", "KeyType", "HASH"),
                    Map.of("AttributeName", "SK", "KeyType", "RANGE")),
            "TimeToLiveSpecification", Map.of("AttributeName", "ttl", "Enabled", true),
            "GlobalSecondaryIndexes",
                List.of(
                    Match.objectLike(
                        Map.of(
                            "IndexName", "GSI1", "Projection", Map.of("ProjectionType", "ALL")))),
            "PointInTimeRecoverySpecification",
                Map.of("PointInTimeRecoveryEnabled", mode == Mode.PRODLIKE)));
    t.hasResource(
        "AWS::DynamoDB::Table",
        Map.of("DeletionPolicy", mode == Mode.PRODLIKE ? "Retain" : "Delete"));
  }

  @ParameterizedTest
  @EnumSource(Mode.class)
  @SuppressWarnings("unchecked")
  void sidecarTaskDefinition(Mode mode) {
    var task =
        props(Synth.of(mode, "KeyedgeQuotesService").findResources("AWS::ECS::TaskDefinition"));
    var containers = (List<Map<String, Object>>) task.get("ContainerDefinitions");
    assertThat(containers).hasSize(2);
    var byName = containers.stream().collect(Collectors.toMap(c -> (String) c.get("Name"), c -> c));
    var edge = byName.get("edge");
    var app = byName.get("quotes");
    assertThat((List<Object>) app.get("DependsOn"))
        .containsExactly(Map.of("Condition", "HEALTHY", "ContainerName", "edge"));
    assertThat(((Number) edge.get("StopTimeout")).intValue())
        .isGreaterThanOrEqualTo(((Number) app.get("StopTimeout")).intValue());
    for (var c : containers) {
      assertThat(c.get("ReadonlyRootFilesystem")).isEqualTo(true);
      assertThat(c.get("User")).isEqualTo("10001");
      assertThat(c)
          .doesNotContainKey("Secrets"); // names via the contract, values via the task role
      var env = (List<Map<String, Object>>) c.get("Environment");
      assertThat(env)
          .extracting(e -> e.get("Name"))
          .containsOnly("KEYEDGE_CONTRACT_PATH", "KEYEDGE_AWS_TARGET");
    }
    assertThat(edge).containsKey("PortMappings").containsKey("HealthCheck");
    assertThat(app).doesNotContainKey("PortMappings");
  }

  @ParameterizedTest
  @EnumSource(Mode.class)
  void serviceDeployments(Mode mode) {
    for (String stack : List.of("KeyedgeQuotesService", "KeyedgeKeysService")) {
      Synth.of(mode, stack)
          .hasResourceProperties(
              "AWS::ECS::Service",
              Map.of(
                  "DeploymentConfiguration",
                  Map.of(
                      "DeploymentCircuitBreaker", Map.of("Enable", true, "Rollback", true),
                      "MinimumHealthyPercent", 100,
                      "MaximumPercent", 200)));
    }
  }

  @ParameterizedTest
  @EnumSource(Mode.class)
  void drainingTargetGroup(Mode mode) {
    Synth.of(mode, "KeyedgeEdgeLb")
        .hasResourceProperties(
            "AWS::ElasticLoadBalancingV2::TargetGroup",
            Map.of(
                "HealthCheckPath", "/health/readiness",
                "TargetType", "ip",
                "TargetGroupAttributes",
                    Match.arrayWith(
                        List.of(
                            Map.of(
                                "Key",
                                "deregistration_delay.timeout_seconds",
                                "Value",
                                mode == Mode.PRODLIKE ? "30" : "5")))));
  }

  @ParameterizedTest
  @EnumSource(Mode.class)
  void clusterCapacityAndNat(Mode mode) {
    Template t = Synth.of(mode, "KeyedgeCluster");
    t.hasResourceProperties(
        "AWS::ECS::ClusterCapacityProviderAssociations",
        Map.of(
            "DefaultCapacityProviderStrategy",
            List.of(
                Map.of("Base", 1, "CapacityProvider", "FARGATE", "Weight", 1),
                Map.of("Base", 0, "CapacityProvider", "FARGATE_SPOT", "Weight", 4))));
    int nat = t.findResources("AWS::EC2::NatGateway").size();
    if (mode == Mode.LOCAL) {
      assertThat(nat).isZero();
    } else {
      assertThat(nat).isGreaterThanOrEqualTo(1);
      t.resourceCountIs("AWS::EC2::FlowLog", 1);
    }
  }

  @ParameterizedTest
  @EnumSource(Mode.class)
  @SuppressWarnings("unchecked")
  void noOpenIngressExceptTheProdlikeAlb(Mode mode) {
    for (var e : Synth.templates(mode).entrySet()) {
      var sgs = e.getValue().findResources("AWS::EC2::SecurityGroup");
      for (var sg : sgs.entrySet()) {
        var p = (Map<String, Object>) sg.getValue().get("Properties");
        var ingress = (List<Map<String, Object>>) p.getOrDefault("SecurityGroupIngress", List.of());
        for (var rule : ingress) {
          if ("0.0.0.0/0".equals(rule.get("CidrIp"))) {
            assertThat(mode).as(e.getKey() + "/" + sg.getKey()).isEqualTo(Mode.PRODLIKE);
            assertThat(e.getKey()).isEqualTo("KeyedgeEdgeLb");
            assertThat(rule.get("FromPort")).isIn(80, 443);
          }
        }
      }
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void everyNameTheServicesReadIsWrittenBySomeStack() {
    var written =
        Synth.templates(Mode.LOCAL).values().stream()
            .flatMap(t -> t.findResources("AWS::SSM::Parameter").values().stream())
            .map(r -> String.valueOf(((Map<String, Object>) r.get("Properties")).get("Name")))
            .map(n -> n.substring(n.lastIndexOf('/') + 1))
            .toList();
    assertThat(written)
        .containsAll(ServiceContract.READ_BY_SERVICES)
        .contains(ServiceContract.SOPS_KMS_ARN);
  }

  @Test
  void localDeployedStacksHaveNoParametersOrAssets() {
    for (String s : InfraApp.LOCAL_DEPLOYED) {
      var json = Synth.of(Mode.LOCAL, s).toJSON();
      assertThat(json).as(s).doesNotContainKey("Parameters");
      assertThat(json.toString()).as(s).doesNotContain("cdk-hnb659fds-assets", "AssetParameters");
    }
  }
}
