package dev.keyedge.infra.stacks;

import dev.keyedge.infra.config.InfraConfig;
import java.util.List;
import software.amazon.awscdk.IStackSynthesizer;
import software.amazon.awscdk.services.ec2.GatewayVpcEndpointAwsService;
import software.amazon.awscdk.services.ec2.GatewayVpcEndpointOptions;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecs.CapacityProviderStrategy;
import software.amazon.awscdk.services.ecs.CloudMapNamespaceOptions;
import software.amazon.awscdk.services.ecs.Cluster;
import software.constructs.Construct;

/**
 * VPC, ECS cluster, Cloud Map namespace, capacity providers (FARGATE base 1, FARGATE_SPOT weight
 * 4). Local: public subnets only, no NAT (the emulators route nothing), DynamoDB gateway endpoint;
 * prodlike: private subnets with egress through a NAT gateway.
 */
public final class ClusterStack extends KeyedgeStack {
  private final Vpc vpc;
  private final Cluster cluster;

  public ClusterStack(Construct scope, String id, InfraConfig cfg, IStackSynthesizer synth) {
    super(scope, id, cfg, synth, "network and ECS cluster");
    var s = cfg.spec();
    int nat = s.num("cluster.natGateways", cfg.mode());
    var subnets =
        cfg.prodlike()
            ? List.of(
                SubnetConfiguration.builder()
                    .name("public")
                    .subnetType(SubnetType.PUBLIC)
                    .cidrMask(24)
                    .build(),
                SubnetConfiguration.builder()
                    .name("private")
                    .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                    .cidrMask(22)
                    .build())
            : List.of(
                SubnetConfiguration.builder()
                    .name("public")
                    .subnetType(SubnetType.PUBLIC)
                    .cidrMask(24)
                    .build());
    int azs = s.num("cluster.maxAzs");
    vpc =
        Vpc.Builder.create(this, "Vpc")
            .ipAddresses(
                software.amazon.awscdk.services.ec2.IpAddresses.cidr(s.str("cluster.vpcCidr")))
            // explicit AZs: no context lookup, the same template on every machine and in both CDK
            // apps
            .availabilityZones(
                java.util.stream.IntStream.range(0, azs)
                    .mapToObj(i -> cfg.region() + (char) ('a' + i))
                    .toList())
            .natGateways(nat)
            .subnetConfiguration(subnets)
            .build();
    if (cfg.prodlike()) {
      vpc.addFlowLog("FlowLog"); // to CloudWatch Logs; local mode acknowledges VPC7 (spec)
    }
    vpc.addGatewayEndpoint(
        "Dynamo",
        GatewayVpcEndpointOptions.builder().service(GatewayVpcEndpointAwsService.DYNAMODB).build());
    cluster = cluster(vpc);
    suppressFromSpec();
  }

  /** ECS cluster with Cloud Map and the FARGATE / FARGATE_SPOT capacity provider strategy. */
  private Cluster cluster(Vpc network) {
    var s = cfg.spec();
    var built =
        Cluster.Builder.create(this, "Cluster")
            .vpc(network)
            .clusterName(s.str("names.cluster") + "-" + cfg.env())
            .enableFargateCapacityProviders(true)
            .containerInsightsV2(
                cfg.prodlike()
                    ? software.amazon.awscdk.services.ecs.ContainerInsights.ENABLED
                    : software.amazon.awscdk.services.ecs.ContainerInsights.DISABLED)
            .defaultCloudMapNamespace(
                CloudMapNamespaceOptions.builder().name(s.str("names.namespace")).build())
            .build();
    built.addDefaultCapacityProviderStrategy(
        List.of(
            CapacityProviderStrategy.builder()
                .capacityProvider("FARGATE")
                .base(s.num("cluster.capacity.fargate.base"))
                .weight(s.num("cluster.capacity.fargate.weight"))
                .build(),
            CapacityProviderStrategy.builder()
                .capacityProvider("FARGATE_SPOT")
                .base(s.num("cluster.capacity.fargateSpot.base"))
                .weight(s.num("cluster.capacity.fargateSpot.weight"))
                .build()));
    return built;
  }

  public Vpc vpc() {
    return vpc;
  }

  public Cluster cluster() {
    return cluster;
  }
}
