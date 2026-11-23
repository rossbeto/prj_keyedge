"""The template diff tool: order-insensitive lists, JSON pointers, missing files."""

import json
import subprocess
import sys
from pathlib import Path

import pytest

TOOL = Path(__file__).resolve().parents[1] / "cdk_template_diff.py"
sys.path.insert(0, str(TOOL.parent))
import cdk_template_diff as diff  # noqa: E402


def write(d: Path, name: str, doc: dict[str, object]) -> None:
    d.mkdir(exist_ok=True)
    (d / f"{name}.template.json").write_text(json.dumps(doc))


def res(tags: list[dict[str, str]], size: int, path: str = "A/B") -> dict[str, object]:
    return {"Resources": {"T": {"Type": "AWS::X", "Properties": {"Tags": tags, "Size": size},
                                "Metadata": {"aws:cdk:path": path}}}}


def test_tag_order_and_cdk_path_are_ignored(tmp_path: Path) -> None:
    write(tmp_path / "l", "S", res([{"Key": "a", "Value": "1"}, {"Key": "b", "Value": "2"}], 1, "X/Y"))
    write(tmp_path / "r", "S", res([{"Key": "b", "Value": "2"}, {"Key": "a", "Value": "1"}], 1, "Z/W"))
    assert diff.compare(tmp_path / "l", tmp_path / "r", "local")[0]["identical"]


def test_a_changed_property_is_reported_with_its_pointer(tmp_path: Path) -> None:
    write(tmp_path / "l", "S", res([], 1))
    write(tmp_path / "r", "S", res([], 2))
    row = diff.compare(tmp_path / "l", tmp_path / "r", "local")[0]
    assert not row["identical"]
    assert row["differences"] == ["/Resources/T/Properties/Size: 1 != 2"]
    cli = subprocess.run([sys.executable, str(TOOL), str(tmp_path / "l"), str(tmp_path / "r")],
                         capture_output=True, text=True, check=False)
    assert cli.returncode == 1


def test_order_sensitive_lists_keep_their_order(tmp_path: Path) -> None:
    a = {"Resources": {"T": {"Type": "AWS::X", "Properties": {"ContainerDefinitions": [{"Name": "a"}, {"Name": "b"}]}}}}
    b = {"Resources": {"T": {"Type": "AWS::X", "Properties": {"ContainerDefinitions": [{"Name": "b"}, {"Name": "a"}]}}}}
    write(tmp_path / "l", "S", a)
    write(tmp_path / "r", "S", b)
    assert not diff.compare(tmp_path / "l", tmp_path / "r", "local")[0]["identical"]


def test_a_missing_template_is_an_error(tmp_path: Path) -> None:
    write(tmp_path / "l", "S", res([], 1))
    write(tmp_path / "l", "Extra", res([], 1))
    write(tmp_path / "r", "S", res([], 1))
    with pytest.raises(SystemExit, match="template sets differ"):
        diff.compare(tmp_path / "l", tmp_path / "r", "local")
