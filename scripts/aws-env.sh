#!/usr/bin/env bash
# Print the environment for one AWS target (masterplan §7.2). Use in a subshell:
#   ( . <(scripts/aws-env.sh floci) && aws sts get-caller-identity )
# Real-credential variables are always unset first, so nothing can reach a real account by accident.
set -euo pipefail
target=${1:-${KEYEDGE_AWS_TARGET:-floci}}
emu_dir="$HOME/.aws/emulator/15297"
cat <<UNSET
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_PROFILE AWS_DEFAULT_PROFILE \\
  AWS_WEB_IDENTITY_TOKEN_FILE AWS_ROLE_ARN AWS_ROLE_SESSION_NAME AWS_CONTAINER_CREDENTIALS_RELATIVE_URI \\
  AWS_CONTAINER_CREDENTIALS_FULL_URI AWS_CONTAINER_AUTHORIZATION_TOKEN AWS_CA_BUNDLE \\
  AWS_SHARED_CREDENTIALS_FILE AWS_CONFIG_FILE AWS_ENDPOINT_URL
export AWS_REGION=us-east-1 AWS_DEFAULT_REGION=us-east-1 AWS_EC2_METADATA_DISABLED=true AWS_PAGER=""
UNSET
case "$target" in
  floci)
    echo 'export AWS_ENDPOINT_URL=http://127.0.0.1:15566'
    echo 'export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test'
    ;;
  aws-emulator)
    echo "export AWS_ENDPOINT_URL=https://127.0.0.1:15297"
    echo "export AWS_SHARED_CREDENTIALS_FILE=$emu_dir/emulator_credentials"
    echo "export AWS_CONFIG_FILE=$emu_dir/config"
    # The generated config's ca_bundle is a container path; the host path is passed explicitly.
    echo "export AWS_CA_BUNDLE=$emu_dir/ca.pem"
    ;;
  aws)
    if [ "${KEYEDGE_ALLOW_REAL_AWS:-}" != "yes" ]; then
      echo "echo 'refused: real AWS requires KEYEDGE_ALLOW_REAL_AWS=yes' >&2; false"
    else
      echo "unset AWS_ENDPOINT_URL"
    fi
    ;;
  *) echo "echo 'unknown target: $target (floci | aws-emulator | aws)' >&2; false" ;;
esac
