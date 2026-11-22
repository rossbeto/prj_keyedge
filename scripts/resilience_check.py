"""Control plane down under the running stack (Phase 008): what does the edge do?

    make up TARGET=floci|aws-emulator && make resilience-check
Creates two keys, uses one through the edge (warm), then disables the Toxiproxy `keys` proxy that the
edge uses to reach keys, and checks:
  1. warm key while its verdict is fresh  -> 200
  2. cold key (never seen by the edge)    -> 503 upstream-unavailable + Retry-After (not 401: it may be valid)
  3. warm key after its TTL has passed    -> 200 (fail-static: served stale) or 503 (FAIL_CLOSED)
  4. proxy enabled again                  -> warm and cold keys 200
Waits out the verify TTL (60 s unless the stack runs with KEYEDGE_VERIFY_TTL). Never prints a secret.
"""

from __future__ import annotations

import base64
import json
import os
import sys
import time
import urllib.request
import uuid

import boto3

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from demo import ISSUER, KEYS, call, signed  # noqa: E402

EDGE = os.environ.get("EDGE_URL", "http://127.0.0.1:15080")
TOXIPROXY = "http://127.0.0.1:15474"
EXPECT_STALE = os.environ.get("KEYEDGE_FAILURE_POLICY", "FAIL_STATIC") == "FAIL_STATIC"


def keys_proxy(enabled: bool) -> None:
    req = urllib.request.Request(f"{TOXIPROXY}/proxies/keys", data=json.dumps({"enabled": enabled}).encode(),
                                 method="POST", headers={"Content-Type": "application/json"})
    urllib.request.urlopen(req, timeout=5).read()


def edge(key: str) -> tuple[int, dict, dict]:
    return call("GET", EDGE + "/v1/quotes/sku-1", None, {"Authorization": "Bearer " + key})


def main() -> int:
    _, _, tok = call("POST", ISSUER + "/token", {"tenant": "acme", "scopes": ["keys:write", "keys:read"]})
    auth = {"Authorization": "Bearer " + tok["accessToken"]}
    keys = []
    for name in ("warm", "cold"):
        _, _, created = call("POST", KEYS + "/v1/keys", {"name": f"resilience-{name}", "scopes": ["quotes:read"]},
                             {**auth, "Idempotency-Key": "res-" + uuid.uuid4().hex[:8]})
        keys.append(created["key"])
    warm, cold = keys
    credential = base64.b64decode(json.loads(boto3.client("secretsmanager").get_secret_value(
        SecretId="keyedge/callers")["SecretString"])["edge-quotes"])
    _, _, verdict = signed(KEYS, "/internal/v1/verify", {"keyId": warm[8:34], "secret": warm[35:78]}, credential)
    ttl = int(verdict.get("ttlSeconds", 60))
    status, _, _ = edge(warm)
    print(f"0. warm the edge's cache: HTTP {status} (verdict TTL {ttl} s)")
    ok = status == 200
    keys_proxy(False)
    t_down = time.monotonic()
    try:
        status, _, _ = edge(warm)
        print(f"1. keys down, warm key, fresh verdict: HTTP {status}")
        ok &= status == 200
        status, headers, problem = edge(cold)
        print(f"2. keys down, cold key: HTTP {status} {problem.get('type')} Retry-After={headers.get('Retry-After')}")
        ok &= status == 503 and headers.get("Retry-After") is not None
        wait = ttl + 2 - (time.monotonic() - t_down)
        print(f"   waiting {wait:.0f} s for the warm verdict to expire ...", flush=True)
        time.sleep(max(0, wait))
        status, _, _ = edge(warm)
        expected = 200 if EXPECT_STALE else 503
        print(f"3. keys down, warm key, verdict expired: HTTP {status} "
              f"({'served stale, fail-static' if status == 200 else 'rejected'}; expected {expected})")
        ok &= status == expected
    finally:
        keys_proxy(True)
    time.sleep(1)
    deadline = time.monotonic() + 30  # the breaker may be open: it lets a probe through after wait-in-open
    statuses: tuple[int, int] = (0, 0)
    while time.monotonic() < deadline:
        statuses = (edge(warm)[0], edge(cold)[0])
        if statuses == (200, 200):
            break
        time.sleep(1)
    print(f"4. keys back: warm HTTP {statuses[0]}, cold HTTP {statuses[1]} (after {time.monotonic() - t_down:.0f} s)")
    ok &= statuses == (200, 200)
    print("resilience-check: OK" if ok else "resilience-check: FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
