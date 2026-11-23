"""One Fargate service: quotes with its edge sidecar, or keys alone (circuit breaker, 100/200)."""

from dataclasses import dataclass

from aws_cdk import Duration, IStackSynthesizer
from aws_cdk import aws_dynamodb as dynamodb
from aws_cdk import aws_ec2 as ec2
from aws_cdk import aws_ecs as ecs
from aws_cdk import aws_secretsmanager as sm
from constructs import Construct

from keyedge_infra.config import InfraConfig
from keyedge_infra.constructs import SidecarTaskDefinition, SidecarTaskDefinitionProps
from keyedge_infra.stacks.base import KeyedgeStack
from keyedge_infra.stacks.cluster import ClusterStack


@dataclass(frozen=True)
class ServiceProps:
    name: str
    with_sidecar: bool
    cluster: ClusterStack


class ServiceStack(KeyedgeStack):
    def __init__(
        self, scope: Construct, stack_id: str, cfg: InfraConfig, synth: IStackSynthesizer, p: ServiceProps
    ) -> None:
        super().__init__(scope, stack_id, cfg, synth, f"{p.name} service")
        s = cfg.spec
        env = dict(
            sorted(
                {  # ordered like Java's TreeMap
                    "KEYEDGE_CONTRACT_PATH": cfg.ssm_path(),
                    "KEYEDGE_AWS_TARGET": "aws" if cfg.prodlike else "floci",
                }.items()
            )
        )
        task = self._sidecar(p, env) if p.with_sidecar else self._single(p, env)
        for name in ("peppers", "callers", "principal"):
            sm.Secret.from_secret_name_v2(self, f"Secret-{name}", s.str(f"names.secrets.{name}")).grant_read(
                task.task_role
            )
        if not p.with_sidecar:
            table_name = s.str("names.table")
            table = dynamodb.Table.from_table_attributes(
                self, "Keys", table_name=table_name, global_indexes=[s.str("table.gsi1.name")]
            )
            table.grant_read_write_data(task.task_role)  # includes <table>/index/* for GSI1 queries
            self.acknowledge_granular(
                task.task_role,
                "AwsSolutions-IAM5",
                f"Resource::arn:<AWS::Partition>:dynamodb:{cfg.region}:{cfg.account}:table/{table_name}/index/*",
            )
        self.security_group = ec2.SecurityGroup(
            self, "Sg", vpc=p.cluster.vpc, description=f"{p.name} tasks", allow_all_outbound=True
        )
        self.service = ecs.FargateService(
            self,
            "Service",
            cluster=p.cluster.cluster,
            service_name=p.name,
            task_definition=task,
            desired_count=s.num("service.desiredCount", cfg.mode),
            min_healthy_percent=100,
            max_healthy_percent=200,
            circuit_breaker=ecs.DeploymentCircuitBreaker(enable=True, rollback=True),
            assign_public_ip=not cfg.prodlike,
            security_groups=[self.security_group],
            cloud_map_options=ecs.CloudMapOptions(name=p.name),
            health_check_grace_period=Duration.seconds(30) if p.with_sidecar else None,
        )
        self.suppress_from_spec()

    def _sidecar(self, p: ServiceProps, env: dict[str, str]) -> ecs.TaskDefinition:
        s = self.cfg.spec
        return SidecarTaskDefinition(
            self,
            "Task",
            SidecarTaskDefinitionProps(
                s.num("service.cpu"),
                s.num("service.memoryMiB"),
                s.str("images.edge"),
                s.str(f"images.{p.name}"),
                p.name,
                s.num("service.edgePort"),
                s.num("service.appPort"),
                s.num("service.edgeStopTimeoutSeconds"),
                s.num("service.appStopTimeoutSeconds"),
                s.str("service.user"),
                env,
            ),
        ).task

    def _single(self, p: ServiceProps, env: dict[str, str]) -> ecs.TaskDefinition:
        s = self.cfg.spec
        port = s.num(f"service.{p.name}Port")
        task = ecs.FargateTaskDefinition(
            self, "Task", cpu=s.num("service.cpu"), memory_limit_mib=s.num("service.memoryMiB")
        )
        task.add_container(
            p.name,
            image=ecs.ContainerImage.from_registry(s.str(f"images.{p.name}")),
            readonly_root_filesystem=True,
            user=s.str("service.user"),
            stop_timeout=Duration.seconds(s.num("service.appStopTimeoutSeconds")),
            environment=env,
            port_mappings=[ecs.PortMapping(container_port=port)],
            health_check=ecs.HealthCheck(
                command=["CMD", "/app/application", "--health-check", f"http://127.0.0.1:{port}/health"]
            ),
            logging=ecs.LogDrivers.aws_logs(stream_prefix=p.name),
        )
        return task

    def allow_from(self, other: ec2.SecurityGroup, port: int) -> None:
        self.security_group.add_ingress_rule(other, ec2.Port.tcp(port), f"from {other.node.id}")
