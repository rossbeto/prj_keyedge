#!/usr/bin/env bash
# release_check.sh fails on crafted bad trees: dirty tree, tag != version, missing changelog entry,
# a TODO(release) marker; and passes on a good one. Works in a throwaway git repo under mktemp.
set -euo pipefail
unset RELEASE_ALLOW_DIRTY RELEASE_ONLY RELEASE_ROOT # a caller's settings (release-check runs this test) must not leak in
here=$(cd "$(dirname "$0")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -r -- "$work"' EXIT
mkdir "$work/repo" && cd "$work/repo"
git init -q . && git config user.email t@example.invalid && git config user.name t
printf 'keyedge.version=1.0.0\n' > gradle.properties
printf '# Changelog\n\n## [1.0.0] - 2026-11-24\n- first\n' > CHANGELOG.md
git add . && git commit -qm init
run() { RELEASE_ROOT=$work/repo RELEASE_ONLY="clean version changelog todo" "$here/scripts/release_check.sh" "$@"; }
expect() { # pattern message tag: the check's output must contain the pattern (its exit status is checked apart)
  local out; out=$(run "$3" || true)
  grep -q "$1" <<< "$out" || { echo "FAIL: $2"; echo "$out"; exit 1; }
}

run v1.0.0 > "$work/out.txt" || { cat "$work/out.txt"; echo "FAIL: good tree rejected"; exit 1; }
expect 'FAIL  version' "wrong tag accepted" v1.0.1
echo x > dirty.txt
expect 'FAIL  clean' "dirty tree accepted" v1.0.0
RELEASE_ALLOW_DIRTY=1 expect 'PASS  clean' "RELEASE_ALLOW_DIRTY ignored" v1.0.0
rm dirty.txt
printf '# Changelog\n' > CHANGELOG.md && git commit -qam "no entry"
expect 'FAIL  changelog' "missing changelog entry accepted" v1.0.0
git checkout -q HEAD~1 -- CHANGELOG.md && echo 'TODO(release): bump' > notes.md && git add . && git commit -qm todo
expect 'FAIL  todo' "TODO(release) marker accepted" v1.0.0
echo "ok: release_check.sh fails on each crafted bad tree and passes on a good one"
