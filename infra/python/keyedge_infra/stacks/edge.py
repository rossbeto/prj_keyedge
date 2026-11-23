"""The ALB in front of the edge sidecars, the alarms, and the CI identity."""

from aws_cdk import Duration, IStackSynthesizer, RemovalPolicy
from aws_cdk import aws_cloudwatch as cw
from aws_cdk import aws_ec2 as ec2
from aws_cdk import aws_elasticloadbalancingv2 as elbv2
from aws_cdk import aws_s3 as s3
from constructs import Construct

from keyedge_infra.config import InfraConfig
from keyedge_infra.constructs import (
    DrainingTargetGroup,
    DrainingTargetGroupProps,
    GithubOidcRole,
    SeverityAlarms,
)
from keyedge_infra.stacks.base import KeyedgeStack
from keyedge_infra.stacks.cluster import ClusterStack
from keyedge_infra.stacks.service import ServiceStack


class EdgeLbStack(KeyedgeStack):
    def __init__(
        self,
        scope: Construct,
        stack_id: str,
        cfg: InfraConfig,
        synth: IStackSynthesizer,
        cluster: ClusterStack,
        quotes: ServiceStack,
    ) -> None:
        super().__init__(scope, stack_id, cfg, synth, "load balancer for the edge sidecars")
        s = cfg.spec
        listener_port = s.num("loadBalancer.listenerPort", cfg.mode)
        alb_sg = self._alb_security_group(cluster, listener_port)
        self.alb = elbv2.ApplicationLoadBalancer(
            self, "Alb", vpc=cluster.vpc, internet_facing=cfg.prodlike, security_group=alb_sg
        )
        tg = DrainingTargetGroup(
            self,
            "Edge",
            DrainingTargetGroupProps(
                cluster.vpc,
                s.num("service.edgePort"),
                s.str("loadBalancer.healthCheckPath"),
                s.num("loadBalancer.deregistrationDelaySeconds", cfg.mode),
            ),
        ).group
        self._listener(tg, listener_port)
        if cfg.prodlike:
            self._redirect_and_access_logs()
        quotes.service.attach_to_application_target_group(tg)
        quotes.allow_from(alb_sg, s.num("service.edgePort"))
        self.suppress_from_spec()

    def _alb_security_group(self, cluster: ClusterStack, listener_port: int) -> ec2.SecurityGroup:
        sg = ec2.SecurityGroup(
            self, "AlbSg", vpc=cluster.vpc, description="edge ALB", allow_all_outbound=True
        )
        if self.cfg.prodlike:
            sg.add_ingress_rule(ec2.Peer.any_ipv4(), ec2.Port.tcp(443), "HTTPS from the internet")
            sg.add_ingress_rule(ec2.Peer.any_ipv4(), ec2.Port.tcp(80), "redirect to HTTPS")
        else:
            sg.add_ingress_rule(
                ec2.Peer.ipv4(self.cfg.spec.str("cluster.vpcCidr")),
                ec2.Port.tcp(listener_port),
                "inside the VPC",
            )
        return sg

    def _listener(self, tg: elbv2.ApplicationTargetGroup, listener_port: int) -> elbv2.ApplicationListener:
        if self.cfg.prodlike:
            return self.alb.add_listener(
                "Https",
                port=443,
                open=False,
                protocol=elbv2.ApplicationProtocol.HTTPS,
                certificates=[
                    elbv2.ListenerCertificate.from_arn(self.cfg.spec.str("certificate.prodlikeArn"))
                ],
                default_action=elbv2.ListenerAction.forward([tg]),
            )
        return self.alb.add_listener(
            "Http",
            port=listener_port,
            open=False,
            protocol=elbv2.ApplicationProtocol.HTTP,
            default_action=elbv2.ListenerAction.forward([tg]),
        )

    def _redirect_and_access_logs(self) -> None:
        self.alb.add_redirect()
        logs = s3.Bucket(
            self,
            "AccessLogs",
            encryption=s3.BucketEncryption.S3_MANAGED,
            block_public_access=s3.BlockPublicAccess.BLOCK_ALL,
            enforce_ssl=True,
            removal_policy=RemovalPolicy.RETAIN,
        )
        self.alb.log_access_logs(logs, "alb")
        self.acknowledge_on(logs, "AwsSolutions-S1")


class ObservabilityStack(KeyedgeStack):
    def __init__(
        self, scope: Construct, stack_id: str, cfg: InfraConfig, synth: IStackSynthesizer, lb: EdgeLbStack
    ) -> None:
        super().__init__(scope, stack_id, cfg, synth, "alarms")
        s = cfg.spec
        alarms = SeverityAlarms(self, "Severity", f"keyedge-{cfg.env}", s.list("names.alarmTopics"))
        metrics = lb.alb.metrics
        edge_5xx = cw.MathExpression(
            expression="100 * (elb + target) / MAX([requests, 1])",
            using_metrics=dict(
                sorted(
                    {  # ordered like Java's TreeMap
                        "elb": metrics.http_code_elb(elbv2.HttpCodeElb.ELB_5XX_COUNT),
                        "target": metrics.http_code_target(elbv2.HttpCodeTarget.TARGET_5XX_COUNT),
                        "requests": metrics.request_count(),
                    }.items()
                )
            ),
            period=Duration.minutes(1),
            label="5xx %",
        )
        alarms.alarm(
            "Edge5xx",
            "critical",
            edge_5xx,
            s.num("alarms.edge5xxPercent"),
            s.num("alarms.evaluationPeriods"),
            f"edge 5xx above {s.num('alarms.edge5xxPercent')} % (runbook §1)",
        )
        alarms.alarm(
            "EdgeLatency",
            "high",
            metrics.target_response_time().with_(statistic="p99", period=Duration.minutes(1)),
            0.5,
            s.num("alarms.evaluationPeriods"),
            "edge p99 above 500 ms",
        )
        self.suppress_from_spec()


class CiStack(KeyedgeStack):
    def __init__(self, scope: Construct, stack_id: str, cfg: InfraConfig, synth: IStackSynthesizer) -> None:
        super().__init__(scope, stack_id, cfg, synth, "CI identity")
        GithubOidcRole(self, "Github", cfg.spec.str("ci.githubRepo"), cfg.spec.str("ci.oidcThumbprint"))
        self.suppress_from_spec()
