#!/usr/bin/env bash
# Pre-tag checklist (Phase 016): every check prints PASS or FAIL; exit 1 if any failed. Never tags or pushes.
#   scripts/release_check.sh [TAG]            TAG defaults to v<version from gradle.properties>
#   RELEASE_ALLOW_DIRTY=1                      skip "working tree clean" (before the final commit)
#   RELEASE_ONLY="clean version changelog"     run a subset (the script's own test uses this)
#   RELEASE_ROOT=<dir>                         check another tree (the script's own test uses this)
set -uo pipefail
ROOT=${RELEASE_ROOT:-$(git rev-parse --show-toplevel)}
cd "$ROOT" || exit 2
version=$(sed -n 's/^keyedge.version=//p' gradle.properties 2>/dev/null)
tag=${1:-v$version}
only=${RELEASE_ONLY:-clean version changelog todo terms secrets results docs policy}
failed=0

check() { # name, command...
  local name=$1; shift
  case " $only " in *" $name "*) ;; *) return 0 ;; esac
  if out=$("$@" 2>&1); then
    printf 'PASS  %-10s\n' "$name"
  else
    printf 'FAIL  %-10s %s\n' "$name" "$(echo "$out" | tail -3 | tr '\n' ' ')"
    failed=1
  fi
}

clean() { [ "${RELEASE_ALLOW_DIRTY:-0}" = 1 ] || [ -z "$(git status --porcelain)" ] || { git status --short | head -5; return 1; }; }
version_matches() { [ -n "$version" ] && [ "$tag" = "v$version" ] || { echo "tag $tag != v$version (gradle.properties)"; return 1; }; }
changelog_has_tag() { grep -qE "^## \[?${tag#v}\]?" CHANGELOG.md || { echo "CHANGELOG.md has no '## ${tag#v}' section"; return 1; }; }
no_todo() { ! git grep -n 'TODO(release)' -- . ':!scripts/release_check.sh' ':!scripts/tests/test_release_check.sh'; }
results_valid() {
  python3 - <<'PY' || return 1
import json, pathlib, sys
bad = []
for p in pathlib.Path("results").rglob("*.json"):
    try:
        json.loads(p.read_text())
    except ValueError as e:
        bad.append(f"{p}: {e}")
print("\n".join(bad))
sys.exit(1 if bad else 0)
PY
  make -s bench-install > /dev/null && JAVA_HOME=$(cat bench/build/java-home) bench/build/install/bench/bin/bench validate
}

check clean     clean
check version   version_matches
check changelog changelog_has_tag
check todo      no_todo
check terms     python3 scripts/forbidden_terms.py check --history --all-revisions
check secrets   scripts/gitleaks.sh
check results   results_valid
check docs      make -s docs-check
check policy    make -s docs-policy
[ $failed = 0 ] && echo "release-check $tag: all PASS" || echo "release-check $tag: FAILED"
exit $failed
