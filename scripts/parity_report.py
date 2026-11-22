#!/usr/bin/env python3
"""Build results/parity/<run>.json and docs/parity.md from the AWS contract suite's JUnit XML.

    python3 scripts/parity_report.py [--run RUN_ID]

Contract rows come from the tests (pass / fail / declared PARITY difference, plus PARITY-DATA lines the
tests print). Runtime rows (ECS, ALB, CloudFormation details) come from the Phase 001 observations in
KNOWN below until Phases 012-014 turn them into tests. The AWS column is AWS's documented behaviour, not a
test: no phase uses a real account.
"""
from __future__ import annotations

import argparse
import glob
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET

SUITES = {"floci": "FlociContractTest", "aws-emulator": "AwsEmulatorContractTest"}
CASES = {
    "stsReportsTheEmulatorAccount": ("STS GetCallerIdentity", "your account id"),
    "transactionCancellationReportsWhichConditionFailed": ("DynamoDB TransactWriteItems: CancellationReasons per item, nothing written", "same"),
    "optimisticLockAndAtomicCounter": ("DynamoDB conditional UpdateItem + ADD", "same"),
    "gsiQueryPaginatesWithExclusiveStartKey": ("DynamoDB GSI Query pagination", "same (GSIs are eventually consistent)"),
    "ttlIsEnabledAndExpiredItemsEventuallyGo": ("DynamoDB TTL deletes expired items", "within a few days; never rely on it"),
    "secretVersionsAndStages": ("Secrets Manager versions, AWSCURRENT / AWSPREVIOUS, by VersionId", "same"),
    "parametersByPathWithSecureStringsAndVersions": ("SSM String/SecureString, by path, version on overwrite", "same"),
    "kmsEncryptionContextMustMatch": ("KMS Encrypt/Decrypt with encryption context", "same"),
    "kmsHmacKeysGenerateAndVerifyMacs": ("KMS HMAC_256 GenerateMac/VerifyMac", "same"),
    "connectHostOverrideKeepsTlsVerificationOnTheEndpointName": ("TLS: connect to the container IP, verify the name localhost", "n/a (public CA)"),
}
# Observed in Phase 001 (plan changes/0001-phase001-emulator-feasibility.md); replaced by tests in Phases 012-014.
KNOWN = [
    ("P-02", "CloudFormation `TargetGroupAttributes` (deregistration delay) applied", "ignored (300 s default kept)", "applied", "applied", "Phase 001 smoke"),
    ("P-03", "CloudFormation task definition with container-level `DependsOn` + `Condition`", "deploys", "**rejected**: \"Condition START is not defined\"", "deploys", "Phase 001 smoke; reported upstream (U-06)"),
    ("P-04", "ECS: containers of one task share `localhost`", "**no** (each on the compose network)", "not run here (no Docker access; Phase 014 retests)", "yes (awsvpc)", "Phase 001 probe"),
    ("P-05", "ECS: container health checks run; `dependsOn: HEALTHY` waits", "**no** (health UNKNOWN, dependent never starts)", "compatibility check: FAILING trace", "yes", "Phase 001 probe + check"),
    ("P-06", "ECS runs task containers", "yes (Docker siblings, `ecs` profile)", "not without the Docker socket (Phase 014 retests with it)", "yes", "Phase 001 smoke"),
    ("P-07", "ALB forwards real HTTP to targets", "unverified (LB DNS → 127.0.0.1:80)", "unverified here; a corpus trace passes", "yes", "Phase 001; Phase 014 tests"),
    ("P-08", "GSI reads are eventually consistent", "immediately consistent (base-table reads, Floci docs)", "not measured", "eventually consistent", "Floci docs"),
    ("P-09", "KMS ciphertext usable outside the emulator", "no (internal envelope, Floci docs)", "not measured", "n/a", "Floci docs"),
    ("P-10", "IAM policies, KMS key policies, resource policies enforced", "no (optional IAM enforcement off)", "no (never denies its own keys)", "yes", "both emulators' docs; never claimed as tested"),
]


def results(target: str) -> tuple[dict[str, dict], dict[str, str]]:
    rows: dict[str, dict] = {}
    data: dict[str, str] = {}
    for xml in glob.glob(f"libs/awstarget/build/test-results/testAws/TEST-*{SUITES[target]}.xml"):
        root = ET.parse(xml).getroot()
        out = (root.findtext("system-out") or "") + "".join(tc.findtext("system-out") or "" for tc in root.iter("testcase"))
        for k, v in re.findall(r"PARITY-DATA (\w+)=(\S+)", out):
            data[k] = v
        for tc in root.iter("testcase"):
            name = tc.get("name", "").split("(")[0]
            if tc.find("failure") is not None or tc.find("error") is not None:
                rows[name] = {"status": "fail"}
            elif (sk := tc.find("skipped")) is not None:
                msg = sk.get("message") or ""
                rows[name] = {"status": "difference" if msg.startswith("PARITY") else "skipped", "note": msg}
            else:
                rows[name] = {"status": "pass"}
    return rows, data


def version(cmd: list[str]) -> str:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=60).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return "unknown"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default=datetime.now().strftime("%Y-%m-%d") + "-" + version(["git", "rev-parse", "--short", "HEAD"]))
    args = ap.parse_args()
    floci_rows, floci_data = results("floci")
    emu_rows, emu_data = results("aws-emulator")
    emu_ver = version(["docker", "run", "--rm", "--entrypoint", "aws-emulator", "keyedge/aws-emulator", "-v"]).splitlines()
    versions = {
        "floci": "2.5.0 (floci/floci@sha256:a0fbf944…)",
        "aws-emulator": (emu_ver[0].replace("Version: ", "") if emu_ver else "unknown") + (f" ({emu_ver[1].replace('Release date: ', '')})" if len(emu_ver) > 1 else ""),
    }
    doc = {
        "schema": 1, "run": args.run, "versions": versions,
        "contract": {name: {"floci": floci_rows.get(name, {"status": "not run"}), "aws-emulator": emu_rows.get(name, {"status": "not run"})} for name in CASES},
        "data": {"floci": floci_data, "aws-emulator": emu_data},
        "known": [dict(zip(["id", "behaviour", "floci", "aws-emulator", "aws", "source"], k)) for k in KNOWN],
        "recorded_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    Path("results/parity").mkdir(parents=True, exist_ok=True)
    Path(f"results/parity/{args.run}.json").write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")

    def cell(r: dict) -> str:
        return {"pass": "pass", "fail": "**FAIL**", "difference": "difference", "not run": "—"}.get(r["status"], r["status"])

    lines = [
        "# Emulator parity",
        "",
        f"Generated by `make emu-parity` from [results/parity/{args.run}.json](../results/parity/{args.run}.json).",
        f"Floci **{versions['floci']}** · AWS Emulator **{versions['aws-emulator']}**. The AWS column is AWS's",
        "documented behaviour, not a test: no part of this lab uses a real AWS account.",
        "",
        "## Contract suite (tested on every run)",
        "",
        "| Behaviour | Floci | AWS Emulator | AWS (documented) |",
        "|---|---|---|---|",
    ]
    for name, (label, aws) in CASES.items():
        lines.append(f"| {label} | {cell(doc['contract'][name]['floci'])} | {cell(doc['contract'][name]['aws-emulator'])} | {aws} |")
    f_ttl, e_ttl = floci_data.get("ttl_deleted_after_s"), emu_data.get("ttl_deleted_after_s")
    lines += [
        "",
        "## Measured differences",
        "",
        "| Id | Behaviour | Floci | AWS Emulator | AWS (documented) |",
        "|---|---|---|---|---|",
        f"| P-01 | Time until an expired TTL item is gone | {f_ttl or '—'} s | {e_ttl or '—'} s | up to days |",
        "",
        "## Observed differences (Phase 001; tests arrive in Phases 012–014)",
        "",
        "| Id | Behaviour | Floci | AWS Emulator | AWS (documented) | Source |",
        "|---|---|---|---|---|---|",
    ]
    lines += [f"| {k[0]} | {k[1]} | {k[2]} | {k[3]} | {k[4]} | {k[5]} |" for k in KNOWN]
    lines += ["", "What this means for the design: code never waits for TTL deletion (reads check `expiresAt`),",
              "infrastructure checks verify attributes rather than existence, and security properties that the",
              "emulators do not enforce (IAM, key policies) are checked for *shape* only (cdk-nag), never claimed as tested.", ""]
    Path("docs/parity.md").write_text("\n".join(lines), encoding="utf-8")
    fails = [n for n, r in doc["contract"].items() for t in r.values() if t["status"] == "fail"]
    print(f"parity: {args.run}; floci ttl={f_ttl}s, aws-emulator ttl={e_ttl}s; failures={fails or 'none'}")
    return 1 if fails else 0


if __name__ == "__main__":
    raise SystemExit(main())
