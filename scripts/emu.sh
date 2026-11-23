#!/usr/bin/env bash
# Emulator lifecycle: up | down | health | assert-local | truststore  <target>   (masterplan §7.2, D-31, D-33)
set -euo pipefail
cd "$(dirname "$0")/.."
cmd=${1:?usage: emu.sh up|down|health|assert-local|truststore <target>}
target=${2:-${KEYEDGE_AWS_TARGET:-floci}}
emu_dir="$HOME/.aws/emulator/15297"
compose_files=(-f deploy/compose.yaml)
# Opt-in: AWS Emulator with the Docker socket (Phase 014 ECS probe); root-equivalent, never by default
[ "${KEYEDGE_EMU_DOCKER_SOCKET:-no}" = yes ] && compose_files+=(-f deploy/compose.aws-emulator-socket.yaml)
compose() { docker compose "${compose_files[@]}" "$@"; }

health() {
  case "$target" in
    floci) curl -sf -o /dev/null http://127.0.0.1:15566/_floci/info || curl -sf -o /dev/null http://127.0.0.1:15566/_localstack/health ;;
    floci-ecs) curl -sf -o /dev/null http://127.0.0.1:15567/_floci/info ;;
    aws-emulator) curl -sf --cacert "$emu_dir/ca.pem" https://127.0.0.1:15297/__admin/health >/dev/null ;;
    *) echo "no local emulator for target '$target'" >&2; return 2 ;;
  esac
}

case "$cmd" in
  up)
    case "$target" in
      floci) compose --profile floci up -d floci ;;
      floci-ecs)
        echo "WARNING: floci-ecs mounts the Docker socket: root-equivalent access to this host's Docker daemon."
        echo "         Opt-in only (compose profile 'ecs'); stop it with: scripts/emu.sh down floci-ecs"
        compose --profile ecs up -d floci-ecs ;;
      aws-emulator)
        mkdir -p "$emu_dir"     # must exist before the mount, or Docker creates it as root
        echo "AWS Emulator telemetry is ON by default upstream; this project starts it with EMULATOR_TELEMETRY=off."
        if [ "${KEYEDGE_EMU_DOCKER_SOCKET:-no}" = yes ]; then
          echo "WARNING: aws-emulator mounts the Docker socket: root-equivalent access to this host's Docker daemon."
          export KEYEDGE_DOCKER_GID; KEYEDGE_DOCKER_GID=$(stat -c %g /var/run/docker.sock)
        fi
        KEYEDGE_UID=$(id -u) KEYEDGE_GID=$(id -g) compose --profile aws-emulator up -d aws-emulator ;;
      *) echo "no local emulator for target '$target'" >&2; exit 2 ;;
    esac
    for _ in $(seq 60); do health && break; sleep 1; done
    health || { echo "emulator $target not healthy" >&2; exit 1; }
    [ "$target" = aws-emulator ] && "$0" truststore aws-emulator
    "$0" assert-local "$target"
    echo "emulator $target: healthy" ;;
  down)
    compose --profile floci --profile aws-emulator --profile ecs stop floci aws-emulator floci-ecs >/dev/null 2>&1 || true
    compose --profile floci --profile aws-emulator --profile ecs rm -f floci aws-emulator floci-ecs >/dev/null 2>&1 || true ;;
  health) health && echo "emulator $target: healthy" ;;
  assert-local)
    out=$( ( . <(scripts/aws-env.sh "$target") && aws sts get-caller-identity --query Account --output text ) )
    [ "$out" = "000000000000" ] || { echo "REFUSED: caller account is '$out', not the emulator's 000000000000" >&2; exit 1; }
    echo "assert-local $target: account 000000000000" ;;
  truststore)
    # The CA changes on every emulator start: rebuild the PKCS12 trust store for test JVMs only (never JAVA_TOOL_OPTIONS for Gradle).
    store="$emu_dir/truststore.p12"
    rm -f "$store"
    keytool -importcert -noprompt -alias aws-emulator -file "$emu_dir/ca.pem" -keystore "$store" -storetype PKCS12 -storepass changeit >/dev/null 2>&1
    chmod 600 "$store"
    echo "truststore: $store" ;;
  *) echo "unknown command $cmd" >&2; exit 2 ;;
esac
