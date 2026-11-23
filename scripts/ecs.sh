#!/usr/bin/env bash
# Emulated ECS (Phase 014): the CDK templates on Floci with real task containers (compose profile `ecs`,
# Docker socket: root-equivalent, opt-in only).
#   ecs.sh up       Floci-ECS + the data/config stacks + the support services in compose (keys, issuer,
#                   upstream-sim, toxiproxy); NOT edge/quotes: those run as the ECS task
#   ecs.sh deploy   KeyedgeCluster, KeyedgeEdgeLb, KeyedgeQuotesService from infra/out/<CDK_LANG>/local
#   ecs.sh status   service counts, task containers, their addresses
#   ecs.sh smoke    quotes-check through the task's edge (its address on the compose network)
#   ecs.sh start-order | drain   the experiments (results/ecs/*.json)
#   ecs.sh down     delete the ECS stacks (Floci stops the task containers), stop Floci-ECS
# Tasks are attached to the compose network (keyedge_default) and to a per-VPC network; containers of one
# task do NOT share localhost on Floci (parity P-04), hence KEYEDGE_APP_URL=ecs:quotes:15081 (ADR 0018).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
target=floci-ecs
lang=${CDK_LANG:-java}
cmd=${1:?up|deploy|status|down}
aws_() { ( . <(scripts/aws-env.sh "$target") && aws "$@" ); }

running_task() {
  aws_ ecs list-tasks --cluster keyedge-local --service-name quotes --desired-status RUNNING --query 'taskArns[0]' --output text
}

container_ip() { # $1 task arn, $2 container name: the address on the compose network
  # Floci names task containers floci-aws-ecs-<task id>-<container> (label io.floci.resource-id=<task id>)
  local name="floci-aws-ecs-${1##*/}-$2"
  docker inspect "$name" --format '{{with index .NetworkSettings.Networks "keyedge_default"}}{{.IPAddress}}{{end}}' 2> /dev/null
}

case "$cmd" in
  up)
    scripts/emu.sh up "$target"
    CDK_LANG=$lang scripts/cdk-deploy.sh deploy "$target"
    KEYEDGE_AWS_TARGET=floci KEYEDGE_CONTAINER_AWS_ENDPOINT=http://floci-ecs:4566 \
      docker compose -f deploy/compose.yaml up -d --wait --force-recreate issuer keys upstream-sim toxiproxy ;;
  deploy)
    CDK_LANG=$lang scripts/cdk-deploy.sh deploy "$target" KeyedgeCluster KeyedgeEdgeLb KeyedgeQuotesService
    for _ in $(seq 1 60); do
      n=$(aws_ ecs describe-services --cluster keyedge-local --services quotes --query 'services[0].runningCount' --output text)
      [ "$n" = 1 ] && break; sleep 2
    done
    "$0" status ;;
  status)
    aws_ ecs describe-services --cluster keyedge-local --services quotes \
      --query 'services[0].{running:runningCount,desired:desiredCount,deployments:length(deployments)}' --output json
    task=$(running_task)
    echo "task: $task"
    [ "$task" != None ] || exit 1
    aws_ ecs describe-tasks --cluster keyedge-local --tasks "$task" \
      --query 'tasks[0].containers[].{name:name,status:lastStatus,health:healthStatus}' --output table
    for c in edge quotes; do echo "$c: $(container_ip "$task" "$c")"; done ;;
  edge-ip) container_ip "$(running_task)" edge ;;
  smoke) # the same checks as `make quotes-check`, but through the edge container of the ECS task
    ip=$(container_ip "$(running_task)" edge)
    [ -n "$ip" ] && [ "$ip" != None ] || { echo "no running edge container" >&2; exit 1; }
    EDGE_URL="http://$ip:15080" uv run -q python scripts/quotes_check.py ;;
  start-order) # task-start-order: does the app start only after the edge is HEALTHY (dependsOn)?
    task=$(running_task); id=${task##*/}
    mkdir -p results/ecs
    edge_ready=$(docker logs "floci-aws-ecs-$id-edge" 2>&1 | grep -m1 '"Startup completed' | python3 -c 'import json,sys;print(json.loads(sys.stdin.read())["ts"])')
    python3 - "$id" "$edge_ready" "$(docker inspect -f '{{.State.StartedAt}}' "floci-aws-ecs-$id-edge")" \
      "$(docker inspect -f '{{.State.StartedAt}}' "floci-aws-ecs-$id-quotes")" \
      "$(docker inspect -f '{{if .Config.Healthcheck}}{{json .Config.Healthcheck.Test}}{{end}}' "floci-aws-ecs-$id-edge")" \
      "$(aws_ ecs describe-tasks --cluster keyedge-local --tasks "$task" --query 'tasks[0].containers[?name==`edge`].healthStatus | [0]' --output text)" <<'PY'
import json, sys
from datetime import datetime
task, edge_ready, edge_start, app_start, docker_hc, ecs_health = sys.argv[1:]
import re
def t(s):  # Docker gives nanoseconds, Python parses microseconds
    return datetime.fromisoformat(re.sub(r"(\.\d{6})\d*", r"\1", s).replace("Z", "+00:00"))
lead = (t(app_start) - t(edge_ready)).total_seconds()
out = {"experiment": "task-start-order", "target": "floci-ecs", "task": task,
       "edge_started": edge_start, "edge_ready": edge_ready, "app_started": app_start,
       "app_start_minus_edge_ready_s": round(lead, 3),
       "docker_healthcheck_on_edge": docker_hc or None, "ecs_edge_health_status": ecs_health,
       # timing alone cannot confirm a HEALTHY dependency (a fast edge is ready before any START-ordered app):
       # confirmed needs the health check to have run AND the app to start after the edge was ready
       "verdict": "confirmed" if lead >= 0 and ecs_health == "HEALTHY" else "refuted",
       "reason": ("app started after the edge was HEALTHY" if lead >= 0 and ecs_health == "HEALTHY" else
                  "dependsOn HEALTHY is not enforced: the task definition's health check is not run (no Docker "
                  "health check on the container, ECS health " + ecs_health + "); containers start in START order, "
                  "so the app can start before the edge is ready (first run 2026-11-23: app started 2.39 s before the "
                  "edge was ready)")}
json.dump(out, open("results/ecs/task-start-order.json", "w"), indent=2)
print(f"task-start-order: {out['verdict']} (app start - edge ready = {lead:.3f} s, ECS health {ecs_health})")
PY
    ;;
  drain) # drain-on-deploy through the ALB (127.0.0.1:15180): drain on (the template), then drain off
    mkdir -p results/ecs
    td=$(aws_ ecs describe-services --cluster keyedge-local --services quotes --query 'services[0].taskDefinition' --output text)
    runs=${DRAIN_RUNS:-3}
    rm -f results/ecs/drain-on-deploy-*.json
    for i in $(seq 1 "$runs"); do
      ( . <(scripts/aws-env.sh "$target") && uv run -q python scripts/ecs_drain.py --label "on-$i" --out "results/ecs/drain-on-deploy-on-$i.json" )
      sleep 25  # the replaced task is gone before the next run
    done
    # drain off: same task definition, edge without grace/drain/graceful shutdown (a new revision, outside CFN)
    off=$(aws_ ecs describe-task-definition --task-definition "$td" --query taskDefinition --output json | python3 -c '
import json, sys
d = json.load(sys.stdin)
keep = ["family", "taskRoleArn", "executionRoleArn", "networkMode", "containerDefinitions", "requiresCompatibilities", "cpu", "memory"]
d = {k: d[k] for k in keep if k in d}
for c in d["containerDefinitions"]:
    if c["name"] == "edge":
        c["environment"] = [e for e in c.get("environment", []) if not e["name"].startswith(("KEYEDGE_EDGE_DRAIN", "MICRONAUT_LIFECYCLE"))] + [
            {"name": "KEYEDGE_EDGE_DRAIN_GRACE", "value": "0s"}, {"name": "KEYEDGE_EDGE_DRAIN_TIMEOUT", "value": "0s"},
            {"name": "MICRONAUT_LIFECYCLE_GRACEFUL_SHUTDOWN_ENABLED", "value": "false"}]
        c["stopTimeout"] = 2
print(json.dumps(d))' > "${TMPDIR:-/tmp}/keyedge-td-off.json" && aws_ ecs register-task-definition --cli-input-json "file://${TMPDIR:-/tmp}/keyedge-td-off.json" --query taskDefinition.taskDefinitionArn --output text)
    aws_ ecs update-service --cluster keyedge-local --service quotes --task-definition "$off" > /dev/null
    for _ in $(seq 1 90); do # wait until only the drain-off task runs
      cur=$(aws_ ecs describe-services --cluster keyedge-local --services quotes --query 'services[0].[length(deployments),runningCount]' --output text)
      [ "$cur" = "$(printf '1\t1')" ] && break; sleep 2
    done
    sleep 5
    for i in $(seq 1 "$runs"); do
      ( . <(scripts/aws-env.sh "$target") && uv run -q python scripts/ecs_drain.py --label "off-$i" --out "results/ecs/drain-on-deploy-off-$i.json" )
      sleep 25
    done
    aws_ ecs update-service --cluster keyedge-local --service quotes --task-definition "$td" > /dev/null
    echo "restored $td"
    "$0" drain-verdict ;;
  drain-verdict) # summarise results/ecs/drain-on-deploy-{on,off}-N.json into results/ecs/drain-on-deploy.json
    python3 - <<'PY'
import glob, json
runs = {f: json.load(open(f)) for f in sorted(glob.glob("results/ecs/drain-on-deploy-o*-*.json"))}
on = [r for r in runs.values() if r["label"].startswith("on")]
off = [r for r in runs.values() if r["label"].startswith("off")]
valid = all(r["old_and_new_overlapped"] and r["tasks_replaced"] for r in runs.values())
on_fail = sum(r["replacement_failures"] for r in on)
off_fail = sum(r["replacement_failures"] for r in off)
if not valid:
    verdict, reason = "inconclusive", "old and new tasks did not overlap in every run"
elif off_fail == 0:
    # without drain nothing failed either: the emulator cannot show what drain prevents (failures with drain
    # on are then the emulator's own, e.g. a proxy stall while it launches the new task)
    verdict, reason = "inconclusive", ("no replacement failures without drain: the emulated ALB stops routing to the "
                                       "old task as soon as the new one starts, so the old task's shutdown is never "
                                       "observed; failures with drain on: " + str([r["replacement_failures"] for r in on]))
elif on_fail == 0:
    verdict, reason = "confirmed", "drain on: no replacement failures; drain off: some"
elif on_fail < off_fail:
    verdict, reason = "inconclusive", "fewer failures with drain on, but not zero"
else:
    verdict, reason = "refuted", "as many replacement failures with drain on as without"
out = {"experiment": "drain-on-deploy", "target": "floci-ecs", "runs": len(runs), "verdict": verdict, "reason": reason,
       "replacement_failures": {"on": [r["replacement_failures"] for r in on], "off": [r["replacement_failures"] for r in off]},
       "edge_shed": {"on": [r["edge_shed"] for r in on], "off": [r["edge_shed"] for r in off]},
       "requests_per_run": [r["requests"] for r in runs.values()], "files": list(runs)}
json.dump(out, open("results/ecs/drain-on-deploy.json", "w"), indent=2)
print(f"drain-on-deploy: {verdict} ({reason}); replacement failures on {out['replacement_failures']['on']} off {out['replacement_failures']['off']}")
PY
    ;;
  down)
    CDK_LANG=$lang scripts/cdk-deploy.sh destroy "$target" KeyedgeCluster KeyedgeEdgeLb KeyedgeQuotesService || true
    docker compose -f deploy/compose.yaml stop issuer keys upstream-sim toxiproxy > /dev/null 2>&1 || true
    left=$(docker ps -q --filter "label=io.floci.service=ecs" | wc -l)
    echo "task containers left: $left"
    scripts/emu.sh down "$target" ;;
  *) echo "unknown command $cmd" >&2; exit 2 ;;
esac
