"""The shared spec validates against its JSON schema, and both loaders reject unknown keys."""

from pathlib import Path

import jsonschema
import pytest
import yaml
from conftest import ROOT

from keyedge_infra.config import InfraConfig, InfraSpec, Mode

SPEC = ROOT / "infra/spec/infra-spec.yaml"
SCHEMA = ROOT / "infra/spec/infra-spec.schema.json"


def test_spec_matches_schema() -> None:
    jsonschema.validate(yaml.safe_load(SPEC.read_text()), yaml.safe_load(SCHEMA.read_text()))


def test_unknown_keys_are_rejected_by_schema_and_loader(tmp_path: Path) -> None:
    doc = yaml.safe_load(SPEC.read_text())
    doc["extra"] = 1
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(doc, yaml.safe_load(SCHEMA.read_text()))
    bad = tmp_path / "spec.yaml"
    bad.write_text(yaml.safe_dump(doc))
    with pytest.raises(ValueError, match="unknown top-level key"):
        InfraSpec.load(bad)


def test_accessors() -> None:
    spec = InfraSpec.load(SPEC)
    cfg = InfraConfig.of(Mode.LOCAL, "local", spec)
    assert cfg.account == "000000000000"
    assert cfg.ssm("tableName") == "/keyedge/local/table-name"
    assert spec.num("loadBalancer.deregistrationDelaySeconds", Mode.PRODLIKE) == 30
    with pytest.raises(ValueError, match="spec has no"):
        spec.get("names.nope")
    with pytest.raises(ValueError, match="not a number"):
        spec.num("names.table")
    with pytest.raises(ValueError, match="mode must be"):
        Mode.parse("prod")
