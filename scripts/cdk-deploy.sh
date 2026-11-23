#!/usr/bin/env bash
# Deploy synthesised CDK templates to a LOCAL emulator (Phase 012, D-42): no bootstrap, no assets,
# plain `aws cloudformation deploy`, then scripts/cdk-verify.sh checks every resource with the service's
# own describe API (a stubbed resource fails). Real AWS is refused unless KEYEDGE_ALLOW_REAL_AWS=yes (D-33).
#   scripts/cdk-deploy.sh deploy  <target> [stacks...]     (default: KeyedgeKms KeyedgeData KeyedgeConfig)
#   scripts/cdk-deploy.sh destroy <target> [stacks...]     (reverse order; emulators only)
#   scripts/cdk-deploy.sh parity  <target>                 deploy every local stack, record results/parity/cdk-<target>.json
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
cmd=${1:?deploy|destroy|parity}; target=${2:?target}; shift 2
lang=${CDK_LANG:-java}
out="infra/out/$lang/local"
stacks=("$@"); [ ${#stacks[@]} -gt 0 ] || stacks=(KeyedgeKms KeyedgeData KeyedgeConfig)
if [ "$target" = aws ]; then
  echo "refused: deploying to real AWS requires KEYEDGE_ALLOW_REAL_AWS=yes and is the user's job (USER-ACTIONS U-04)" >&2
  [ "${KEYEDGE_ALLOW_REAL_AWS:-}" = yes ] || exit 3
  echo "refused: this script deploys bootstrap-less LOCAL templates only; use the CDK CLI with a bootstrapped account" >&2
  exit 3
fi
scripts/emu.sh assert-local "$target" > /dev/null   # account 000000000000 at a local endpoint, or stop
[ -f "$out/manifest.json" ] || { echo "no synthesised templates in $out: make cdk-synth CDK_LANG=$lang" >&2; exit 2; }
# shellcheck disable=SC1090
. <(scripts/aws-env.sh "$target")
deploy_one() {
  local s=$1
  aws cloudformation deploy --stack-name "$s" --template-file "$out/$s.template.json" \
    --no-fail-on-empty-changeset --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM > /dev/null
  scripts/cdk-verify.sh "$s"
}
case "$cmd" in
  deploy)
    for s in "${stacks[@]}"; do echo "deploy $s -> $target"; deploy_one "$s"; done ;;
  destroy)
    for (( i=${#stacks[@]}-1; i>=0; i-- )); do
      s=${stacks[$i]}; echo "delete $s"
      aws cloudformation delete-stack --stack-name "$s"
      aws cloudformation wait stack-delete-complete --stack-name "$s" || true
    done ;;
  parity)
    mkdir -p results/parity
    all=$(python3 -c "import json;m=json.load(open('$out/manifest.json'));print(' '.join(k for k,v in m['artifacts'].items() if v['type']=='aws:cloudformation:stack'))")
    report="results/parity/cdk-$target-$lang.json"; echo "[" > "$report"; first=1
    for s in $all; do
      if log=$(deploy_one "$s" 2>&1); then status=ok; else status=failed; fi
      detail=$(echo "$log" | tail -3 | tr '"\n' "' " | cut -c1-400)
      [ $first -eq 1 ] || echo "," >> "$report"; first=0
      printf '  {"stack":"%s","status":"%s","detail":"%s"}' "$s" "$status" "$detail" >> "$report"
      echo "$s: $status"
    done
    printf '\n]\n' >> "$report" ;;
  *) echo "unknown command $cmd" >&2; exit 2 ;;
esac
