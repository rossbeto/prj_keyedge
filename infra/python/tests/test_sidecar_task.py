"""The edge sidecar task, asserted the same way as Java's StacksTest#sidecarTaskDefinition."""

from typing import Any

from conftest import templates

from keyedge_infra.config import Mode


def test_sidecar_task_definition(mode: Mode) -> None:
    tasks = templates(mode)["KeyedgeQuotesService"].find_resources("AWS::ECS::TaskDefinition")
    assert len(tasks) == 1
    containers: list[dict[str, Any]] = next(iter(tasks.values()))["Properties"]["ContainerDefinitions"]
    by_name = {c["Name"]: c for c in containers}
    assert set(by_name) == {"edge", "quotes"}
    edge, app = by_name["edge"], by_name["quotes"]
    assert app["DependsOn"] == [{"Condition": "HEALTHY", "ContainerName": "edge"}]
    assert edge["StopTimeout"] >= app["StopTimeout"]
    for c in containers:
        assert c["ReadonlyRootFilesystem"] is True
        assert c["User"] == "10001"
        assert "Secrets" not in c
        names = {e["Name"] for e in c["Environment"]}
        assert {"KEYEDGE_CONTRACT_PATH", "KEYEDGE_AWS_TARGET", "MICRONAUT_SERVER_HOST"} <= names
        assert not any("SECRET" in n or "PASSWORD" in n for n in names)  # names only, never values
    assert {"KEYEDGE_APP_URL", "KEYEDGE_KEYS_URL"} <= {e["Name"] for e in edge["Environment"]}
    assert "KEYEDGE_QUOTES_TOKEN_URL" in {e["Name"] for e in app["Environment"]}
    assert "PortMappings" in edge and "HealthCheck" in edge
    assert "PortMappings" not in app
