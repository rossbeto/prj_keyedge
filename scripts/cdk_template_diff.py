"""Prove two CDK apps synthesise the same infrastructure (Phase 013, D-47). Stdlib only.

    python3 scripts/cdk_template_diff.py <left cdk.out> <right cdk.out> [--mode local] [--report file]

For every *.template.json (the same file set is required on both sides): load, normalise, compare.
Normalisation, and nothing more:
  * drop resource Metadata keys "aws:cdk:path" and "aws:asset:*", the CDKMetadata resource and the
    CDKMetadataAvailable condition (both apps turn them off; dropped in case a default changes);
  * sort mapping keys (JSON key order is not meaningful);
  * sort lists only under order-insensitive keys (ORDER_INSENSITIVE); every other list keeps its order,
    because order can matter (container definitions, DependsOn, policy statements).
Exit 1 on any difference; each difference is printed as a JSON pointer.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

ORDER_INSENSITIVE = {"Tags", "ManagedPolicyArns", "SecurityGroupIngress", "DependsOn"}


def normalise(node: Any, key: str = "") -> Any:
    if isinstance(node, dict):
        out = {}
        for k in sorted(node):
            if k == "Metadata" and isinstance(node[k], dict):
                meta = {m: v for m, v in node[k].items() if m != "aws:cdk:path" and not m.startswith("aws:asset:")}
                if meta:
                    out[k] = normalise(meta, k)
                continue
            out[k] = normalise(node[k], k)
        return out
    if isinstance(node, list):
        items = [normalise(v, key) for v in node]
        if key in ORDER_INSENSITIVE:
            items.sort(key=lambda v: json.dumps(v, sort_keys=True))
        return items
    return node


def template(path: Path) -> Any:
    doc = json.loads(path.read_text())
    doc.get("Resources", {}).pop("CDKMetadata", None)
    doc.get("Conditions", {}).pop("CDKMetadataAvailable", None)
    if doc.get("Conditions") == {}:
        del doc["Conditions"]
    return normalise(doc)


def differences(left: Any, right: Any, pointer: str = "") -> list[str]:
    if type(left) is not type(right):
        return [f"{pointer or '/'}: {json.dumps(left)[:80]} != {json.dumps(right)[:80]}"]
    if isinstance(left, dict):
        out = []
        for k in sorted(set(left) | set(right)):
            p = f"{pointer}/{k.replace('~', '~0').replace('/', '~1')}"
            if k not in left:
                out.append(f"{p}: only on the right")
            elif k not in right:
                out.append(f"{p}: only on the left")
            else:
                out.extend(differences(left[k], right[k], p))
        return out
    if isinstance(left, list):
        if len(left) != len(right):
            return [f"{pointer}: {len(left)} items != {len(right)} items"]
        return [d for i, (a, b) in enumerate(zip(left, right, strict=True)) for d in differences(a, b, f"{pointer}/{i}")]
    return [] if left == right else [f"{pointer}: {json.dumps(left)[:80]} != {json.dumps(right)[:80]}"]


def compare(left_dir: Path, right_dir: Path, mode: str) -> list[dict[str, Any]]:
    left = {p.name for p in left_dir.glob("*.template.json")}
    right = {p.name for p in right_dir.glob("*.template.json")}
    if not left or left != right:
        raise SystemExit(f"template sets differ: only left {sorted(left - right)}, only right {sorted(right - left)}")
    rows = []
    for name in sorted(left):
        diffs = differences(template(left_dir / name), template(right_dir / name))
        rows.append({"stack": name.removesuffix(".template.json"), "mode": mode, "identical": not diffs,
                     "differences": diffs[:50]})
    return rows


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("left", type=Path)
    ap.add_argument("right", type=Path)
    ap.add_argument("--mode", default="local")
    ap.add_argument("--report", type=Path)
    args = ap.parse_args()
    rows = compare(args.left, args.right, args.mode)
    if args.report:
        existing = json.loads(args.report.read_text()) if args.report.exists() else []
        existing = [r for r in existing if r["mode"] != args.mode] + rows
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(existing, indent=2) + "\n")
    for r in rows:
        print(f"{r['mode']:9s} {r['stack']:22s} {'identical' if r['identical'] else 'DIFFERENT'}")
        for d in r["differences"]:
            print("    " + d)
    return 0 if all(r["identical"] for r in rows) else 1


if __name__ == "__main__":
    sys.exit(main())
