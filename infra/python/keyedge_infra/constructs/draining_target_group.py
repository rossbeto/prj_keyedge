"""Target group for the edge sidecars: IP targets, readiness check, drain delay shorter than the edge's."""

from dataclasses import dataclass

from aws_cdk import Duration
from aws_cdk import aws_ec2 as ec2
from aws_cdk import aws_elasticloadbalancingv2 as elbv2
from constructs import Construct


@dataclass(frozen=True)
class DrainingTargetGroupProps:
    vpc: ec2.IVpc
    port: int
    health_check_path: str
    deregistration_delay_seconds: int


class DrainingTargetGroup(Construct):
    def __init__(self, scope: Construct, construct_id: str, p: DrainingTargetGroupProps) -> None:
        super().__init__(scope, construct_id)
        self.group = elbv2.ApplicationTargetGroup(
            self,
            "Group",
            vpc=p.vpc,
            port=p.port,
            protocol=elbv2.ApplicationProtocol.HTTP,
            target_type=elbv2.TargetType.IP,
            deregistration_delay=Duration.seconds(p.deregistration_delay_seconds),
            health_check=elbv2.HealthCheck(
                path=p.health_check_path,
                healthy_http_codes="200",
                interval=Duration.seconds(10),
                healthy_threshold_count=2,
                unhealthy_threshold_count=2,
            ),
        )
