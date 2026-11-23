"""drain-on-deploy (Phase 014, L3): constant-rate load through the emulated ALB while ECS replaces the task.

    ( . <(scripts/aws-env.sh floci-ecs) && uv run -q python scripts/ecs_drain.py --label on --out results/... )
Creates an API key, sends RATE requests/s for DURATION s to ALB_URL, and at REPLACE_AT s forces a new deployment
of the quotes service. Records every response (status or connection error) and the RUNNING task set once a
second: the verdict is only valid if old and new tasks overlapped (minimum healthy 100 %, maximum 200 %).
Never prints the key.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

ALB = os.environ.get("ALB_URL", "http://127.0.0.1:15180")
KEYS = os.environ.get("KEYS_URL", "http://127.0.0.1:15100")
ISSUER = os.environ.get("ISSUER_URL", "http://127.0.0.1:15110")
CLUSTER, SERVICE = "keyedge-local", "quotes"


def post(url: str, body: object, headers: dict[str, str]) -> dict:
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", **headers})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def api_key() -> str:
    tok = post(ISSUER + "/token", {"tenant": "acme", "scopes": ["keys:write"]}, {})["accessToken"]
    created = post(KEYS + "/v1/keys", {"name": "drain", "scopes": ["quotes:read"]},
                   {"Authorization": "Bearer " + tok, "Idempotency-Key": "drain-" + uuid.uuid4().hex[:8]})
    return created["key"]


def one(url: str, key: str) -> tuple[str, str]:
    """(status, detail): detail names who answered a failure (problem type or body start, Server header)."""
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + key})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            r.read()
            return str(r.status), ""
    except urllib.error.HTTPError as e:
        body = e.read()[:160].decode(errors="replace").replace("\n", " ")
        return str(e.code), f"server={e.headers.get('Server', '-')} body={body}"
    except Exception as e:  # noqa: BLE001 - every failure kind is a result here
        return "error:" + type(e).__name__, str(e)[:160]


def aws(*args: str) -> str:
    return subprocess.run(["aws", *args], check=True, capture_output=True, text=True).stdout.strip()


def running_tasks() -> list[str]:
    out = aws("ecs", "list-tasks", "--cluster", CLUSTER, "--service-name", SERVICE, "--desired-status", "RUNNING",
              "--query", "taskArns", "--output", "json")
    return sorted(a.rsplit("/", 1)[-1] for a in json.loads(out))


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--label", required=True)
    p.add_argument("--rate", type=int, default=50)
    p.add_argument("--duration", type=int, default=90)
    p.add_argument("--replace-at", type=int, default=10)
    p.add_argument("--out", required=True)
    a = p.parse_args()

    key = api_key()
    url = ALB + "/v1/quotes/sku-7?qty=2&currency=USD"
    for _ in range(5):  # warm-up
        one(url, key)
    results: list[tuple[float, str]] = []
    details: list[dict[str, object]] = []
    tasks: list[tuple[float, list[str]]] = []
    lock = threading.Lock()
    stop = threading.Event()

    def watch() -> None:
        while not stop.is_set():
            try:
                tasks.append((round(time.monotonic() - t0, 1), running_tasks()))
            except subprocess.CalledProcessError:
                pass
            stop.wait(1)

    def record(fut_t: float, answer: tuple[str, str]) -> None:
        status, detail = answer
        with lock:
            results.append((fut_t, status))
            if not status.startswith("2"):
                details.append({"t_s": fut_t, "wall": time.strftime("%H:%M:%S", time.gmtime(wall0 + fut_t)) + "Z",
                                "status": status, "detail": detail})

    t0, wall0 = time.monotonic(), time.time()
    threading.Thread(target=watch, daemon=True).start()
    replaced = False
    replace_wall = ""
    with ThreadPoolExecutor(max_workers=200) as pool:
        n = 0
        while (now := time.monotonic() - t0) < a.duration:
            if not replaced and now >= a.replace_at:
                replace_wall = time.strftime("%H:%M:%S", time.gmtime()) + "Z"
                aws("ecs", "update-service", "--cluster", CLUSTER, "--service", SERVICE, "--force-new-deployment")
                replaced = True
            due = n / a.rate
            if due > now:
                time.sleep(due - now)
            sent = round(time.monotonic() - t0, 2)
            pool.submit(lambda s=sent: record(s, one(url, key)))
            n += 1
    stop.set()

    counts = Counter(s for _, s in results)
    seen = [set(ts) for _, ts in tasks]
    first, last = (seen[0] if seen else set()), (seen[-1] if seen else set())
    overlap = any(len(s) >= 2 for s in seen)
    replaced_ok = bool(first) and bool(last) and not (first & last)
    failures = [(t, s) for t, s in results if not s.startswith("2")]
    # The edge keeps serving while it drains (only readiness goes DOWN). A failure the replacement causes is a
    # connection error or an answer that is not one of our problem documents (the ALB's 502/503/504). The edge's
    # own `overloaded` / `upstream-unavailable` answers are capacity shedding, counted apart.
    shed = [d for d in details if "urn:keyedge:problem:" in str(d["detail"])]
    replacement = [d for d in details if "urn:keyedge:problem:" not in str(d["detail"])]
    out = {
        "experiment": "drain-on-deploy", "label": a.label, "rate": a.rate, "duration_s": a.duration,
        "replace_at_s": a.replace_at, "requests": len(results), "status_counts": dict(sorted(counts.items())),
        "non_2xx": len(failures), "replacement_failures": len(replacement), "edge_shed": len(shed),
        "edge_shed_types": dict(Counter(str(d["detail"]).split('"type":"')[1].split('"')[0] for d in shed)), "first_failure_s": failures[0][0] if failures else None,
        "last_failure_s": failures[-1][0] if failures else None,
        "started_wall": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(wall0)), "replace_wall": replace_wall,
        "replacement_failure_samples": replacement[:20], "edge_shed_samples": shed[:5], "tasks_replaced": replaced_ok, "old_and_new_overlapped": overlap, "task_timeline": tasks,
    }
    os.makedirs(os.path.dirname(a.out) or ".", exist_ok=True)
    with open(a.out, "w") as f:
        json.dump(out, f, indent=2)
    print(f"{a.label}: {len(results)} requests, replacement failures {len(replacement)}, edge shed {len(shed)}, "
          f"{dict(counts)}, "
          f"replaced {replaced_ok}, overlap {overlap}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
