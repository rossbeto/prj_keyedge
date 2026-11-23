"""Quotes under simulator scenarios, through the running stack (Phase 009).

    make up && make quotes-check
Creates a key, then for each scenario switches upstream-sim (`/__chaos`) and calls the blocking endpoint and its
Reactor twin through the edge sidecar: same status and same body every time; `fx-down` must give a priced quote in
EUR with the FX_UNAVAILABLE warning. Ends on `healthy`. Never prints the key.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
import uuid

EDGE = os.environ.get("EDGE_URL", "http://127.0.0.1:15080")
KEYS = os.environ.get("KEYS_URL", "http://127.0.0.1:15100")
ISSUER = os.environ.get("ISSUER_URL", "http://127.0.0.1:15110")
SIM = os.environ.get("SIM_URL", "http://127.0.0.1:15201")


def call(method: str, url: str, body: object | None = None, headers: dict[str, str] | None = None) -> tuple[int, dict]:
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, json.loads(raw) if raw else {}


def scenario(name: str) -> None:
    urllib.request.urlopen(urllib.request.Request(f"{SIM}/__chaos?name={name}", method="PUT"), timeout=5).read()


def main() -> int:
    _, tok = call("POST", ISSUER + "/token", {"tenant": "acme", "scopes": ["keys:write"]})
    _, created = call("POST", KEYS + "/v1/keys", {"name": "quotes-check", "scopes": ["quotes:read"]},
                      {"Authorization": "Bearer " + tok["accessToken"], "Idempotency-Key": "qc-" + uuid.uuid4().hex[:8]})
    auth = {"Authorization": "Bearer " + created["key"]}
    ok = True
    try:
        for name in ("healthy", "fx-down"):
            scenario(name)
            b = call("GET", EDGE + "/v1/quotes/sku-7?qty=2&currency=USD", None, auth)
            r = call("GET", EDGE + "/v1/quotes-reactive/sku-7?qty=2&currency=USD", None, auth)
            same = b == r
            print(f"{name:10s} blocking HTTP {b[0]} {b[1].get('currency', b[1].get('type'))} {b[1].get('warnings', '')} | "
                  f"reactive HTTP {r[0]} | same: {same}")
            if name == "fx-down":
                ok &= b[0] == 200 and "FX_UNAVAILABLE" in b[1].get("warnings", []) and b[1].get("currency") == "EUR"
            elif name == "healthy":
                ok &= b[0] == 200 and b[1].get("currency") == "USD"
            ok &= same
    finally:
        scenario("healthy")
    print("quotes-check: OK" if ok else "quotes-check: FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
