"""Curate tracing-agent output into committed reachability metadata (Phase 010).

    ./gradlew --no-configuration-cache :services:<svc>:test -Pagent      (tests on the GraalVM JDK with the agent)
    python3 scripts/native_metadata.py <svc> <package-prefix>...

Review rule (Phase 010 plan): keep only entries for libraries WITHOUT published metadata (the GraalVM
reachability-metadata repository covers the rest) and nothing for our own or test classes. The
prefixes name the libraries kept; everything else in the agent output is dropped and counted.
Writes services/<svc>/src/main/resources/META-INF/native-image/dev.keyedge/<svc>/reachability-metadata.json
and prints what changed against the committed file.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def keep(entry: object, prefixes: tuple[str, ...]) -> bool:
    text = json.dumps(entry)
    return any(f'"{p}' in text for p in prefixes)


def main() -> int:
    svc, prefixes = sys.argv[1], tuple(sys.argv[2:])
    sessions = sorted((ROOT / "services" / svc / "build/native/agent-output/test").glob("*/reachability-metadata.json")) + sorted((ROOT / "services" / svc / "build/native/agent-output/testAws").glob("*/reachability-metadata.json"))
    if not sessions:
        print("no agent output: run the tests with -Pagent first", file=sys.stderr)
        return 1
    merged: dict[str, list] = {}
    dropped = 0
    for session in sessions:
        for section, entries in json.loads(session.read_text()).items():
            if not isinstance(entries, list):
                continue
            for e in entries:
                if keep(e, prefixes):
                    if e not in merged.setdefault(section, []):
                        merged[section].append(e)
                else:
                    dropped += 1
    for entries in merged.values():
        entries.sort(key=lambda e: json.dumps(e, sort_keys=True))
    out = ROOT / "services" / svc / "src/main/resources/META-INF/native-image/dev.keyedge" / svc / "reachability-metadata.json"
    before = json.loads(out.read_text()) if out.exists() else {}
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(merged, indent=2) + "\n")
    for section, entries in merged.items():
        new = [e for e in entries if e not in before.get(section, [])]
        print(f"{section}: {len(entries)} kept ({len(new)} new)")
        for e in new:
            print("  +", e.get("type", e) if isinstance(e, dict) else e)
    print(f"dropped {dropped} entries (covered by published metadata, Micronaut, or test-only)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
