"""Config: the shared spec (infra/spec/infra-spec.yaml) and one synth's settings. No CDK imports."""

from __future__ import annotations

import builtins
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Any

import yaml

DEFAULT_SPEC = Path("infra/spec/infra-spec.yaml")
_TOP_LEVEL = {
    "version",
    "envs",
    "names",
    "table",
    "secrets",
    "contract",
    "service",
    "cluster",
    "loadBalancer",
    "alarms",
    "ci",
    "nag",
    "images",
    "certificate",
}


class Mode(Enum):
    """``local``: deployable to the emulators; ``prodlike``: synthesised and tested only."""

    LOCAL = "local"
    PRODLIKE = "prodlike"

    @property
    def key(self) -> str:
        return self.value

    @staticmethod
    def parse(value: str) -> Mode:
        for m in Mode:
            if m.value == value:
                return m
        raise ValueError(f"mode must be local or prodlike, was '{value}'")


class InfraSpec:
    """The spec read once; values by dotted path, like the Java InfraSpec (same errors, same rules)."""

    def __init__(self, root: dict[str, Any]) -> None:
        self._root = root

    @staticmethod
    def load(path: Path = DEFAULT_SPEC) -> InfraSpec:
        doc = yaml.safe_load(path.read_text())
        if not isinstance(doc, dict):
            raise ValueError(f"{path} is not a mapping")
        unknown = set(doc) - _TOP_LEVEL
        if unknown:
            raise ValueError(f"unknown top-level key in {path}: {sorted(unknown)[0]}")
        if doc.get("version") != 1:
            raise ValueError("spec version must be 1")
        return InfraSpec(doc)

    def get(self, path: str) -> Any:
        cur: Any = self._root
        for part in path.split("."):
            if not isinstance(cur, dict) or part not in cur:
                raise ValueError(f"spec has no '{path}'")
            cur = cur[part]
        if cur is None:
            raise ValueError(f"spec value '{path}' is empty")
        return cur

    def str(self, path: builtins.str) -> builtins.str:
        return builtins.str(self.get(path))

    def num(self, path: builtins.str, mode: Mode | None = None) -> int:
        value = self.get(path if mode is None else f"{path}.{mode.key}")
        if not isinstance(value, int) or isinstance(value, bool):
            raise ValueError(f"spec value '{path}' is not a number")
        return value

    def list(self, path: builtins.str) -> builtins.list[builtins.str]:
        value = self.get(path)
        if not isinstance(value, list):
            raise ValueError(f"spec value '{path}' is not a list")
        return [builtins.str(v) for v in value]

    def map(self, path: builtins.str) -> dict[builtins.str, builtins.str]:
        value = self.get(path)
        if not isinstance(value, dict):
            raise ValueError(f"spec value '{path}' is not a mapping")
        return {
            builtins.str(k): builtins.str(v) for k, v in value.items()
        }  # YAML order, like Java's LinkedHashMap


@dataclass(frozen=True)
class InfraConfig:
    """What one synth is for: mode, environment name, account/region, and the spec."""

    mode: Mode
    env: str
    account: str
    region: str
    spec: InfraSpec

    @staticmethod
    def of(mode: Mode, env: str, spec: InfraSpec) -> InfraConfig:
        return InfraConfig(
            mode, env, spec.str(f"envs.{mode.key}.account"), spec.str(f"envs.{mode.key}.region"), spec
        )

    @property
    def prodlike(self) -> bool:
        return self.mode is Mode.PRODLIKE

    def ssm(self, contract_key: str) -> str:
        return f"{self.spec.str('names.ssmRoot')}/{self.env}/{self.spec.str('contract.' + contract_key)}"

    def ssm_path(self) -> str:
        return f"{self.spec.str('names.ssmRoot')}/{self.env}"
