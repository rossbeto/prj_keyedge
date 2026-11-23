"""One synth per mode for the whole run (jsii starts Node once); tests read the templates from it."""

import os
from pathlib import Path
from typing import Any

import pytest
from aws_cdk import App
from aws_cdk.assertions import Template

from keyedge_infra.app import build, new_app
from keyedge_infra.config import InfraConfig, InfraSpec, Mode

ROOT = Path(__file__).resolve().parents[3]
os.chdir(ROOT)  # the spec path is relative to the repository root, as in the Java app

_CACHE: dict[Mode, dict[str, Template]] = {}


def templates(mode: Mode) -> dict[str, Template]:
    if mode not in _CACHE:
        app = new_app({"mode": mode.key, "env": mode.key})
        stacks = build(app, InfraConfig.of(mode, mode.key, InfraSpec.load()))
        _CACHE[mode] = {s.stack_name: Template.from_stack(s) for s in stacks}
    return _CACHE[mode]


@pytest.fixture(params=list(Mode), ids=lambda m: m.key)
def mode(request: pytest.FixtureRequest) -> Mode:
    value: Mode = request.param
    return value


def full_app(mode: Mode, outdir: str) -> App:
    app = new_app({"mode": mode.key, "env": mode.key}, outdir)
    build(app, InfraConfig.of(mode, mode.key, InfraSpec.load()))
    return app


Json = dict[str, Any]
