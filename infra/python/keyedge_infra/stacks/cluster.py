"""VPC, ECS cluster, Cloud Map namespace, capacity providers (FARGATE base 1, FARGATE_SPOT weight 4)."""

from aws_cdk import IStackSynthesizer
from aws_cdk import aws_ec2 as ec2
from aws_cdk import aws_ecs as ecs
from constructs import Construct

from keyedge_infra.config import InfraConfig
from keyedge_infra.stacks.base import KeyedgeStack


class ClusterStack(KeyedgeStack):
    def __init__(self, scope: Construct, stack_id: str, cfg: InfraConfig, synth: IStackSynthesizer) -> None:
        super().__init__(scope, stack_id, cfg, synth, "network and ECS cluster")
        s = cfg.spec
        public = ec2.SubnetConfiguration(name="public", subnet_type=ec2.SubnetType.PUBLIC, cidr_mask=24)
        subnets = [public]
        if cfg.prodlike:
            subnets.append(
                ec2.SubnetConfiguration(
                    name="private", subnet_type=ec2.SubnetType.PRIVATE_WITH_EGRESS, cidr_mask=22
                )
            )
        self.vpc = ec2.Vpc(
            self,
            "Vpc",
            ip_addresses=ec2.IpAddresses.cidr(s.str("cluster.vpcCidr")),
            # explicit AZs: no context lookup, the same template on every machine and in both CDK apps
            availability_zones=[cfg.region + chr(ord("a") + i) for i in range(s.num("cluster.maxAzs"))],
            nat_gateways=s.num("cluster.natGateways", cfg.mode),
            subnet_configuration=subnets,
        )
        if cfg.prodlike:
            self.vpc.add_flow_log("FlowLog")
        self.vpc.add_gateway_endpoint("Dynamo", service=ec2.GatewayVpcEndpointAwsService.DYNAMODB)
        self.cluster = self._cluster(self.vpc)
        self.suppress_from_spec()

    def _cluster(self, network: ec2.Vpc) -> ecs.Cluster:
        s = self.cfg.spec
        built = ecs.Cluster(
            self,
            "Cluster",
            vpc=network,
            cluster_name=f"{s.str('names.cluster')}-{self.cfg.env}",
            enable_fargate_capacity_providers=True,
            container_insights_v2=ecs.ContainerInsights.ENABLED
            if self.cfg.prodlike
            else ecs.ContainerInsights.DISABLED,
            default_cloud_map_namespace=ecs.CloudMapNamespaceOptions(name=s.str("names.namespace")),
        )
        built.add_default_capacity_provider_strategy(
            [
                ecs.CapacityProviderStrategy(
                    capacity_provider="FARGATE",
                    base=s.num("cluster.capacity.fargate.base"),
                    weight=s.num("cluster.capacity.fargate.weight"),
                ),
                ecs.CapacityProviderStrategy(
                    capacity_provider="FARGATE_SPOT",
                    base=s.num("cluster.capacity.fargateSpot.base"),
                    weight=s.num("cluster.capacity.fargateSpot.weight"),
                ),
            ]
        )
        return built
