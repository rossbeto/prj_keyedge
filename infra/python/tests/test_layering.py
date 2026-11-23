"""Layering (import-linter contracts in pyproject.toml) and: no os.environ outside app.py."""

import ast
import shutil
import subprocess
from pathlib import Path

PKG = Path(__file__).resolve().parents[1] / "keyedge_infra"


def test_import_contracts() -> None:
    exe = shutil.which("lint-imports")
    assert exe is not None, "import-linter is not installed (uv sync --group dev)"
    result = subprocess.run([exe], cwd=PKG.parent, capture_output=True, text=True, check=False)  # noqa: S603
    assert result.returncode == 0, result.stdout + result.stderr


def test_only_the_app_reads_the_environment() -> None:
    offenders = []
    for path in PKG.rglob("*.py"):
        if path.name == "app.py":
            continue
        for node in ast.walk(ast.parse(path.read_text())):
            if isinstance(node, ast.Attribute) and node.attr in {"environ", "getenv"}:
                offenders.append(f"{path.relative_to(PKG)}:{node.lineno}")
    assert offenders == []
