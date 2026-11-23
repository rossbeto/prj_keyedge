"""One Fargate task = the app + its edge sidecar (D-26, D-29): edge healthy first, stops last."""

from dataclasses import dataclass

from aws_cdk import Duration
from aws_cdk import aws_ecs as ecs
from constructs import Construct


@dataclass(frozen=True)
class SidecarTaskDefinitionProps:
    cpu: int
    memory_mib: int
    edge_image: str
    app_image: str
    app_name: str
    edge_port: int
    app_port: int
    edge_stop_timeout_seconds: int
    app_stop_timeout_seconds: int
    user: str
    environment: dict[str, str]


def health(port: int, path: str) -> list[str]:
    """The native binary probes itself (no shell or curl in distroless): Phase 010's --health-check."""
    return ["CMD", "/app/application", "--health-check", f"http://127.0.0.1:{port}{path}"]


def _common(image: str, p: SidecarTaskDefinitionProps, stop_seconds: int) -> dict[str, object]:
    return {
        "image": ecs.ContainerImage.from_registry(image),
        "readonly_root_filesystem": True,
        "user": p.user,
        "stop_timeout": Duration.seconds(stop_seconds),
        "environment": p.environment,
        "logging": ecs.LogDrivers.aws_logs(stream_prefix=image[image.rfind("/") + 1 :].replace(":", "-")),
    }


class SidecarTaskDefinition(Construct):
    def __init__(self, scope: Construct, construct_id: str, p: SidecarTaskDefinitionProps) -> None:
        super().__init__(scope, construct_id)
        self.task = ecs.FargateTaskDefinition(self, "Task", cpu=p.cpu, memory_limit_mib=p.memory_mib)
        self.edge = self.task.add_container(
            "edge",
            **_common(p.edge_image, p, p.edge_stop_timeout_seconds),  # type: ignore[arg-type]
            port_mappings=[ecs.PortMapping(container_port=p.edge_port)],
            health_check=ecs.HealthCheck(
                command=health(p.edge_port, "/health/readiness"),
                interval=Duration.seconds(10),
                timeout=Duration.seconds(5),
                retries=3,
                start_period=Duration.seconds(10),
            ),
        )
        self.app = self.task.add_container(
            p.app_name,
            **_common(p.app_image, p, p.app_stop_timeout_seconds),  # type: ignore[arg-type]
            health_check=ecs.HealthCheck(
                command=health(p.app_port, "/health"),
                interval=Duration.seconds(10),
                timeout=Duration.seconds(5),
                retries=3,
            ),
        )
        self.app.add_container_dependencies(
            ecs.ContainerDependency(container=self.edge, condition=ecs.ContainerDependencyCondition.HEALTHY)
        )
