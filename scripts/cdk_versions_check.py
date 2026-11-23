"""The two CDK apps must run on the same library versions and context (Phase 013, D-47).

    python3 scripts/cdk_versions_check.py      -> exit 1 on any mismatch, before any template is compared
"""

import json
import re
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
catalog = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]
lock = tomllib.loads((ROOT / "infra/python/uv.lock").read_text())
locked = {p["name"]: p["version"] for p in lock["package"]}
pairs = {"aws-cdk-lib": catalog["cdk"], "cdk-nag": catalog["cdkNag"], "constructs": catalog["constructs"]}
ok = True
for name, java in pairs.items():
    py = locked.get(name)
    same = py == java
    ok &= same
    print(f"{name:12s} java {java:10s} python {py!s:10s} {'==' if same else '!= MISMATCH'}")
ctx = [json.loads((ROOT / f"infra/{lang}/cdk.json").read_text()).get("context") for lang in ("java", "python")]
same_ctx = ctx[0] == ctx[1]
ok &= same_ctx
print(f"cdk.json context blocks {'equal' if same_ctx else 'DIFFER'}")
cli = json.loads((ROOT / "infra/package.json").read_text())["devDependencies"]["aws-cdk"]
print(f"CDK CLI {cli} (one pin for both apps)")
if not re.fullmatch(r"\d+\.\d+\.\d+", cli):
    print("CLI must be pinned to an exact version")
    ok = False
sys.exit(0 if ok else 1)
