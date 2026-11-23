"""GitHub Actions policy (Phase 016, D-80): CI runs on pull requests, version tags and manual dispatch only (never on
a push to a branch); every `uses:` is pinned to a full commit SHA with a version comment; nothing is granted at the
top level (`permissions: {}`) and every job states its own permissions."""

import re
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = sorted((ROOT / ".github" / "workflows").glob("*.yml"))
USES = re.compile(r"^\s*-?\s*uses:\s*(\S+)(.*)$", re.MULTILINE)


def load(path: Path) -> dict:
    doc = yaml.safe_load(path.read_text())
    doc["on"] = doc.pop(True, doc.get("on"))  # YAML 1.1 reads the key `on` as True
    return doc


@pytest.mark.parametrize("path", WORKFLOWS, ids=lambda p: p.name)
def test_no_branch_push_trigger(path: Path) -> None:
    push = (load(path)["on"] or {}).get("push") if isinstance(load(path)["on"], dict) else None
    if push is not None:
        assert set(push) <= {"tags"}, f"{path.name}: push may only trigger on tags, got {sorted(push)}"


@pytest.mark.parametrize("path", WORKFLOWS, ids=lambda p: p.name)
def test_actions_pinned_by_sha_with_a_version_comment(path: Path) -> None:
    for ref, rest in USES.findall(path.read_text()):
        if ref.startswith(("./", "docker://")):
            continue
        assert re.fullmatch(r"[\w.-]+/[\w./-]+@[0-9a-f]{40}", ref), f"{path.name}: {ref} is not pinned to a SHA"
        assert re.search(r"#\s*v\d", rest), f"{path.name}: {ref} has no version comment"


@pytest.mark.parametrize("path", WORKFLOWS, ids=lambda p: p.name)
def test_least_privilege(path: Path) -> None:
    doc = load(path)
    assert doc.get("permissions") == {}, f"{path.name}: top-level permissions must be {{}}"
    for name, job in doc["jobs"].items():
        assert "permissions" in job, f"{path.name}: job {name} states no permissions"
        writes = [k for k, v in (job["permissions"] or {}).items() if v == "write"]
        assert writes in ([], ["contents"]), f"{path.name}: job {name} writes {writes}"
        if writes:
            assert path.name == "release.yml", f"{path.name}: only the release draft job may write"


def test_there_are_workflows() -> None:
    assert {p.name for p in WORKFLOWS} >= {"ci.yml", "aws-contract.yml", "e2e.yml", "security.yml", "release.yml"}
