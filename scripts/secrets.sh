#!/usr/bin/env bash
# SOPS with two recipients (Phase 012, D-44): an age key (outside the repo, survives everything) and the
# emulator's KMS key from the KmsStack (gone on every emulator restart: `rekey` re-adds the new one).
# Plaintext never touches the disk: it is generated or decrypted into a pipe.
#   secrets.sh age-key                      create ~/.config/keyedge/age.key once (0600)
#   secrets.sh encrypt <target>             new secrets/<target>/local.enc.yaml (fresh random values)
#   secrets.sh rekey <target>               point the file's KMS recipient at the current KMS key (from SSM)
#   secrets.sh decrypt-check <target> [age|kms]   decrypt to a pipe and validate the keys (default: age)
#   secrets.sh sync <target>                put each document into Secrets Manager (overrides CDK-generated values)
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
SOPS=.tools/sops
AGE_KEY=${KEYEDGE_AGE_KEY:-$HOME/.config/keyedge/age.key}
cmd=${1:?usage: secrets.sh age-key|encrypt|rekey|decrypt-check|sync <target>}
target=${2:-floci}
file="secrets/$target/local.enc.yaml"
config=secrets/.sops.yaml
[ -x "$SOPS" ] || scripts/tools.sh > /dev/null

age_recipient() { grep -m1 '^# public key:' "$AGE_KEY" | awk '{print $4}'; }

kms_arn() { # the KmsStack writes it to the contract
  # shellcheck disable=SC1090
  ( . <(scripts/aws-env.sh "$target") && aws ssm get-parameter --name /keyedge/local/sops-kms-arn --query Parameter.Value --output text )
}

write_config() { # one creation rule per target: age + that target's current KMS key
  local arn=$1
  python3 - "$config" "$target" "$(age_recipient)" "$arn" <<'PY'
import re, sys
path, target, age, arn = sys.argv[1:]
try:
    text = open(path).read()
except FileNotFoundError:
    text = "# SOPS creation rules (make secrets-*). age: the developer key; kms: the emulator's KmsStack key.\ncreation_rules:\n"
# path_regex is matched relative to the directory of .sops.yaml (secrets/)
rule = f"  - path_regex: (^|/){target}/.*\\.enc\\.yaml$\n    age: {age}\n    kms: {arn}\n"
pattern = re.compile(rf"  - path_regex: \(\^\|/\){re.escape(target)}/.*?(?=\n  - |\Z)", re.S)
text = pattern.sub(rule.rstrip("\n"), text) if pattern.search(text) else text.rstrip("\n") + "\n" + rule
open(path, "w").write(text.rstrip("\n") + "\n")
PY
}

sops_env() { # KMS calls go to the emulator (sops uses the AWS SDK v2 environment)
  # shellcheck disable=SC1090
  . <(scripts/aws-env.sh "$target")
}

case "$cmd" in
  age-key)
    if [ -f "$AGE_KEY" ]; then echo "age key exists: $AGE_KEY"; exit 0; fi
    mkdir -p "$(dirname "$AGE_KEY")" && chmod 700 "$(dirname "$AGE_KEY")"
    .tools/age-keygen -o "$AGE_KEY" 2> /dev/null && chmod 600 "$AGE_KEY"
    echo "age key created: $AGE_KEY (public: $(age_recipient))" ;;
  encrypt)
    mkdir -p "secrets/$target"
    write_config "$(kms_arn)"
    ( sops_env
      python3 -c "
import base64, json, os
b = lambda: base64.b64encode(os.urandom(32)).decode()
print(f'peppers: {json.dumps({\"current\": 1, \"peppers\": {\"1\": b()}})}')
print(f'callers: {json.dumps({\"edge-quotes\": b()})}')
print(f'principal: {json.dumps({\"key\": b()})}')" \
      | "$SOPS" --config "$config" --encrypt --input-type yaml --output-type yaml \
          --filename-override "$file" /dev/stdin > "$file" )
    echo "encrypted $file (age + KMS)" ;;
  rekey)
    write_config "$(kms_arn)"
    ( sops_env; SOPS_AGE_KEY_FILE="$AGE_KEY" "$SOPS" --config "$config" updatekeys -y "$file" > /dev/null 2>&1 )
    echo "rekeyed $file to $(grep -A2 "$target/" "$config" | grep kms: | sed 's/.*key\//key\//')" ;;
  decrypt-check)
    via=${3:-age}
    ( sops_env
      if [ "$via" = kms ]; then unset SOPS_AGE_KEY_FILE; export SOPS_AGE_KEY_FILE=/nonexistent; else export SOPS_AGE_KEY_FILE="$AGE_KEY"; fi
      "$SOPS" --decrypt "$file" 2> /dev/null \
        | python3 -c "
import json, sys, yaml
d = yaml.safe_load(sys.stdin)
if d is None:
    sys.exit(1)  # sops could not decrypt with this recipient
assert set(d) == {'peppers', 'callers', 'principal'}, sorted(d)
assert d['peppers']['current'] == 1 and len(d['peppers']['peppers']['1']) == 44
print('decrypt-check ($via): OK, documents:', ', '.join(sorted(d)))" ) \
      || { echo "decrypt-check ($via): FAILED"; exit 1; } ;;
  sync)
    scripts/emu.sh assert-local "$target" > /dev/null
    ( sops_env
      SOPS_AGE_KEY_FILE="$AGE_KEY" "$SOPS" --decrypt "$file" | python3 -c "
import json, subprocess, sys, yaml
names = {'peppers': 'keyedge/peppers', 'callers': 'keyedge/callers', 'principal': 'keyedge/principal'}
for key, doc in yaml.safe_load(sys.stdin).items():
    subprocess.run(['aws', 'secretsmanager', 'put-secret-value', '--secret-id', names[key], '--secret-string', json.dumps(doc)],
                   check=True, stdout=subprocess.DEVNULL)
    print('synced', names[key])" )
    ;;
  *) echo "unknown command $cmd" >&2; exit 2 ;;
esac
