"""Every stack synthesises in both modes; cdk-nag reports no unacknowledged finding; the acknowledged rules
are exactly the Java app's (both are recorded in infra/java/src/test/resources/snapshots)."""

import json
import re
from collections import Counter
from pathlib import Path

from conftest import ROOT, full_app

from keyedge_infra.app import LOCAL_DEPLOYED
from keyedge_infra.config import Mode

STACKS = {
    "KeyedgeKms",
    "KeyedgeData",
    "KeyedgeConfig",
    "KeyedgeCluster",
    "KeyedgeKeysService",
    "KeyedgeQuotesService",
    "KeyedgeEdgeLb",
    "KeyedgeObservability",
    "KeyedgeCi",
}


def test_synth_with_nag_matches_the_java_acknowledgments(mode: Mode, tmp_path: Path) -> None:
    full_app(mode, str(tmp_path)).synth()  # raises on an unacknowledged validation error
    assert {p.name.removesuffix(".template.json") for p in tmp_path.glob("*.template.json")} == STACKS
    report = json.loads((tmp_path / "validation-report.json").read_text())
    plugins = report["pluginReports"]  # cdk-nag and CDK's own CloudFormation validation (cfn-lint)
    assert "AwsSolutions" in {p["pluginName"] for p in plugins}
    for plugin in plugins:
        assert plugin["conclusion"] == "success", plugin["pluginName"]
        assert plugin["violations"] == []
    counts = Counter(v["ruleName"] for p in plugins for v in p.get("suppressedViolations", []))
    recorded = (ROOT / f"infra/java/src/test/resources/snapshots/nag-acknowledged-{mode.key}.txt").read_text()
    expected = {line.rsplit(" ", 1)[0]: int(line.rsplit(" ", 1)[1]) for line in recorded.splitlines() if line}
    assert dict(counts) == expected


def test_local_deployed_stacks_have_no_parameters_or_assets(tmp_path: Path) -> None:
    full_app(Mode.LOCAL, str(tmp_path)).synth()
    for name in LOCAL_DEPLOYED:
        text = (tmp_path / f"{name}.template.json").read_text()
        assert "Parameters" not in json.loads(text)
        assert not re.search(r"cdk-hnb659fds-assets|AssetParameters", text)
