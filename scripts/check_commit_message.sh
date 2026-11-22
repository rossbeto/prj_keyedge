#!/usr/bin/env bash
# commit-msg hook (and the CI PR title/body check): the message is scanned with the same hashed
# term list as the files. FORBIDDEN_TERMS_HASHES overrides the list (tests use a synthetic one).
set -euo pipefail
SCRIPTS_DIR=$(cd "$(dirname "$0")" && pwd)
python3 - "$1" "$SCRIPTS_DIR" <<'PY'
import os
import sys
from pathlib import Path

sys.path.insert(0, sys.argv[2])
import forbidden_terms as ft

hashes_file = Path(os.environ.get("FORBIDDEN_TERMS_HASHES") or ft.HASHES)
hashes = ft.load(hashes_file.read_text(encoding="utf-8"))
hits = ft.scan([("commit message", Path(sys.argv[1]).read_text(encoding="utf-8"))], hashes)
for hit in hits:
    print(f"FORBIDDEN {hit}")
sys.exit(1 if hits else 0)
PY
