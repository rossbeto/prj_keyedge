"""Experiment native-vs-jvm (Phase 010): the edge sidecar as a JVM image and as a native image.

    make lab E=native-vs-jvm [TARGET=floci|aws-emulator]     (builds nothing: needs keyedge/edge:dev and :native)
For each flavor: bring the stack up with that edge, then
  * 5 cold starts: `docker start` -> first HTTP 200 from /health/readiness (polled every 10 ms);
  * RSS of the edge process at idle (VmRSS from /proc, read on the host);
  * k6 open model, 200 req/s for 30 s through the edge (valid key, GET /v1/quotes/...): p99, errors,
    edge RSS after the load, edge CPU seconds during the load.
Writes results/labs/native-vs-jvm.json with image digests. Never prints the API key.
"""

from __future__ import annotations

import json
import os
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = os.environ.get("TARGET", "floci")
READY = "http://127.0.0.1:15080/health/readiness"
K6 = "grafana/k6@sha256:3d8e5c7f1d53ec59408c2ffbd9943602a0985ffcbd4a2430ec00f3ffca89796e"  # same pin as calibrate.sh


def sh(*args: str, check: bool = True, capture: bool = True, env: dict | None = None) -> str:
    r = subprocess.run(args, cwd=ROOT, check=check, text=True, capture_output=capture, env={**os.environ, **(env or {})})
    return r.stdout.strip() if capture else ""


def up(flavor: str) -> None:
    sh("make", "up", f"TARGET={TARGET}", f"FLAVOR={flavor}", capture=False)


def edge_container() -> str:
    return sh("docker", "ps", "-q", "--filter", "label=com.docker.compose.service=edge")


def pid_of(container: str) -> int:
    return int(sh("docker", "inspect", "-f", "{{.State.Pid}}", container))


def rss_mib(pid: int) -> float:
    for line in Path(f"/proc/{pid}/status").read_text().splitlines():
        if line.startswith("VmRSS:"):
            return round(int(line.split()[1]) / 1024, 1)
    raise RuntimeError("no VmRSS")


def cpu_seconds(pid: int) -> float:
    fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    return (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK")


def ready() -> bool:
    try:
        with urllib.request.urlopen(READY, timeout=1) as r:
            return r.status == 200
    except (urllib.error.URLError, OSError):
        return False


def startup_logged_ms(container: str) -> int | None:
    """Micronaut's own "Startup completed in N ms" (in-process, without the container start)."""
    import re
    logs = sh("docker", "logs", "--since", "60s", container, check=False) + sh("docker", "logs", container, check=False)
    found = re.findall(r"Startup completed in (\d+)ms", logs)
    return int(found[-1]) if found else None


def cold_start(container: str) -> float:
    sh("docker", "stop", "-t", "30", container)
    t0 = time.perf_counter()
    sh("docker", "start", container)
    while not ready():
        if time.perf_counter() - t0 > 60:
            raise RuntimeError("edge not ready after 60 s")
        time.sleep(0.01)
    return round((time.perf_counter() - t0) * 1000, 1)


def api_key() -> str:
    def post(url: str, body: dict, headers: dict) -> dict:
        req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json", **headers}, method="POST")
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read())
    tok = post("http://127.0.0.1:15110/token", {"tenant": "acme", "scopes": ["keys:write"]}, {})["accessToken"]
    return post("http://127.0.0.1:15100/v1/keys", {"name": "bench", "scopes": ["quotes:read"]},
                {"Authorization": "Bearer " + tok, "Idempotency-Key": "nvj-" + uuid.uuid4().hex[:8]})["key"]


def k6(keys: list[str], rate: int, duration: str, summary: Path) -> None:
    sh("docker", "run", "--rm", "--network", "host", "-u", f"{os.getuid()}:{os.getgid()}",
       "-v", f"{ROOT / 'deploy' / 'k6'}:/scripts:ro", "-v", f"{summary.parent}:/out",
       "-e", "KEYS=" + ",".join(keys), "-e", f"RATE={rate}", "-e", f"DURATION={duration}",
       K6, "run", "-q", "--summary-export", "/out/" + summary.name, "/scripts/edge.js", check=False)


def load(keys: list[str], container: str) -> dict:
    warm = ROOT / "build" / "k6-native-vs-jvm-warmup.json"
    warm.parent.mkdir(exist_ok=True)
    k6(keys, 100, "20s", warm)  # warm-up (JIT of quotes and of a JVM edge, key caches): discarded
    pid = pid_of(container)
    cpu0 = cpu_seconds(pid)
    summary = ROOT / "build" / "k6-native-vs-jvm.json"
    k6(keys, 200, "30s", summary)
    data = json.loads(summary.read_text())
    m = data["metrics"]
    return {
        "p50_ms": round(m["http_req_duration"]["p(50)"], 2),
        "p99_ms": round(m["http_req_duration"].get("p(99)", 0), 2),
        "requests": int(m["http_reqs"]["count"]),
        "error_rate": round(m["http_req_failed"]["value"], 4),
        "statuses": {k.removeprefix("status_"): int(v["count"]) for k, v in m.items() if k.startswith("status_")},
        "rss_mib_after_load": rss_mib(pid),
        "cpu_seconds_during_load": round(cpu_seconds(pid) - cpu0, 2),
    }


def measure(flavor: str) -> dict:
    up(flavor)
    container = edge_container()
    image = sh("docker", "inspect", "-f", "{{.Image}}", container)
    starts, inproc = [], []
    for _ in range(5):
        starts.append(cold_start(container))
        inproc.append(startup_logged_ms(container))
    sh("docker", "restart", sh("docker", "ps", "-q", "--filter", "label=com.docker.compose.service=quotes"))  # quotes shares edge's netns
    time.sleep(5)
    idle = rss_mib(pid_of(container))
    result = {"flavor": flavor, "image_id": image, "start_to_ready_ms": starts,
              "start_to_ready_median_ms": statistics.median(starts),
              "micronaut_startup_logged_ms": inproc, "rss_mib_idle": idle}
    time.sleep(3)
    result.update(load([api_key() for _ in range(6)], container))
    return result


def main() -> int:
    jvm = measure("jvm")
    native = measure("native")
    up("jvm")  # leave the default stack running
    fast_start = native["start_to_ready_median_ms"] < 100
    small = native["rss_mib_idle"] * 3 < jvm["rss_mib_idle"] and native["rss_mib_after_load"] * 3 < jvm["rss_mib_after_load"]
    result = {
        "experiment": "native-vs-jvm",
        "hypothesis": "the native edge reaches readiness in < 100 ms (median of 5 cold starts) with < 1/3 of the JVM"
                      " edge's RSS at idle and after 200 req/s",
        "setup": f"compose stack on {TARGET}; docker start -> readiness 200 polled every 10 ms (includes the "
                 "Secrets Manager reads at start-up); VmRSS of the edge process; k6 200 req/s for 30 s through the edge over 6 keys after a 20 s warm-up at 100 req/s",
        "jvm": jvm,
        "native": native,
        "verdict": "confirmed" if fast_start and small else "refuted",
        "verdict_detail": {"start_under_100ms": fast_start, "rss_under_a_third": small},
        "recorded_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    out = ROOT / "results" / "labs" / "native-vs-jvm.json"
    out.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({k: result[k] for k in ("verdict", "verdict_detail")}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
